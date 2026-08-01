// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Convenience builder for the “envelope-only” error result shape used by
 * the MCP tools when input validation or identity-check fails before any
 * upstream work runs. Keeps the format consistent across both tools.
 */
internal fun buildErrorResult(
    toolName: String,
    code: String,
    message: String,
    extra: JsonObject = JsonObject(emptyMap()),
): CallToolResult {
    val structured =
        buildJsonObject {
            put("ok", JsonPrimitive(false))
            put("tool", JsonPrimitive(toolName))
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("severity", JsonPrimitive("error"))
                            put("code", JsonPrimitive(code))
                            put("text", JsonPrimitive(message))
                        },
                    )
                },
            )
            // Always-present empty pipeline-warnings array so agents can write
            // a uniform parser. Real warnings populate this in Section I.
            put("pipelineWarnings", JsonArray(emptyList()))
            for ((k, v) in extra) put(k, v)
        }
    return CallToolResult(
        content = listOf(TextContent(text = "$code: $message")),
        isError = true,
        structuredContent = structured,
    )
}

/** Convenience: assemble the structured envelope's `messages` array. */
internal fun messagesArray(entries: List<MessageEntry>): JsonArray =
    buildJsonArray {
        for (e in entries) {
            add(
                buildJsonObject {
                    put("severity", JsonPrimitive(e.severity))
                    put("code", JsonPrimitive(e.code))
                    put("text", JsonPrimitive(e.text))
                },
            )
        }
    }

internal data class MessageEntry(
    val severity: String,
    val code: String,
    val text: String,
)

/** Bridge: read a single string field from a JsonObject without throwing. */
internal fun JsonObject.stringFieldOrNull(name: String): String? {
    val v = this[name] ?: return null
    if (v !is JsonPrimitive || !v.isString) return null
    return v.content
}

/** Bridge: read a JsonObject field. */
internal fun JsonObject.objectFieldOrEmpty(name: String): JsonObject =
    (this[name] as? JsonObject) ?: JsonObject(emptyMap())

/** Bridge: read a list-of-strings field. */
internal fun JsonObject.stringArrayFieldOrEmpty(name: String): List<String> {
    val v = this[name]
    if (v !is JsonArray) return emptyList()
    return v.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
}

/** Bridge: read an int field with a default. */
internal fun JsonObject.intFieldOr(
    name: String,
    default: Int,
): Int {
    val v = this[name]
    if (v !is JsonPrimitive) return default
    return v.content.toIntOrNull() ?: default
}

/** Bridge: read a boolean field with a default. */
internal fun JsonObject.boolFieldOr(
    name: String,
    default: Boolean,
): Boolean {
    val v = this[name]
    if (v !is JsonPrimitive) return default
    return v.content.toBooleanStrictOrNull() ?: default
}

/** Encode an arbitrary JsonElement as a single-line string. */
internal fun JsonElement.compact(): String = toString()
