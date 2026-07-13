package org.tatrman.money.grpc

import com.google.protobuf.util.JsonFormat
import org.tatrman.grounding.v1.ClarificationOption
import org.tatrman.grounding.v1.FxPolicy
import org.tatrman.grounding.v1.GetStatusRequest
import org.tatrman.grounding.v1.GetStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.GroundingServiceGrpcKt
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.slf4j.LoggerFactory
import org.tatrman.money.client.GatewayResponseFormat
import org.tatrman.money.client.LlmGatewayClient
import org.tatrman.money.discover.ColumnRef
import org.tatrman.money.discover.MoneyDiscovery
import org.tatrman.money.obs.MoneyMetrics
import org.tatrman.money.recipe.MoneyRecipe
import org.tatrman.money.recipe.MoneyRecipeBuilder
import org.tatrman.money.recognize.AmountRecognizer

/**
 * `services/money` — the MONEY grounding service (A10). Recognize the amount idiom → target the
 * amount column(s) → recipe: domestic FilterRecipe on `amount_domestic`/`amount`, foreign FX
 * JoinRecipe, or a native currency FilterRecipe (A10.4/A10.5). Multiple amount columns →
 * AWAITING_CLARIFICATION; no groundable target → UNGROUNDABLE; an unrecognized / low-confidence span
 * → the llm-gateway fallback (`source: LLM`, A10.6). Never reads a clock — `reference_datetime` is
 * always taken from the request context.
 */
