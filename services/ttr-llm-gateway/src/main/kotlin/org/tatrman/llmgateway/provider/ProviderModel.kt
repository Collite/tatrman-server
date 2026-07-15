// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import org.tatrman.llmgateway.stream.SseFrame
import org.tatrman.llmgateway.wire.ChatRequest

/**
 * A provider upstream key. **Passed per call, never baked into a handler at construction** (C-5 — the
 * ai-gateway lesson: keys-at-construction make rotation/fallback impossible). Resolved from the
 * provider's `keyEnv` at startup by the registry.
 */
@JvmInline
value class Key(
    val value: String,
)

/**
 * The catalog-row × provider projection a handler needs to reach one upstream (contracts §5.4). Carries
 * no key — that arrives per call.
 */
data class UpstreamTarget(
    val providerName: String, // "azure" — surfaced as X-Gateway-Provider (C-4)
    val kind: String, // "openai-wire" | "anthropic"
    val baseUrl: String,
    val upstream: String, // deployment (azure) / model id (others) — the served model, X-Gateway-Model
    val urlPattern: String, // path template: {upstream} {path} {apiVersion}
    val apiVersion: String?, // azure query param (?api-version=)
    val authHeader: String, // "api-key" | "Authorization" | "x-api-key"
    val authScheme: String?, // "Bearer" | null
    val providerVersion: String? = null, // anthropic → the `anthropic-version` header value
    val defaultMaxTokens: Int? = null, // anthropic → max_tokens fallback when the request omits it (REQUIRED upstream)
)

/** Parse-lite extraction from a (non-stream) upstream body — enough for settlement, no full parse. */
data class UpstreamUsage(
    val promptTokens: Long,
    val completionTokens: Long,
)

/** A non-stream provider call result: the upstream status + body (bytes preserved) + parse-lite tap. */
data class ProviderResult(
    val status: Int,
    val body: JsonObject,
    val usage: UpstreamUsage?,
    val finishReason: String?,
    // Retry-After (ms) parsed off the upstream response header, if present — the retry engine (LG-P3·S2)
    // reads it for the RateLimit backoff floor (the converter emits RateLimit(null); the header lives here).
    val retryAfterMs: Long? = null,
    // OpenAI-dialect params the converter dropped on a converter crossing (C-4) — surfaced so the engine
    // context can record which params were stripped-and-logged when a fallback replays through the converter.
    val stripped: List<String> = emptyList(),
)

/**
 * One call contract for every provider (contracts §5.4). Passthrough (this stage) and the Anthropic
 * converter (LG-P2·S3) implement it; `stream()` arrives with the SSE tap in LG-P2·S2.
 */
interface ProviderHandler {
    suspend fun complete(
        req: ChatRequest,
        target: UpstreamTarget,
        key: Key,
    ): ProviderResult

    /**
     * A streaming completion as a cold [Flow] of [SseFrame]. The upstream `execute` runs INSIDE the flow
     * (the IrisBffClient idiom), so the upstream connection lives exactly as long as the collector — which
     * is the server's SSE writer (Ktor defers the response body producer past the route handler's return,
     * so the upstream call must not be execute-scoped to the handler). Cancelling the collector (client
     * disconnect) cancels the upstream read via structured concurrency. The tap is applied by the collector
     * (the route), keeping the handler a pure frame producer (LG-P2·S2).
     */
    fun stream(
        req: ChatRequest,
        target: UpstreamTarget,
        key: Key,
    ): Flow<SseFrame>

    suspend fun embed(
        rawBody: JsonObject,
        target: UpstreamTarget,
        key: Key,
    ): ProviderResult
}
