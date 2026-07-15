// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Anthropic Messages-API error converter (LG-P2·S3). Anthropic wraps errors as
 * `{"type":"error","error":{"type":"<kind>","message":"…"}}`. Same FI-6 invariant as the OpenAI-wire
 * converter: the ORIGINAL upstream status is carried through — notably **529 `overloaded_error` →
 * `Provider5xx(529)` (retryable, design C-3)**, never collapsed to a generic 5xx or a client error.
 */
object AnthropicErrorConverter : ErrorConverter {
    override fun convert(
        status: Int,
        body: JsonObject?,
    ): GatewayError {
        val err = body?.get("error") as? JsonObject
        val type = err?.get("type")?.jsonPrimitive?.contentOrNull ?: ""
        val message = err?.get("message")?.jsonPrimitive?.contentOrNull ?: "upstream error (status $status)"

        return when {
            status == 401 || status == 403 -> GatewayError.Auth(status)
            status == 408 -> GatewayError.Timeout()
            status == 429 -> GatewayError.RateLimit(retryAfterMs = null) // Retry-After read at the HTTP layer
            // 529 overloaded_error is Anthropic's non-standard overload signal — retryable + chain-eligible (C-3).
            status == 529 || type == "overloaded_error" -> GatewayError.Provider5xx(529)
            status in 500..599 -> GatewayError.Provider5xx(status) // ORIGINAL status carried
            status == 400 && isContextLength(message) -> GatewayError.ContextLength()
            status == 400 -> GatewayError.Validation(message)
            else -> GatewayError.Validation(message)
        }
    }

    // Anthropic surfaces context-window overflow as invalid_request_error with a "prompt is too long" message.
    private fun isContextLength(message: String): Boolean {
        val m = message.lowercase()
        return "prompt is too long" in m || "context" in m && "long" in m || "max_tokens" in m && "context" in m
    }
}
