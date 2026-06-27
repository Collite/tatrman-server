package org.tatrman.kantheon.argos.stages

import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.argos.client.GatewayResponseFormat
import org.tatrman.kantheon.argos.client.LlmGatewayClient
import org.tatrman.kantheon.argos.client.LlmGatewayException

/**
 * LLM_GUARD stage (DF-V04 / Phase 06 B1) — a semantic last-mile review of the validated plan.
 *
 * Off by default (`validator.llm-guard.enabled = false`). When enabled and a [LlmGatewayClient]
 * is configured, the guard renders a compact summary of the plan and asks the gateway-routed
 * model for a strict-JSON verdict:
 *
 *   ```json
 *   {"verdict": "approve" | "reject" | "caveat", "reason": "<short>"}
 *   ```
 *
 * Verdict semantics:
 *   - **approve** → `Decision.Approved` (logged as `llm_guard_approved` warning).
 *   - **caveat** → `Decision.Approved` with the model's reason surfaced as an `llm_guard_caveat`
 *     warning to the caller — the query runs but the agent sees the concern.
 *   - **reject** → `Decision.Rejected` with the model's reason — the gRPC surface returns no plan
 *     and an `llm_guard_rejected` ERROR.
 *
 * **Failure posture** (configurable, `validator.llm-guard.failure-posture`):
 *   - `FAIL_CLOSED` (default): gateway unreachable / non-2xx / parse failure → reject the query.
 *     This is the safer posture for a *guard* — the query is treated as suspicious until the
 *     model says otherwise.
 *   - `FAIL_OPEN`: same scenarios → approve with an `llm_guard_unavailable` warning. Use only
 *     when the guard is advisory and downstream tolerates ungated queries.
 *
 * Enable flag is respected: when `enabled = false`, `evaluate` returns `null` immediately with
 * no gateway call (no latency). When `enabled = true` but no gateway is wired (gateway-url not
 * set in conf), the guard logs a warning and approves with an `llm_guard_skeleton` warning so the
 * pipeline can boot in dev without a gateway running.
 */
