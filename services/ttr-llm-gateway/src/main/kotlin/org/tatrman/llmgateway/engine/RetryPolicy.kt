// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.engine

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.tatrman.llmgateway.config.RetryConfig
import org.tatrman.llmgateway.provider.Key
import org.tatrman.llmgateway.provider.ProviderHandler
import org.tatrman.llmgateway.provider.ProviderResult
import org.tatrman.llmgateway.provider.UpstreamTarget
import org.tatrman.llmgateway.wire.ChatRequest
import org.tatrman.llmgateway.wire.ErrorConverter
import org.tatrman.llmgateway.wire.GatewayError
import kotlin.random.Random

/**
 * A monotonic wall-clock budget (C-3). Started once per chain and **shared across every entry** so the
 * total time a caller waits is bounded regardless of how many providers/retries are tried (architecture
 * §3.1; ⚑ shared-not-per-entry is the interactive-caller call — one-line flip to per-entry if Bora rejects).
 */
class BudgetClock(
    private val budgetMs: Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val start = nowMs()

    fun elapsedMs(): Long = nowMs() - start

    fun remainingMs(): Long = (budgetMs - elapsedMs()).coerceAtLeast(0)

    fun expired(): Boolean = remainingMs() <= 0
}

/** The result of running the retry loop against ONE chain entry. */
sealed interface AttemptOutcome {
    data class Success(
        val result: ProviderResult,
    ) : AttemptOutcome

    /** Retries exhausted (attempt cap, budget, or non-retryable). [lastError] decides chain eligibility. */
    data class Exhausted(
        val lastError: GatewayError,
    ) : AttemptOutcome
}

/**
 * Typed retry over a single provider (design §3.2 C-3, contracts §5.3). Retryable = RateLimit / Timeout /
 * Network / Provider5xx; everything else short-circuits. Exponential backoff with full jitter in
 * `[0, initial·2^(n-1) ⊓ maxBackoff]`, honoring `Retry-After` as a floor, all capped by the remaining
 * shared wall-clock budget. The decision core [nextDelay] is pure (no coroutines) so the matrix is proven
 * under virtual time; [run] is the suspend loop that applies it.
 */
class RetryPolicy(
    private val config: RetryConfig,
    // Full-jitter source in [0,1); injected so backoff bounds are deterministic under test (the resolver's
    // determinism gate — a grep for a randomness call in main — is about routing, not retry backoff jitter).
    private val jitter: () -> Double = { Random.nextDouble() },
) {
    /**
     * The delay (ms) to wait before the next attempt after [error] on attempt [attempt] (1-based, the
     * attempt that just failed), given [elapsedMs] of the shared budget already spent — or `null` to STOP
     * (non-retryable, attempt cap reached, or no budget left). Pure.
     */
    fun nextDelay(
        error: GatewayError,
        attempt: Int,
        elapsedMs: Long,
    ): Long? {
        if (!error.retryable) return null
        if (attempt >= config.maxAttempts) return null
        val remaining = config.wallClockBudgetMs - elapsedMs
        if (remaining <= 0) return null

        // exponential backoff: initial · 2^(attempt-1), capped at maxBackoff, then full jitter in [0, base]
        val shift = (attempt - 1).coerceIn(0, 30)
        val base = (config.initialBackoffMs shl shift).coerceAtMost(config.maxBackoffMs)
        val jittered = (base * jitter().coerceIn(0.0, 1.0)).toLong()

        // Retry-After (RateLimit) is a floor — never retry sooner than the server asked; still budget-capped.
        val floor = (error as? GatewayError.RateLimit)?.retryAfterMs ?: 0L
        return maxOf(jittered, floor).coerceAtMost(remaining)
    }

    /**
     * Run attempts against one entry until success or exhaustion. [budget] is the chain-shared clock. The
     * error is reclassified each attempt via [errorConverter]; a `RateLimit` is enriched with the response's
     * `Retry-After` (the converter emits `RateLimit(null)`; the header value rides on [ProviderResult]).
     */
    suspend fun run(
        handler: ProviderHandler,
        target: UpstreamTarget,
        key: Key,
        errorConverter: ErrorConverter,
        req: ChatRequest,
        budget: BudgetClock,
    ): AttemptOutcome {
        var attempt = 0
        while (true) {
            attempt++
            val error =
                try {
                    val result = handler.complete(req, target, key)
                    if (result.status in 200..299) return AttemptOutcome.Success(result)
                    enrich(errorConverter.convert(result.status, result.body), result.retryAfterMs)
                } catch (e: CancellationException) {
                    throw e // client disconnect / scope cancel — never swallow (structured concurrency)
                } catch (e: Exception) {
                    // A transport-level failure (connection refused/reset, DNS, TTFB timeout) never produced an
                    // HTTP status, so the converter never sees it. Classify it here as retryable/chain-eligible
                    // per §5.3 — otherwise it would escape the engine and skip retry AND fallback entirely (the
                    // streaming path already does this in InferenceEngine.probeFirstFrame).
                    transportError(e)
                }
            val wait = nextDelay(error, attempt, budget.elapsedMs()) ?: return AttemptOutcome.Exhausted(error)
            delay(wait)
            if (budget.expired()) return AttemptOutcome.Exhausted(error)
        }
    }

    // Map a thrown upstream transport exception onto the typed vocabulary: connect/socket/TTFB timeouts are
    // Timeout, everything else (connection refused/reset, DNS, IO) is Network — both retryable + chainEligible.
    private fun transportError(e: Exception): GatewayError =
        when (e) {
            is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException ->
                GatewayError.Timeout()
            else -> GatewayError.Network()
        }

    // The converter can't see the Retry-After header (it lives on ProviderResult); fold it into RateLimit.
    private fun enrich(
        error: GatewayError,
        retryAfterMs: Long?,
    ): GatewayError =
        if (error is GatewayError.RateLimit && retryAfterMs != null) {
            GatewayError.RateLimit(retryAfterMs)
        } else {
            error
        }
}
