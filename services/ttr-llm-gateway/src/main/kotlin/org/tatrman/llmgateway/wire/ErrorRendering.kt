// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.wire

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.ceil

/**
 * Transport-facing rendering of [GatewayError] → an OpenAI-shaped error body + status + headers
 * (contracts §1.7, A-3/D-6). Error bodies are ALWAYS SDK-parseable, even gateway-originated ones.
 */
data class RenderedGatewayError(
    val status: HttpStatusCode,
    val type: String,
    val code: String?,
    val message: String,
    val headers: Map<String, String> = emptyMap(),
)

fun GatewayError.render(): RenderedGatewayError =
    when (this) {
        is GatewayError.Validation ->
            RenderedGatewayError(HttpStatusCode.BadRequest, "invalid_request_error", null, detail)
        is GatewayError.ContextLength ->
            RenderedGatewayError(
                HttpStatusCode.BadRequest,
                "invalid_request_error",
                "context_length_exceeded",
                "context length exceeded",
            )
        is GatewayError.ContentFilter ->
            RenderedGatewayError(
                HttpStatusCode.BadRequest,
                "invalid_request_error",
                "content_filter",
                "content filtered",
            )
        is GatewayError.Auth ->
            RenderedGatewayError(
                HttpStatusCode.Unauthorized,
                "invalid_request_error",
                "invalid_api_key",
                "invalid or revoked API key",
            )
        is GatewayError.RateLimit ->
            RenderedGatewayError(
                HttpStatusCode.TooManyRequests,
                "rate_limit_error",
                "rate_limit_exceeded",
                "rate limit exceeded",
                headers =
                    retryAfterMs?.let { mapOf("Retry-After" to ceil(it / 1000.0).toLong().coerceAtLeast(1).toString()) }
                        ?: emptyMap(),
            )
        is GatewayError.BudgetExceeded ->
            RenderedGatewayError(
                HttpStatusCode.TooManyRequests,
                "insufficient_quota",
                "insufficient_quota",
                "budget exceeded",
                headers = mapOf("x-gateway-reason" to "budget_exceeded"),
            )
        is GatewayError.Provider5xx ->
            RenderedGatewayError(
                HttpStatusCode.BadGateway,
                "server_error",
                "upstream_error",
                "upstream provider error (status $status)",
            )
        is GatewayError.Timeout ->
            RenderedGatewayError(HttpStatusCode.BadGateway, "server_error", "upstream_error", "upstream timeout")
        is GatewayError.Network ->
            RenderedGatewayError(HttpStatusCode.BadGateway, "server_error", "upstream_error", "upstream network error")
        is GatewayError.Internal ->
            RenderedGatewayError(HttpStatusCode.InternalServerError, "server_error", "internal_error", "internal error")
    }

/** The OpenAI error envelope `{"error":{message,type,code,param}}`. */
fun openAiErrorBody(
    type: String,
    code: String?,
    message: String,
): JsonObject =
    buildJsonObject {
        putJsonObject("error") {
            put("message", message)
            put("type", type)
            if (code != null) put("code", code) else put("code", JsonNull)
            put("param", JsonNull)
        }
    }

suspend fun ApplicationCall.respondGatewayError(error: GatewayError) {
    val r = error.render()
    r.headers.forEach { (k, v) -> response.header(k, v) }
    respond(r.status, openAiErrorBody(r.type, r.code, r.message))
}

/**
 * SSE mid-stream error frame (contracts §1.4): `data: {"error":{…}}` then `[DONE]` + close — used
 * once the first token has been sent (before-first-token failures use an HTTP status instead).
 * TODO(LG-P2·S2): emit through the SSE writer when streaming lands. Typed now for the seam.
 */
fun GatewayError.toSseErrorFrame(): String {
    val r = render()
    return "data: " + openAiErrorBody(r.type, r.code, r.message).toString() + "\n\ndata: [DONE]\n\n"
}

/** Single StatusPages handler so EVERY route renders failures identically (contracts §1.7). */
fun StatusPagesConfig.installGatewayErrorHandling() {
    exception<Throwable> { call, cause ->
        val error =
            when (cause) {
                is GatewayException -> cause.error
                is SerializationException -> GatewayError.Validation("unparseable request body")
                is BadRequestException -> GatewayError.Validation("bad request")
                else -> GatewayError.Internal(cause)
            }
        call.respondGatewayError(error)
    }
}
