// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.tatrman.llmgateway.wire.ChatRequest

/** The Anthropic request body + the OpenAI-dialect params dropped on the way (C-4 strip-and-log). */
data class ConvertedRequest(
    val body: JsonObject,
    val stripped: List<String>,
)

/**
 * The **single full converter** (design B-1): OpenAI chat-completions wire ⇄ Anthropic Messages API. Pure,
 * IO-free. The request half maps the OpenAI dialect onto `/v1/messages` and returns the list of
 * unmappable/unknown params it stripped (so fallback replay is honest — C-4). The response half maps a
 * Messages response (and, statefully, the SSE event family) back to OpenAI `chat.completion(.chunk)` so
 * every downstream consumer — including the passthrough tap — is provider-agnostic (B-3).
 */
object AnthropicConverter {
    // OpenAI top-level fields the converter consumes; every other key is stripped-and-logged. `model_tags`
    // is gateway-internal (never a client param) so it is consumed silently, not reported as stripped.
    private val CONSUMED =
        setOf(
            "model",
            "max_tokens",
            "max_completion_tokens",
            "messages",
            "tools",
            "tool_choice",
            "temperature",
            "top_p",
            "stream",
            "stop",
            "model_tags",
        )

    fun toMessages(
        req: ChatRequest,
        model: String,
        defaultMaxTokens: Int,
    ): ConvertedRequest {
        val src = req.asJsonObject()
        val stripped = sortedSetOf<String>()
        val (messages, system) = convertMessages(src["messages"] as? JsonArray ?: JsonArray(emptyList()), stripped)

        val body =
            buildJsonObject {
                put("model", model)
                put(
                    "max_tokens",
                    src["max_tokens"]?.jsonPrimitive?.intOrNull
                        ?: src["max_completion_tokens"]?.jsonPrimitive?.intOrNull
                        ?: defaultMaxTokens,
                )
                if (system != null) put("system", system)
                put("messages", messages)
                (src["tools"] as? JsonArray)?.let { put("tools", convertTools(it, stripped)) }
                src["tool_choice"]?.let { convertToolChoice(it)?.let { tc -> put("tool_choice", tc) } }
                src["temperature"]?.let { put("temperature", it) }
                src["top_p"]?.let { put("top_p", it) }
                src["stream"]?.let { put("stream", it) }
                convertStop(src["stop"])?.let { put("stop_sequences", it) }
            }

        // strip-and-log everything the client sent that we did not map (typed-but-unmappable AND unknown)
        src.keys.forEach { if (it !in CONSUMED) stripped.add(it) }
        return ConvertedRequest(body, stripped.toList())
    }

    // ── messages ────────────────────────────────────────────────────────────────────────────────────
    private fun convertMessages(
        messages: JsonArray,
        stripped: MutableSet<String>,
    ): Pair<JsonArray, JsonElement?> {
        val system = StringBuilder()
        val out = mutableListOf<JsonObject>()
        var pendingToolResults = mutableListOf<JsonObject>()

        fun flushToolResults() {
            if (pendingToolResults.isNotEmpty()) {
                val results = pendingToolResults
                out +=
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") { results.forEach { add(it) } }
                    }
                pendingToolResults = mutableListOf()
            }
        }

