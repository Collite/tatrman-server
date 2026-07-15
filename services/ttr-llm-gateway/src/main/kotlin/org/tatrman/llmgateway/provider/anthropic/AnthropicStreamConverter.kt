// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.tatrman.llmgateway.stream.SseFrame
import org.tatrman.llmgateway.wire.AnthropicErrorConverter
import org.tatrman.llmgateway.wire.openAiErrorBody
import org.tatrman.llmgateway.wire.render

/**
 * Stateful Anthropic Messages SSE → OpenAI `chat.completion.chunk` frame converter (LG-P2·S3). Maps the
 * `message_start / content_block_start / content_block_delta / message_delta / message_stop` event family
 * to the OpenAI streaming shape, preserving tool-call **index integrity** (`input_json_delta` fragments →
 * `tool_calls[].function.arguments` deltas). Its output frames are ordinary OpenAI chunks, so the SAME
 * passthrough tap + SSE writer drive both paths (B-3 tap parity). `ping`/`content_block_stop` are dropped;
 * an Anthropic `error` event becomes an OpenAI error frame via [AnthropicErrorConverter].
 */
class AnthropicStreamConverter(
    private val model: String,
    private val createdEpochSeconds: Long,
) {
    private var id: String = "chatcmpl-anthropic"
    private var promptTokens: Int = 0
    private var completionTokens: Int = 0
    private var toolCallCount: Int = 0
    private val toolIndexByBlock = mutableMapOf<Int, Int>() // anthropic content-block index → openai tool_calls index

    fun onFrame(frame: SseFrame): List<SseFrame> {
        val data = frame.data?.trim() ?: return emptyList()
        val json = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        return when (json["type"]?.jsonPrimitive?.contentOrNull) {
            "message_start" -> onMessageStart(json)
            "content_block_start" -> onContentBlockStart(json)
            "content_block_delta" -> onContentBlockDelta(json)
            "message_delta" -> onMessageDelta(json)
            "message_stop" -> onMessageStop()
            "error" -> onError(json)
            else -> emptyList() // ping, content_block_stop, unknown → nothing
        }
    }

    private fun onMessageStart(json: JsonObject): List<SseFrame> {
        val message = json["message"] as? JsonObject
        message
            ?.get("id")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { id = it }
        promptTokens = AnthropicConverter.intOf(message?.get("usage") as? JsonObject, "input_tokens")
        // OpenAI opens with a role delta
        return listOf(chunk { putJsonObject("delta") { put("role", "assistant") } })
    }

    private fun onContentBlockStart(json: JsonObject): List<SseFrame> {
        val block = json["content_block"] as? JsonObject ?: return emptyList()
        if (block["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return emptyList()
        val anthropicIndex = json["index"]?.jsonPrimitive?.intOrNull ?: return emptyList()
        val toolIndex = toolCallCount++
        toolIndexByBlock[anthropicIndex] = toolIndex
        return listOf(
            chunk {
                putJsonObject("delta") {
                    putJsonArray("tool_calls") {
                        addJsonObject {
                            put("index", toolIndex)
                            put("id", block["id"]?.jsonPrimitive?.contentOrNull ?: "")
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", block["name"]?.jsonPrimitive?.contentOrNull ?: "")
                                put("arguments", "")
                            }
                        }
                    }
                }
            },
        )
    }

    private fun onContentBlockDelta(json: JsonObject): List<SseFrame> {
        val delta = json["delta"] as? JsonObject ?: return emptyList()
        return when (delta["type"]?.jsonPrimitive?.contentOrNull) {
            "text_delta" ->
                listOf(
                    chunk {
                        putJsonObject(
                            "delta",
                        ) { put("content", delta["text"]?.jsonPrimitive?.contentOrNull ?: "") }
                    },
                )
            "input_json_delta" -> {
                val toolIndex = toolIndexByBlock[json["index"]?.jsonPrimitive?.intOrNull] ?: 0
                listOf(
                    chunk {
                        putJsonObject("delta") {
                            putJsonArray("tool_calls") {
                                addJsonObject {
                                    put("index", toolIndex)
                                    putJsonObject("function") {
                                        put("arguments", delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: "")
                                    }
                                }
                            }
                        }
                    },
                )
            }
            else -> emptyList()
        }
    }

    private fun onMessageDelta(json: JsonObject): List<SseFrame> {
        val delta = json["delta"] as? JsonObject
        completionTokens = AnthropicConverter.intOf(json["usage"] as? JsonObject, "output_tokens")
        val finish =
            AnthropicConverter.mapStopReason(
                delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull,
                toolCallCount > 0,
            )
        return listOf(chunk(finishReason = finish) { putJsonObject("delta") {} })
    }

    private fun onMessageStop(): List<SseFrame> =
        listOf(
            // final usage chunk (empty choices) — pumpSse injects the §1.3 extension (dual names + cost)
            SseFrame.ofData(
                buildJsonObject {
                    put("id", id)
                    put("object", "chat.completion.chunk")
                    put("created", createdEpochSeconds)
                    put("model", model)
                    putJsonArray("choices") {}
                    putJsonObject("usage") {
                        put("prompt_tokens", promptTokens)
                        put("completion_tokens", completionTokens)
                        put("total_tokens", promptTokens + completionTokens)
                    }
                }.toString(),
            ),
            SseFrame.ofData("[DONE]"),
        )

    private fun onError(json: JsonObject): List<SseFrame> {
        // A mid-stream Anthropic error event → an OpenAI-shaped error frame (§1.7 body), then [DONE].
        val rendered = AnthropicErrorConverter.convert(500, json).render()
        return listOf(
            SseFrame.ofData(openAiErrorBody(rendered.type, rendered.code, rendered.message).toString()),
            SseFrame.ofData("[DONE]"),
        )
    }

    private fun chunk(
        finishReason: String? = null,
        delta: JsonObjectBuilder.() -> Unit,
    ): SseFrame =
        SseFrame.ofData(
            buildJsonObject {
                put("id", id)
                put("object", "chat.completion.chunk")
                put("created", createdEpochSeconds)
                put("model", model)
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", 0)
                        delta()
                        if (finishReason !=
                            null
                        ) {
                            put("finish_reason", finishReason)
                        } else {
                            put("finish_reason", kotlinx.serialization.json.JsonNull)
                        }
                    }
                }
            }.toString(),
        )
}
