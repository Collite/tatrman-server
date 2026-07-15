// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.llmgateway.config.RetryConfig
import org.tatrman.llmgateway.provider.Key
import org.tatrman.llmgateway.provider.ProviderHandler
import org.tatrman.llmgateway.provider.ProviderResult
import org.tatrman.llmgateway.provider.UpstreamTarget
import org.tatrman.llmgateway.stream.SseFrame
import org.tatrman.llmgateway.wire.ChatRequest
import org.tatrman.llmgateway.wire.GatewayError
import org.tatrman.llmgateway.wire.OpenAiWireErrorConverter

/**
 * LG-P3·S2·T1/T3 — the retry matrix (contracts §5.3) proven with the PURE decision core [RetryPolicy.nextDelay]
 * (no coroutines) plus the suspend [RetryPolicy.run] loop under kotlinx-coroutines-test **virtual time** — no
 * real sleeps, so backoff/budget behavior is deterministic.
 */
class RetryPolicySpec :
    StringSpec({

        val cfg = RetryConfig(maxAttempts = 3, initialBackoffMs = 250, maxBackoffMs = 4000, wallClockBudgetMs = 15_000)

        // full-jitter pinned to 1.0 → backoff hits its upper bound; deterministic assertions
        fun policy(jitter: Double = 1.0) = RetryPolicy(cfg, jitter = { jitter })

        // ── nextDelay: retryable vs not (contracts §5.3) ────────────────────────────────────────────
        "non-retryable errors never retry (nextDelay = null)" {
            val p = policy()
            listOf(
                GatewayError.Auth(401),
                GatewayError.Validation("bad"),
                GatewayError.ContextLength(),
                GatewayError.ContentFilter(),
                GatewayError.BudgetExceeded(),
                GatewayError.Internal(null),
            ).forEach { p.nextDelay(it, attempt = 1, elapsedMs = 0).shouldBeNull() }
        }
        "retryable errors retry while attempts + budget remain" {
            val p = policy()
            listOf(
                GatewayError.RateLimit(null),
                GatewayError.Timeout(),
                GatewayError.Network(),
                GatewayError.Provider5xx(503),
            ).forEach { p.nextDelay(it, attempt = 1, elapsedMs = 0).shouldNotBeNull() }
        }

        // ── nextDelay: exponential backoff bounds ───────────────────────────────────────────────────
        "exponential backoff: initial·2^(n-1), jitter-scaled, capped at maxBackoff" {
            val big = cfg.copy(maxAttempts = 10, wallClockBudgetMs = 1_000_000)

            fun d(attempt: Int) = RetryPolicy(big, jitter = { 1.0 }).nextDelay(GatewayError.Network(), attempt, 0)
            d(1) shouldBe 250 // 250·2^0
            d(2) shouldBe 500 // 250·2^1
            d(3) shouldBe 1000 // 250·2^2
            d(6) shouldBe 4000 // 250·2^5=8000 → capped at maxBackoff
        }
        "full jitter stays within [0, base]" {
            val big =
                cfg.copy(
                    maxAttempts = 10,
                    initialBackoffMs = 1_000,
                    maxBackoffMs = 8_000,
                    wallClockBudgetMs = 1_000_000,
                )
            RetryPolicy(big, jitter = { 0.0 }).nextDelay(GatewayError.Network(), 2, 0) shouldBe 0 // floor of jitter
            RetryPolicy(big, jitter = { 0.5 }).nextDelay(GatewayError.Network(), 2, 0) shouldBe 1000 // half of 2000
        }

        // ── nextDelay: Retry-After floor + budget cap ───────────────────────────────────────────────
        "Retry-After is a floor even when backoff jitter is tiny" {
            // jitter 0.0 → backoff would be 0; Retry-After 2000 wins
            policy(jitter = 0.0).nextDelay(GatewayError.RateLimit(2_000), 1, 0) shouldBe 2_000
        }
        "delay is capped at the remaining wall-clock budget" {
            // remaining = 15000 - 14500 = 500; Retry-After asks 5000 → capped to 500
            policy(jitter = 0.0).nextDelay(GatewayError.RateLimit(5_000), 1, elapsedMs = 14_500) shouldBe 500
        }

        // ── nextDelay: stopping conditions ──────────────────────────────────────────────────────────
        "attempt cap stops retrying" {
            policy().nextDelay(GatewayError.Network(), attempt = 3, elapsedMs = 0).shouldBeNull() // maxAttempts=3
        }
        "spent budget stops retrying even with attempts left" {
            policy().nextDelay(GatewayError.Network(), attempt = 1, elapsedMs = 15_000).shouldBeNull()
        }

        // ── suspend run() under virtual time ────────────────────────────────────────────────────────
        "run: 429×N exhausts the attempt cap; total wait = Σ backoff (virtual time)" {
            runTest {
                val handler = ScriptedHandler(listOf(429))
                val budget = BudgetClock(cfg.wallClockBudgetMs) { testScheduler.currentTime }
                val outcome =
                    policy().run(handler, TARGET, KEY, OpenAiWireErrorConverter, REQ, budget)

                outcome.shouldBeInstanceOf<AttemptOutcome.Exhausted>()
                (outcome as AttemptOutcome.Exhausted).lastError.shouldBeInstanceOf<GatewayError.RateLimit>()
                handler.calls shouldBe 3 // maxAttempts
                testScheduler.currentTime shouldBe 750 // 250 + 500 (third attempt hits the cap, no 3rd delay)
            }
        }
        "run: shared budget stops mid-retry even when attempts remain" {
            runTest {
                val tight = cfg.copy(maxAttempts = 10, wallClockBudgetMs = 400)
                val handler = ScriptedHandler(listOf(500))
                val budget = BudgetClock(tight.wallClockBudgetMs) { testScheduler.currentTime }
                val outcome =
                    RetryPolicy(
                        tight,
                        jitter = { 1.0 },
                    ).run(handler, TARGET, KEY, OpenAiWireErrorConverter, REQ, budget)

                outcome.shouldBeInstanceOf<AttemptOutcome.Exhausted>()
                handler.calls shouldBe 2 // 250 then 150 (budget-capped) → expired
                testScheduler.currentTime shouldBe 400
            }
        }
        "run: success on a later attempt returns Success" {
            runTest {
                val handler = ScriptedHandler(listOf(503, 503, 200))
                val five = cfg.copy(maxAttempts = 5)
                val budget = BudgetClock(five.wallClockBudgetMs) { testScheduler.currentTime }
                val outcome =
                    RetryPolicy(five, jitter = { 1.0 }).run(handler, TARGET, KEY, OpenAiWireErrorConverter, REQ, budget)

                outcome.shouldBeInstanceOf<AttemptOutcome.Success>()
                handler.calls shouldBe 3
            }
        }
        "run: non-retryable short-circuits immediately (one call, no wait)" {
            runTest {
                val handler = ScriptedHandler(listOf(401))
                val budget = BudgetClock(cfg.wallClockBudgetMs) { testScheduler.currentTime }
                val outcome = policy().run(handler, TARGET, KEY, OpenAiWireErrorConverter, REQ, budget)

                outcome.shouldBeInstanceOf<AttemptOutcome.Exhausted>()
                (outcome as AttemptOutcome.Exhausted).lastError.shouldBeInstanceOf<GatewayError.Auth>()
                handler.calls shouldBe 1
                testScheduler.currentTime shouldBe 0
            }
        }

        // sanity: jitter really is bounded (guards against a sign/scale regression)
        "jitter output magnitude sanity" {
            val d = RetryPolicy(cfg, jitter = { 0.37 }).nextDelay(GatewayError.Network(), 2, 0)!!
            d.toInt() shouldBeInRange 0..500
        }
    })

private val TARGET =
    UpstreamTarget(
        providerName = "azure",
        kind = "openai-wire",
        baseUrl = "http://unused",
        upstream = "m",
        urlPattern = "/v1/{path}",
        apiVersion = null,
        authHeader = "Authorization",
        authScheme = "Bearer",
    )
private val KEY = Key("k")
private val REQ = ChatRequest.parse("""{"model":"m"}""")

/** A provider handler that replays a scripted list of HTTP statuses (last value repeats). */
private class ScriptedHandler(
    private val statuses: List<Int>,
) : ProviderHandler {
    var calls = 0
        private set

    override suspend fun complete(
        req: ChatRequest,
        target: UpstreamTarget,
        key: Key,
    ): ProviderResult {
        val status = statuses[minOf(calls, statuses.size - 1)]
        calls++
        return ProviderResult(status, buildJsonObject {}, null, null)
    }

    override fun stream(
        req: ChatRequest,
        target: UpstreamTarget,
        key: Key,
    ): Flow<SseFrame> = throw UnsupportedOperationException()

    override suspend fun embed(
        rawBody: JsonObject,
        target: UpstreamTarget,
        key: Key,
    ): ProviderResult = throw UnsupportedOperationException()
}