        for (element in messages) {
            val m = element as? JsonObject ?: continue
            when (m["role"]?.jsonPrimitive?.contentOrNull) {
                "system" -> {
                    flushToolResults()
                    val text = textOf(m["content"])
                    if (text.isNotEmpty()) {
                        if (system.isNotEmpty()) system.append("\n\n")
                        system.append(text)
                    }
                }
                "tool" -> {
                    // OpenAI tool result → an Anthropic tool_result block, folded into ONE following user turn
                    pendingToolResults +=
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", m["tool_call_id"]?.jsonPrimitive?.contentOrNull ?: "")
                            put("content", textOf(m["content"]))
                        }
                }
                "user" -> {
                    flushToolResults()
                    out +=
                        buildJsonObject {
                            put("role", "user")
                            put("content", contentBlocks(m["content"], stripped))
                        }
                }
                "assistant" -> {
                    flushToolResults()
                    out += assistantMessage(m, stripped)
                }
                else -> stripped.add("messages[role=${m["role"]?.jsonPrimitive?.contentOrNull}]")
            }
        }
        flushToolResults()
        return JsonArray(out) to (if (system.isEmpty()) null else JsonPrimitive(system.toString()))
    }

    private fun assistantMessage(
        m: JsonObject,
        stripped: MutableSet<String>,
    ): JsonObject {
        val toolCalls = m["tool_calls"] as? JsonArray
        // Plain assistant text with no tool calls → keep content as a string (Anthropic accepts either form).
        if (toolCalls == null || toolCalls.isEmpty()) {
            return buildJsonObject {
                put("role", "assistant")
                put("content", contentBlocks(m["content"], stripped))
            }
        }
        return buildJsonObject {
            put("role", "assistant")
            putJsonArray("content") {
                textOf(m["content"]).takeIf { it.isNotEmpty() }?.let {
                    addJsonObject {
                        put("type", "text")
                        put("text", it)
                    }
                }
                toolCalls.forEach { tc ->
                    val fn = (tc as? JsonObject)?.get("function") as? JsonObject
                    addJsonObject {
                        put("type", "tool_use")
                        put("id", (tc as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull ?: "")
                        put("name", fn?.get("name")?.jsonPrimitive?.contentOrNull ?: "")
                        put("input", parseArguments(fn?.get("arguments")?.jsonPrimitive?.contentOrNull))
                    }
                }
            }
        }
    }

    /** OpenAI message content (string OR parts array) → an Anthropic content value (string OR blocks array). */
    private fun contentBlocks(
        content: JsonElement?,
        stripped: MutableSet<String>,
    ): JsonElement =
        when (content) {
            null, is JsonNull -> JsonPrimitive("")
            is JsonPrimitive -> content // plain string
            is JsonArray ->
                buildJsonArray {
                    content.forEach { part ->
                        val p = part as? JsonObject ?: return@forEach
                        when (p["type"]?.jsonPrimitive?.contentOrNull) {
                            "text" ->
                                addJsonObject {
                                    put("type", "text")
                                    put(
                                        "text",
                                        p["text"]?.jsonPrimitive?.contentOrNull ?: "",
                                    )
                                }
                            "image_url" ->
                                imageBlock(p["image_url"] as? JsonObject)?.let { add(it) }
                                    ?: stripped.add("content.image_url")
                            else -> stripped.add("content.${p["type"]?.jsonPrimitive?.contentOrNull}")
                        }
                    }
                }
            else -> JsonPrimitive("")
        }

    private fun imageBlock(imageUrl: JsonObject?): JsonObject? {
        val url = imageUrl?.get("url")?.jsonPrimitive?.contentOrNull ?: return null
        return if (url.startsWith("data:")) {
            // data:<media_type>;base64,<data>
            val meta = url.removePrefix("data:").substringBefore(",")
            val data = url.substringAfter(",", "")
            buildJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", meta.substringBefore(";"))
                    put("data", data)
                }
            }
        } else {
            buildJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "url")
                    put("url", url)
                }
            }
        }
    }

    // ── tools ───────────────────────────────────────────────────────────────────────────────────────
    private fun convertTools(
        tools: JsonArray,
        stripped: MutableSet<String>,
    ): JsonArray =
        buildJsonArray {
            tools.forEach { t ->
                val fn = (t as? JsonObject)?.get("function") as? JsonObject
                if (fn == null) {
                    stripped.add("tools[non-function]")
                    return@forEach
                }
                addJsonObject {
                    put("name", fn["name"]?.jsonPrimitive?.contentOrNull ?: "")
                    fn["description"]?.let { put("description", it) }
                    put("input_schema", fn["parameters"] as? JsonObject ?: buildJsonObject { put("type", "object") })
                }
            }
        }

    private fun convertToolChoice(choice: JsonElement): JsonElement? =
        when {
            choice is JsonPrimitive ->
                when (choice.contentOrNull) {
                    "auto" -> buildJsonObject { put("type", "auto") }
                    "required" -> buildJsonObject { put("type", "any") }
                    "none" -> buildJsonObject { put("type", "none") }
                    else -> null
                }
            choice is JsonObject -> {
                val name = (choice["function"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                if (name != null) {
                    buildJsonObject {
                        put("type", "tool")
                        put("name", name)
                    }
                } else {
                    null
                }
            }
            else -> null
        }

    private fun convertStop(stop: JsonElement?): JsonArray? =
        when (stop) {
            null, is JsonNull -> null
            is JsonPrimitive -> buildJsonArray { add(stop) }
            is JsonArray -> stop
            else -> null
        }

    private fun parseArguments(args: String?): JsonElement {
        if (args.isNullOrBlank()) return buildJsonObject { }
        return runCatching { Json.parseToJsonElement(args) }.getOrElse { buildJsonObject { } }
    }

    private fun textOf(content: JsonElement?): String =
        when (content) {
            null, is JsonNull -> ""
            is JsonPrimitive -> content.contentOrNull ?: ""
            is JsonArray ->
                content.joinToString("") { part ->
                    (part as? JsonObject)
                        ?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.contentOrNull ?: ""
                }
            else -> ""
        }

    // ── non-stream response ───────────────────────────────────────────────────────────────────────────

    /** An Anthropic Messages response → an OpenAI `chat.completion` object (usage in OpenAI names; §1.3 cost added later). */
    fun toChatCompletion(
        anthropic: JsonObject,
        model: String,
        createdEpochSeconds: Long,
    ): JsonObject {
        val text = StringBuilder()
        val toolCalls = mutableListOf<JsonObject>()
        (anthropic["content"] as? JsonArray)?.forEach { block ->
            val b = block as? JsonObject ?: return@forEach
            when (b["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> text.append(b["text"]?.jsonPrimitive?.contentOrNull ?: "")
                "tool_use" ->
                    toolCalls +=
                        buildJsonObject {
                            put("id", b["id"]?.jsonPrimitive?.contentOrNull ?: "")
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", b["name"]?.jsonPrimitive?.contentOrNull ?: "")
                                put("arguments", (b["input"] ?: buildJsonObject { }).toString())
                            }
                        }
            }
        }
        val finish = mapStopReason(anthropic["stop_reason"]?.jsonPrimitive?.contentOrNull, toolCalls.isNotEmpty())
        val usage = anthropic["usage"] as? JsonObject
        val inTok = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val outTok = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0

        return buildJsonObject {
            put("id", anthropic["id"]?.jsonPrimitive?.contentOrNull ?: "chatcmpl-anthropic")
            put("object", "chat.completion")
            put("created", createdEpochSeconds)
            put("model", model)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant")
                        if (text.isEmpty() &&
                            toolCalls.isNotEmpty()
                        ) {
                            put("content", JsonNull)
                        } else {
                            put("content", text.toString())
                        }
                        if (toolCalls.isNotEmpty()) putJsonArray("tool_calls") { toolCalls.forEach { add(it) } }
                    }
                    put("finish_reason", finish)
                }
            }
            putJsonObject("usage") {
                put("prompt_tokens", inTok)
                put("completion_tokens", outTok)
                put("total_tokens", inTok + outTok)
            }
        }
    }

    fun mapStopReason(
        stopReason: String?,
        hadToolUse: Boolean,
    ): String =
        when (stopReason) {
            "end_turn", "stop_sequence", "pause_turn" -> "stop"
            "max_tokens" -> "length"
            "tool_use" -> "tool_calls"
            "refusal" -> "content_filter"
            null -> if (hadToolUse) "tool_calls" else "stop"
            else -> "stop"
        }

    internal fun intOf(
        obj: JsonObject?,
        key: String,
    ): Int = obj?.get(key)?.jsonPrimitive?.intOrNull ?: 0
}
