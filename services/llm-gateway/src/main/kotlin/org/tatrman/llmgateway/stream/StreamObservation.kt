// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.tatrman.llmgateway.wire.ErrorConverter
import org.tatrman.llmgateway.wire.GatewayError
import org.tatrman.llmgateway.wire.OpenAiWireErrorConverter

/**
 * The tap's side channel (contracts §5.2, B-3). The passthrough writer forwards raw frame bytes to the
 * client; the tap emits these observations in parallel for settlement, metrics, and the retry/fallback
 * switch — consumers see ONLY observations, never the frames. [FirstToken] flips retry/fallback OFF
 * (the before-first-token rule, LG-P3·S2).
 */
sealed interface StreamObservation {
    data class Opened(
        val provider: String,
        val model: String,
    ) : StreamObservation

    data class FirstToken(
        val ttfbMs: Long,
    ) : StreamObservation

    data class ContentDelta(
        val approxChars: Int,
    ) : StreamObservation

    data class UsageChunk(
        val usage: StreamUsage,
    ) : StreamObservation

    data class Finish(
        val finishReason: String?,
    ) : StreamObservation

    data class ErrorFrame(
        val error: GatewayError,
    ) : StreamObservation

    data object Done : StreamObservation
}

/** Parse-lite usage from a streaming `usage` chunk — the exact names Hebe reads today, plus cached tokens. */
data class StreamUsage(
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

/**
 * Parse-lite tap over OpenAI-wire `chat.completion.chunk` frames. Maps each frame to zero or more
 * [StreamObservation]s; it NEVER alters frames (the raw bytes flow to the client unchanged). Comment
 * and empty blocks (heartbeats) are ignored — never mistaken for a token. Stateful across a stream:
 * tracks the first content/tool-call delta for TTFB.
 *
 * Tool-call deltas surface as [StreamObservation.ContentDelta] (approx-chars over the fragment) — §5.2
 * has no dedicated tool-call member; the index-integrity guarantee is a passthrough-bytes property,
 * proven at the framer/writer level, not a tap observation (recorded in the S2 findings).
 */
class TapParser(
    private val provider: String,
    private val model: String,
    private val converter: ErrorConverter = OpenAiWireErrorConverter,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val startMs = nowMs()

    /** True once the first content/tool-call delta has been seen — the before-first-token switch. */
    var firstTokenSeen: Boolean = false
        private set

    /** The stream-open observation (provider/model); the writer emits it once before the first frame. */
    fun opened(): StreamObservation = StreamObservation.Opened(provider, model)

    fun onFrame(frame: SseFrame): List<StreamObservation> {
        val data = frame.data?.trim() ?: return emptyList() // comment / empty block → ignore
        if (data == "[DONE]") return listOf(StreamObservation.Done)
        val json = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return emptyList()

        // An upstream error FRAME (mid-stream): no HTTP status exists, so map as a server-side failure.
        if (json["error"] is JsonObject) {
            return listOf(StreamObservation.ErrorFrame(converter.convert(MID_STREAM_ERROR_STATUS, json)))
        }

        val out = mutableListOf<StreamObservation>()
        val choice = (json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val delta = choice?.get("delta") as? JsonObject
        val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
        val toolCalls = delta?.get("tool_calls") as? JsonArray

        val chars =
            when {
                !content.isNullOrEmpty() -> content.length
                toolCalls != null && toolCalls.isNotEmpty() -> toolCallChars(toolCalls)
                else -> 0
            }
        if (chars > 0) {
            if (!firstTokenSeen) {
                firstTokenSeen = true
                out += StreamObservation.FirstToken(nowMs() - startMs)
            }
            out += StreamObservation.ContentDelta(chars)
        }

        (json["usage"] as? JsonObject)?.let { out += StreamObservation.UsageChunk(parseUsage(it)) }

        choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull?.let {
            out += StreamObservation.Finish(it)
        }
        return out
    }

    private fun toolCallChars(toolCalls: JsonArray): Int =
        toolCalls.sumOf { tc ->
            val fn = (tc as? JsonObject)?.get("function") as? JsonObject
            val name =
                fn
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.length ?: 0
            val args =
                fn
                    ?.get("arguments")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.length ?: 0
            name + args
        }

    private companion object {
        // Mid-stream error frames carry no HTTP status; treat as a server-side failure (Provider5xx family).
        const val MID_STREAM_ERROR_STATUS = 500
    }
}

/** Read `prompt_tokens` / `completion_tokens` / `prompt_tokens_details.cached_tokens` from a usage object. */
fun parseUsage(usage: JsonObject): StreamUsage {
    val prompt = usage["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: 0
    val completion = usage["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0
    val cached =
        (usage["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0
    return StreamUsage(prompt, completion, cached)
}
