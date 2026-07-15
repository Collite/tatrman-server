// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps an upstream failure (HTTP status + parsed error body/frame) into a [GatewayError] — one
 * implementation per provider family. This is the seam the retry/fallback engine (LG-P3) and the SSE
 * error-frame path (LG-P2·S2) dispatch on. **Invariant (FI-6 regression): the ORIGINAL upstream status
 * is carried through** — the ai-gateway experiment lost it, collapsing every 5xx to 500 and breaking
 * retry decisions.
 */
fun interface ErrorConverter {
    fun convert(
        status: Int,
        body: JsonObject?,
    ): GatewayError
}

/**
 * OpenAI-wire error converter (Azure/OpenAI/Gemini-compat). Reads the `{"error":{type,code,message}}`
 * envelope to disambiguate the 400 family (context length / content filter / plain validation).
 */
object OpenAiWireErrorConverter : ErrorConverter {
    override fun convert(
        status: Int,
        body: JsonObject?,
    ): GatewayError {
        val err = body?.get("error") as? JsonObject
        val code = err?.get("code")?.jsonPrimitive?.contentOrNull ?: ""
        val type = err?.get("type")?.jsonPrimitive?.contentOrNull ?: ""
        val message = err?.get("message")?.jsonPrimitive?.contentOrNull ?: "upstream error (status $status)"
        val tag = (code + " " + type).lowercase()

        return when {
            status == 401 || status == 403 -> GatewayError.Auth(status) // carries the original status
            status == 408 -> GatewayError.Timeout()
            status == 429 -> GatewayError.RateLimit(retryAfterMs = null) // Retry-After is read at the HTTP layer
            status in 500..599 -> GatewayError.Provider5xx(status) // ORIGINAL status (incl. 529)
            status == 400 && "context_length" in tag -> GatewayError.ContextLength()
            status == 400 && "content_filter" in tag -> GatewayError.ContentFilter()
            status == 400 -> GatewayError.Validation(message)
            else -> GatewayError.Validation(message) // other 4xx → non-retryable validation
        }
    }
}

/**
 * Parse the `Retry-After` header — supports the delta-seconds form (`"2"`) and the HTTP-date form
 * (`"Wed, 21 Oct 2026 07:28:00 GMT"`, resolved against [nowEpochMs]). Returns ms, or null if unset/unparseable.
 */
fun parseRetryAfterMs(
    headerValue: String?,
    nowEpochMs: Long,
): Long? {
    if (headerValue.isNullOrBlank()) return null
    headerValue.trim().toLongOrNull()?.let { return it * 1000 }
    return try {
        val whenMs =
            java.time.ZonedDateTime
                .parse(headerValue.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        (whenMs - nowEpochMs).coerceAtLeast(0)
    } catch (e: Exception) {
        null
    }
}
