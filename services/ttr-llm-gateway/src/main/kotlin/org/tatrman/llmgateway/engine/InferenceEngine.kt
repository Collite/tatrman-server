// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import io.opentelemetry.api.trace.Tracer
import org.tatrman.llmgateway.config.ProvidersConfig
import org.tatrman.llmgateway.observability.GatewayMetrics
import shared.otel.withSpan
import org.tatrman.llmgateway.provider.ProviderResult
import org.tatrman.llmgateway.provider.RegistryEntry
import org.tatrman.llmgateway.provider.UpstreamStreamException
import org.tatrman.llmgateway.stream.SseFrame
import org.tatrman.llmgateway.wire.ChatRequest
import org.tatrman.llmgateway.wire.GatewayError

/**
 * The resilience engine (design §3.2 C-3/C-4, architecture §3.1): runs a resolved fallback chain with
 * typed retries per entry, a **chain-shared wall-clock budget**, and per-instance circuit-breaker skip-
 * ahead. It is the only place retry/fallback decisions live; both routes call it. The **before-first-token
 * rule is structural**: the whole attempt loop completes before any SSE bytes reach the client — for
 * streaming, [openStream] peeks the first frame per attempt and only a *successful* attempt's stream is
 * handed back (so an all-exhausted stream still yields a real HTTP status, not an in-band error frame).
 */