class MoneyGroundingService(
    private val discovery: MoneyDiscovery,
    private val llmFallback: LlmGatewayClient?,
    private val llmModel: String = "claude-haiku-4-5",
    private val confidenceThreshold: Double = 0.6,
    private val defaultCurrency: String = "CZK",
    private val defaultLocale: String = "cs-CZ",
    private val defaultTolerancePct: Double = 10.0,
    private val metrics: MoneyMetrics = MoneyMetrics.noop(),
) : GroundingServiceGrpcKt.GroundingServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(MoneyGroundingService::class.java)
    private val recognizer = AmountRecognizer()
    private val recipeBuilder = MoneyRecipeBuilder(discovery)

    override suspend fun ground(request: GroundRequest): GroundResponse {
        val startNanos = System.nanoTime()
        val response = groundInternal(request)
        val source = if (response.hasResult()) response.result.source.name else "NONE"
        metrics.recordGround(
            outcome = response.status.name,
            source = source,
            latencyMs = (System.nanoTime() - startNanos) / 1_000_000.0,
        )
        logger.debug(
            "money ground span='{}' pkg='{}' → outcome={} source={}",
            request.spanText,
            request.getPackage(),
            response.status.name,
            source,
        )
        return response
    }

    private suspend fun groundInternal(request: GroundRequest): GroundResponse {
        val ctx = request.context
        // An unset locale must NOT silently fall to en-style parsing: the cs branch reads "100,50" as
        // 100.50 while en strips the comma to 10050 (a 100x error). Default an empty locale the same
        // way defaultCurrency is defaulted, so a CZK-first deployment stays on cs separator rules.
        val locale = ctx.locale.ifEmpty { defaultLocale }
        val amount =
            recognizer.recognize(request.spanText, locale)
                ?: return fallback(request, "could not recognize a monetary amount in '${request.spanText}'")
        if (amount.confidence < confidenceThreshold) {
            return fallback(request, "recognition confidence ${amount.confidence} below threshold $confidenceThreshold")
        }

        val defaultCcy = ctx.defaultCurrency.ifEmpty { defaultCurrency }
        val tolerancePct = if (ctx.tolerancePct > 0.0) ctx.tolerancePct else defaultTolerancePct
        val fxCurrent = amount.atCurrentRate || ctx.fxPolicy == FxPolicy.CURRENT
        val forcedColumn = request.clarificationAnswerId.takeIf { it.isNotEmpty() }

        return when (
            val recipe =
                recipeBuilder.build(
                    amount = amount,
                    pkg = request.getPackage(),
                    defaultCurrency = defaultCcy,
                    tolerancePct = tolerancePct,
                    fxCurrent = fxCurrent,
                    referenceDatetime = ctx.referenceDatetime,
                    forcedColumnName = forcedColumn,
                )
        ) {
            is MoneyRecipe.Ok ->
                GroundResponse
                    .newBuilder()
                    .setStatus(GroundResponse.Status.OK)
                    .setResult(recipe.result)
                    .build()
            is MoneyRecipe.Clarify -> awaitingClarification(recipe.columns)
            is MoneyRecipe.Ungroundable -> ungroundable(recipe.reason)
        }
    }

    override suspend fun getStatus(request: GetStatusRequest): GetStatusResponse {
        val metadataOk = runCatching { discovery.probeReady() }.getOrDefault(false)
        return GetStatusResponse
            .newBuilder()
            .setReady(metadataOk)
            .setService("money")
            .putCapabilities("metadata", if (metadataOk) "ok" else "down")
            .putCapabilities("llm_fallback", (llmFallback != null).toString())
            .build()
    }

    // ----- clarification (which amount column) -----

    private fun awaitingClarification(columns: List<ColumnRef>): GroundResponse {
        val options =
            columns.map { c ->
                ClarificationOption
                    .newBuilder()
                    .setId(c.columnName)
                    .setLabel("${c.entityName}.${c.columnName}")
                    .build()
            }
        return GroundResponse
            .newBuilder()
            .setStatus(GroundResponse.Status.AWAITING_CLARIFICATION)
            .addAllOptions(options)
            .build()
    }

    // ----- llm-gateway fallback (A10.6) -----

    private suspend fun fallback(
        request: GroundRequest,
        reason: String,
    ): GroundResponse {
        val client = llmFallback ?: return ungroundable(reason)
        return try {
            val raw =
                client.chat(
                    model = llmModel,
                    system = LLM_SYSTEM_PROMPT,
                    user = llmUserPrompt(request),
                    responseFormat = GatewayResponseFormat("json_object"),
                )
            parseLlmResult(raw)
                ?.let {
                    GroundResponse
                        .newBuilder()
                        .setStatus(GroundResponse.Status.OK)
                        .setResult(it.toBuilder().setSource(GroundingResult.Source.LLM))
                        .build()
                } ?: ungroundable("$reason; llm fallback returned an invalid GroundingResult")
        } catch (e: Exception) {
            logger.warn("money llm fallback failed for span '{}': {}", request.spanText, e.message)
            ungroundable("$reason; llm fallback failed")
        }
    }

    private fun parseLlmResult(raw: String): GroundingResult? {
        val result =
            runCatching {
                val builder = GroundingResult.newBuilder()
                JsonFormat.parser().ignoringUnknownFields().merge(raw, builder)
                builder.build()
            }.getOrElse {
                logger.warn("money llm fallback: unparseable GroundingResult JSON: {}", it.message)
                return null
            }
        val structurallyValid =
            result.hasNormalized() &&
                result.applicationCase != GroundingResult.ApplicationCase.APPLICATION_NOT_SET &&
                result.sqlPreview.isNotEmpty()
        return result.takeIf { structurallyValid }
    }

    private fun llmUserPrompt(request: GroundRequest): String =
        """
        Span: "${request.spanText}"
        Question: "${request.questionText}"
        default_currency: ${request.context.defaultCurrency}
        locale: ${request.context.locale}
        package: ${request.getPackage()}
        """.trimIndent()

    private fun ungroundable(message: String): GroundResponse =
        GroundResponse
            .newBuilder()
            .setStatus(GroundResponse.Status.UNGROUNDABLE)
            .addMessages(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.INFO)
                    .setCode("ungroundable")
                    .setHumanMessage(message),
            ).build()

    private companion object {
        const val LLM_SYSTEM_PROMPT =
            "You ground a monetary expression to a JSON GroundingResult (org.tatrman.grounding.v1). " +
                "Return ONLY the JSON object: a `normalized.money` {amount, currency, lower_bound?, " +
                "upper_bound?}, a `filter` recipe (condition + parameters + anchorColumn) over the amount " +
                "column or a `join` recipe for FX conversion, `sqlPreview` (Calcite SQL, double-quoted " +
                "identifiers, named params {name}), `confidence` 0..1, and a one-line `explanation`."
    }
}
