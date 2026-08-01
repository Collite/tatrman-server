// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.cache

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Builds the client-facing responses for a cache hit — the non-stream body and the synthetic stream. */
object CacheReplay {
    /** Non-stream hit: the stored enriched body with the top-level `cached` flag flipped to true. */
    fun nonStreamBody(env: CacheEnvelope): JsonObject =
        JsonObject(env.body.toMutableMap().apply { put("cached", JsonPrimitive(true)) })

    /**
     * Synthetic stream replay for a `stream:true` hit (contracts §1.4): one content chunk (assembled text +
     * any tool_calls), one usage chunk (`cached:true` + the saved cost echo), then `[DONE]`. SDK-legal — an
     * OpenAI client reassembles the same message it would from the original live stream (proven by the
     * conformance replay test).
     */
    fun streamEvents(env: CacheEnvelope): String {
        val choice = (env.body["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
        val message = choice?.get("message") as? JsonObject
        val content = message?.get("content") ?: JsonNull
        val toolCalls = message?.get("tool_calls")
        val finish = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull ?: "stop"
        val id = env.body["id"]?.jsonPrimitive?.contentOrNull ?: "cached"
        val created = env.body["created"]?.jsonPrimitive?.longOrNull ?: (env.storedAtMs / 1000)

        val contentChunk =
            buildJsonObject {
                put("id", id)
                put("object", "chat.completion.chunk")
                put("created", created)
                put("model", env.servedModel)
                putJsonArray("choices") {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            putJsonObject("delta") {
                                put("role", "assistant")
                                put("content", content)
                                if (toolCalls != null) put("tool_calls", toolCalls)
                            }
                            put("finish_reason", finish)
                        },
                    )
                }
            }
        val usageChunk =
            buildJsonObject {
                put("id", id)
                put("object", "chat.completion.chunk")
                put("created", created)
                put("model", env.servedModel)
                putJsonArray("choices") {}
                putJsonObject("usage") {
                    put("prompt_tokens", env.promptTokens)
                    put("completion_tokens", env.completionTokens)
                    put("total_tokens", env.promptTokens + env.completionTokens)
                    put("input_tokens", env.promptTokens) // dual names (A-3)
                    put("output_tokens", env.completionTokens)
                    put("cost", env.costUsd) // the SAVED cost, echoed so Pythia still books it (GI-3)
                    put("estimated", false)
                }
                put("cached", true)
            }
        return "data: $contentChunk\n\ndata: $usageChunk\n\ndata: [DONE]\n\n"
    }
}