class InferenceEngine(
    providers: ProvidersConfig,
    private val circuit: CircuitBreaker,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val retry: RetryPolicy = RetryPolicy(providers.retry),
    private val metrics: GatewayMetrics? = null, // §6 retries/fallbacks (optional so tests/storeless skip)
    private val tracer: Tracer? = null, // §6 `llm-gateway.attempt` spans, children of the request span
) {
    private val budgetMs = providers.retry.wallClockBudgetMs

    /** Wrap one provider attempt in an `llm-gateway.attempt {provider,model,attempt_no}` span (siblings, §6). */
    private suspend fun <T> attemptSpan(
        entry: RegistryEntry,
        attemptNo: Int,
        block: suspend () -> T,
    ): T =
        tracer?.withSpan(
            "llm-gateway.attempt",
            attributes =
                mapOf(
                    "provider" to entry.target.providerName,
                    "model" to entry.target.upstream,
                    "attempt_no" to attemptNo.toString(),
                ),
            block = block,
        ) ?: block()

    // ── non-stream ────────────────────────────────────────────────────────────────────────────────────

    /** Outcome of running the non-stream chain. Carries the SERVING entry + fallback/strip context (T6). */
    sealed interface ChainResult {
        data class Ok(
            val result: ProviderResult,
            val serving: RegistryEntry,
            val fallbackFrom: String?, // the originally-requested model name if a fallback served; else null
            val strippedParams: List<String>, // C-4: params the converter dropped on a crossing
        ) : ChainResult

        data class Failed(
            val error: GatewayError,
            val lastAttempted: RegistryEntry,
        ) : ChainResult
    }

    suspend fun complete(
        chain: List<RegistryEntry>,
        req: ChatRequest,
    ): ChainResult {
        val budget = BudgetClock(budgetMs, nowMs)
        // Default stands only if EVERY entry is circuit-open (all skipped, none attempted) → 502 unavailable.
        var lastError: GatewayError = GatewayError.Provider5xx(503)
        var lastAttempted = chain.first()
        var attemptNo = 0
        for (entry in chain) {
            val handler = entry.handler ?: continue
            if (circuit.shouldSkip(entry.target.providerName)) continue
            lastAttempted = entry
            attemptNo++
            val onRetry = { reason: String ->
                metrics?.retry(entry.target.providerName, reason)
                Unit
            }
            val outcome =
                attemptSpan(entry, attemptNo) {
                    retry.run(handler, entry.target, entry.key, entry.errorConverter, req, budget, onRetry)
                }
            when (outcome) {
                is AttemptOutcome.Success -> {
                    circuit.recordSuccess(entry.target.providerName)
                    val from = if (entry === chain.first()) null else chain.first().model.name
                    if (from != null) metrics?.fallback(from, entry.model.name)
                    logCrossing(from, entry, outcome.result.stripped)
                    return ChainResult.Ok(outcome.result, entry, from, outcome.result.stripped)
                }
                is AttemptOutcome.Exhausted -> {
                    circuit.recordFailure(entry.target.providerName, outcome.lastError::class.simpleName ?: "?")
                    lastError = outcome.lastError
                    if (!outcome.lastError.chainEligible || budget.expired()) break
                }
            }
        }
        return ChainResult.Failed(lastError, lastAttempted)
    }

    // ── stream (before-first-token rule) ────────────────────────────────────────────────────────────────

    sealed interface StreamOpen {
        /** A first frame arrived from [serving]; [frames] replays it then continues — hand to the SSE writer. */
        data class Attached(
            val serving: RegistryEntry,
            val fallbackFrom: String?,
            val strippedParams: List<String>,
            val frames: Flow<SseFrame>,
        ) : StreamOpen

        /** Every eligible entry failed BEFORE any frame — commit a real HTTP status (no bytes were sent). */
        data class Failed(
            val error: GatewayError,
            val lastAttempted: RegistryEntry,
        ) : StreamOpen
    }

    /**
     * Drive the chain to a first frame. [scope] owns the surviving collector (bind it to the call so a
     * client disconnect cancels the upstream read). Runs entirely before the caller attaches the SSE writer.
     */
    suspend fun openStream(
        chain: List<RegistryEntry>,
        req: ChatRequest,
        scope: CoroutineScope,
    ): StreamOpen {
        val budget = BudgetClock(budgetMs, nowMs)
        // Default stands only if EVERY entry is circuit-open (all skipped, none attempted) → 502 unavailable.
        var lastError: GatewayError = GatewayError.Provider5xx(503)
        var lastAttempted = chain.first()
        var attemptNo = 0
        for (entry in chain) {
            if (entry.handler == null) continue
            if (circuit.shouldSkip(entry.target.providerName)) continue
            lastAttempted = entry
            attemptNo++
            when (val outcome = attemptSpan(entry, attemptNo) { attemptEntryStream(entry, req, budget, scope) }) {
                is EntryStream.FirstFrame -> {
                    // ⚑ Circuit success is recorded at first-frame: a provider that reliably delivers one
                    // frame then dies mid-stream reads as a success and never trips its breaker. Acceptable
                    // (first token ⇒ reachable); post-first-token drops surface as the §1.4 error frame, not a
                    // circuit signal. Revisit if mid-stream degradation needs to open a circuit (LG-P5).
                    circuit.recordSuccess(entry.target.providerName)
                    val from = if (entry === chain.first()) null else chain.first().model.name
                    if (from != null) metrics?.fallback(from, entry.model.name)
                    logCrossing(from, entry, emptyList())
                    return StreamOpen.Attached(entry, from, emptyList(), outcome.frames.consumeAsFlow())
                }
                is EntryStream.Exhausted -> {
                    circuit.recordFailure(entry.target.providerName, outcome.error::class.simpleName ?: "?")
                    lastError = outcome.error
                    if (!outcome.error.chainEligible || budget.expired()) break
                }
            }
        }
        return StreamOpen.Failed(lastError, lastAttempted)
    }

    private sealed interface EntryStream {
        data class FirstFrame(
            val frames: Channel<SseFrame>,
        ) : EntryStream

        data class Exhausted(
            val error: GatewayError,
        ) : EntryStream
    }

    /** Retry ONE entry until its first frame arrives or retries are exhausted (before-first-token only). */
    private suspend fun attemptEntryStream(
        entry: RegistryEntry,
        req: ChatRequest,
        budget: BudgetClock,
        scope: CoroutineScope,
    ): EntryStream {
        var attempt = 0
        while (true) {
            attempt++
            val probe = probeFirstFrame(entry, req, scope)
            if (probe.error == null) return EntryStream.FirstFrame(probe.channel!!)
            val wait =
                retry.nextDelay(probe.error, attempt, budget.elapsedMs()) ?: return EntryStream.Exhausted(probe.error)
            metrics?.retry(entry.target.providerName, probe.error::class.simpleName ?: "?")
            delay(wait)
            if (budget.expired()) return EntryStream.Exhausted(probe.error)
        }
    }

    private class Probe(
        val error: GatewayError?,
        val channel: Channel<SseFrame>?,
    )

    /**
     * Launch collection of one streaming attempt into a buffered channel; suspend until either the first
     * frame lands (→ success; the channel holds that frame + the rest) or the upstream fails before any
     * frame (→ typed error, collector cancelled). A 2xx stream that closes with zero frames is treated as a
     * retryable Network failure.
     */
    private suspend fun probeFirstFrame(
        entry: RegistryEntry,
        req: ChatRequest,
        scope: CoroutineScope,
    ): Probe {
        val channel = Channel<SseFrame>(capacity = Channel.BUFFERED)
        val firstSignal = CompletableDeferred<GatewayError?>() // null = first frame seen; else pre-frame error
        val job =
            scope.launch {
                var seenFirst = false
                try {
                    entry.handler!!.stream(req, entry.target, entry.key).collect { frame ->
                        if (!seenFirst) {
                            seenFirst = true
                            channel.send(frame)
                            firstSignal.complete(null)
                        } else {
                            channel.send(frame)
                        }
                    }
                    if (seenFirst) {
                        channel.close()
                    } else {
                        firstSignal.complete(GatewayError.Network()) // 2xx but zero frames
                    }
                } catch (e: UpstreamStreamException) {
                    if (!firstSignal.complete(e.error)) channel.close(e) // post-frame: propagate to the consumer
                } catch (e: CancellationException) {
                    channel.close(e)
                    throw e
                } catch (e: Exception) {
                    if (!firstSignal.complete(GatewayError.Network())) channel.close(e)
                }
            }
        val error = firstSignal.await()
        return if (error == null) {
            Probe(null, channel)
        } else {
            job.cancel()
            Probe(error, null)
        }
    }

    private fun logCrossing(
        fallbackFrom: String?,
        serving: RegistryEntry,
        stripped: List<String>,
    ) {
        if (fallbackFrom != null) {
            log.info(
                "fallback: {} → {} (provider {}); stripped={}",
                fallbackFrom,
                serving.model.name,
                serving.target.providerName,
                stripped,
            )
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(InferenceEngine::class.java)
    }
}