class LlmGuard(
    private val enabled: Boolean,
    private val gateway: LlmGatewayClient? = null,
    private val model: String = DEFAULT_MODEL,
    private val failurePosture: FailurePosture = FailurePosture.FAIL_CLOSED,
) {
    suspend fun evaluate(
        plan: PlanNode,
        @Suppress("UNUSED_PARAMETER") context: PipelineContext,
    ): Decision? {
        if (!enabled) return null
        if (gateway == null) {
            log.warn("LlmGuard enabled but no gateway configured — approving with skeleton warning.")
            return Decision.Approved(
                warning =
                    warning(
                        "llm_guard_skeleton",
                        "LlmGuard is enabled but no LLM gateway is configured; no semantic review performed.",
                    ),
                plan = plan,
            )
        }
        val verdict =
            try {
                askGateway(plan)
            } catch (t: LlmGatewayException) {
                log.warn("LlmGuard gateway call failed ({}): {}", failurePosture, t.message)
                return onFailure(t, plan)
            }
        return when (verdict.verdict.lowercase()) {
            "reject" ->
                Decision.Rejected(
                    reason =
                        verdict.reason.takeIf { it.isNotBlank() } ?: "LlmGuard rejected the query.",
                )
            "caveat" ->
                Decision.Approved(
                    warning =
                        warning(
                            "llm_guard_caveat",
                            "LlmGuard approved with caveat: ${verdict.reason.ifBlank { "<no reason>" }}",
                        ),
                    plan = plan,
                )
            else ->
                // "approve" or any unexpected verdict string treated as approved-with-warning so a
                // permissive model can't accidentally reject a legitimate query.
                Decision.Approved(
                    warning =
                        warning(
                            "llm_guard_approved",
                            verdict.reason.ifBlank { "LlmGuard approved the query." },
                        ),
                    plan = plan,
                )
        }
    }

    private suspend fun askGateway(plan: PlanNode): Verdict {
        val summary = renderPlan(plan)
        val raw =
            gateway!!.chat(
                model = model,
                system = SYSTEM_PROMPT,
                user = USER_PROMPT_TEMPLATE.replace("{plan}", summary),
                responseFormat = GatewayResponseFormat(type = "json_object"),
            )
        return parseVerdict(raw)
    }

    private fun onFailure(
        cause: Throwable,
        plan: PlanNode,
    ): Decision =
        when (failurePosture) {
            FailurePosture.FAIL_CLOSED ->
                Decision.Rejected(
                    reason = "LlmGuard unavailable (${cause.message ?: cause::class.simpleName}); failing closed.",
                )
            FailurePosture.FAIL_OPEN ->
                Decision.Approved(
                    warning =
                        warning(
                            "llm_guard_unavailable",
                            "LlmGuard unavailable (${cause.message ?: cause::class.simpleName}); failing open.",
                        ),
                    plan = plan,
                )
        }

    /**
     * Compact rendering of the plan for the LLM prompt. Uses the proto debug string (human
     * readable) and caps at [PLAN_RENDER_CAP] characters to bound prompt tokens; the LLM sees
     * the structurally interesting bits (TableScans, Filters, Projects, Aggregates) before any
     * truncation kicks in for realistic plans.
     */
    private fun renderPlan(plan: PlanNode): String =
        plan
            .toString()
            .lineSequence()
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(PLAN_RENDER_CAP)

    private fun parseVerdict(raw: String): Verdict =
        try {
            JSON_PARSER.decodeFromString(Verdict.serializer(), raw)
        } catch (t: Throwable) {
            log.warn(
                "LlmGuard verdict not parseable ({}); treating as reject. Raw='{}'",
                t.message,
                raw.take(200),
            )
            Verdict(verdict = "reject", reason = "LlmGuard returned a malformed verdict.")
        }

    private fun warning(
        code: String,
        msg: String,
    ): ResponseMessage =
        ResponseMessage
            .newBuilder()
            .setSeverity(Severity.WARNING)
            .setCode(code)
            .setHumanMessage(msg)
            .build()

    enum class FailurePosture {
        FAIL_CLOSED,
        FAIL_OPEN,
    }

    @Serializable
    data class Verdict(
        val verdict: String,
        val reason: String = "",
    )

    sealed interface Decision {
        data class Approved(
            val warning: ResponseMessage,
            val plan: PlanNode,
        ) : Decision

        data class Rejected(
            val reason: String,
        ) : Decision
    }

    companion object {
        const val DEFAULT_MODEL: String = "claude-haiku-4-5"
        private const val PLAN_RENDER_CAP = 4_000
        private val log = LoggerFactory.getLogger(LlmGuard::class.java)
        private val JSON_PARSER =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        private val SYSTEM_PROMPT =
            """
            You are a query-safety reviewer for an analytical SQL platform. You see a structured
            plan (tables, filters, joins, aggregates, security predicates already applied) and
            decide whether to allow it.

            Reject the query if it:
              - exfiltrates plausibly sensitive data without an obvious analytical purpose
                (e.g. dumping entire customer / employee tables row-by-row without aggregation)
              - bypasses or attempts to neutralise security predicates (e.g. tautological filters)
              - is obviously malformed in a way the upstream validator missed.

            Approve when the plan looks like ordinary analysis. Use "caveat" when you have a
            non-blocking concern the analyst should see.

            Always answer with a SINGLE strict-JSON object, no prose, no code fences:
              {"verdict": "approve" | "reject" | "caveat", "reason": "<one short sentence>"}
            """.trimIndent()

        private val USER_PROMPT_TEMPLATE =
            """
            Plan to review (proto debug form):
            ```
            {plan}
            ```

            Respond with the verdict JSON object.
            """.trimIndent()
    }
}
