// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.cache

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.tatrman.llmgateway.stream.SseFrame

/**
 * Reassembles a non-stream `chat.completion` body from a live stream's OpenAI `chat.completion.chunk`
 * frames, so a `stream:true` MISS still populates the cache (T3). Both provider paths emit OpenAI chunks
 * (passthrough directly; the Anthropic converter re-frames), so one assembler covers both. Content-only —
 * **streamed tool_calls are not reassembled yet** (⚑ LG-P5·S2); a tool-call stream simply isn't stored.
 */
class StreamBodyAssembler(
    private val servedModel: String,
) {
    private val content = StringBuilder()
    private var finishReason: String? = null
    private var usage: JsonObject? = null
    private var sawToolCalls = false
    private var id: String? = null
    private var created: Long? = null

    fun observe(frame: SseFrame) {
        val data = frame.data?.trim() ?: return
        if (data.isEmpty() || data == "[DONE]") return
        val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        if (id == null) id = obj["id"]?.jsonPrimitive?.contentOrNull
        if (created == null) created = obj["created"]?.jsonPrimitive?.longOrNull
        (obj["usage"] as? JsonObject)?.let { usage = it }
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return
        val delta = choice["delta"] as? JsonObject
        delta
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { content.append(it) }
        if (delta?.get("tool_calls") != null) sawToolCalls = true
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
    }

    /** Cacheable only when we assembled real content and no tool_calls (which we don't reassemble yet). */
    fun cacheable(): Boolean = content.isNotEmpty() && !sawToolCalls

    /** A finish_reason means the stream ended cleanly — guards against caching an errored/abandoned stream. */
    fun completed(): Boolean = finishReason != null

    fun assembled(): JsonObject =
        buildJsonObject {
            put("id", id ?: "cached")
            put("object", "chat.completion")
            created?.let { put("created", it) }
            put("model", servedModel)
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        put("index", 0)
                        putJsonObject("message") {
                            put("role", "assistant")
                            put("content", content.toString())
                        }
                        put("finish_reason", finishReason ?: "stop")
                    },
                )
            }
            usage?.let { put("usage", it) }
        }
}
