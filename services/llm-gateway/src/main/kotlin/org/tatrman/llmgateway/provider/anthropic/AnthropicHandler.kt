// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider.anthropic

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.tatrman.llmgateway.provider.Key
import org.tatrman.llmgateway.provider.ProviderHandler
import org.tatrman.llmgateway.provider.ProviderResult
import org.tatrman.llmgateway.provider.UpstreamStreamException
import org.tatrman.llmgateway.provider.UpstreamTarget
import org.tatrman.llmgateway.provider.UpstreamUsage
import org.tatrman.llmgateway.provider.UrlBuilder
import org.tatrman.llmgateway.stream.SseFrame
import org.tatrman.llmgateway.stream.SseFramer
import org.tatrman.llmgateway.wire.AnthropicErrorConverter
import org.tatrman.llmgateway.wire.ChatRequest
import org.tatrman.llmgateway.wire.GatewayError
import org.tatrman.llmgateway.wire.parseRetryAfterMs

/**
 * The Anthropic `ProviderHandler` (design B-1, LG-P2·S3): wraps [AnthropicConverter] around the Messages
 * API (`POST /v1/messages`, `x-api-key` + `anthropic-version`). Non-stream and stream both convert wire on
 * both edges so the caller only ever sees OpenAI shapes; streaming reuses the S2 [SseFramer] (Anthropic is
 * SSE too) and re-frames via [AnthropicStreamConverter]. Stripped OpenAI-dialect params are logged (C-4).
 */
class AnthropicHandler(
    private val client: HttpClient,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ProviderHandler {
    override suspend fun complete(
        req: ChatRequest,
        target: UpstreamTarget,
        key: Key,
    ): ProviderResult {
        val converted =
            AnthropicConverter.toMessages(
                req,
                target.upstream,
                target.defaultMaxTokens ?: DEFAULT_MAX_TOKENS,
            )
        logStripped(converted.stripped)
        val response =
            client.post(target.baseUrl + UrlBuilder.path(target, "messages")) {
                anthropicHeaders(target, key)
                contentType(ContentType.Application.Json)
                setBody(converted.body.toString())
            }
        val status = response.status.value
        val json =
            runCatching {
                Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }.getOrDefault(JsonObject(emptyMap()))
        val retryAfter = parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis())
        if (status !in 200..299) {
            // Non-2xx: return the RAW Anthropic error body — the route renders it via AnthropicErrorConverter.
            return ProviderResult(status, json, null, null, retryAfterMs = retryAfter, stripped = converted.stripped)
        }
        val openai = AnthropicConverter.toChatCompletion(json, target.upstream, nowEpochSeconds())
        val usage = json["usage"] as? JsonObject
        val upstreamUsage =
            if (usage != null) {
                UpstreamUsage(
                    (usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0).toLong(),
                    (usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0).toLong(),
                )
            } else {
                null
            }
        val finish =
            (openai["choices"] as? JsonArray)
                ?.firstOrNull()
                ?.jsonObject
                ?.get("finish_reason")
                ?.jsonPrimitive
                ?.contentOrNull
        return ProviderResult(
            status,
            openai,
            upstreamUsage,
            finish,
            retryAfterMs = retryAfter,
            stripped = converted.stripped,
        )
    }

    override fun stream(
        req: ChatRequest,
        target: UpstreamTarget,
        key: Key,
    ): Flow<SseFrame> =
        channelFlow {
            val converted =
                AnthropicConverter.toMessages(
                    req,
                    target.upstream,
                    target.defaultMaxTokens ?: DEFAULT_MAX_TOKENS,
                )
            logStripped(converted.stripped)
            val streamConverter = AnthropicStreamConverter(target.upstream, nowEpochSeconds())
            client
                .preparePost(target.baseUrl + UrlBuilder.path(target, "messages")) {
                    anthropicHeaders(target, key)
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Text.EventStream)
                    timeout { requestTimeoutMillis = Long.MAX_VALUE }
                    setBody(converted.body.toString())
                }.execute { response ->
                    val status = response.status.value
                    if (status !in 200..299) {
                        // Before-first-token error → typed throw; the LG-P3·S2 engine retries/falls back and
                        // (if exhausted) commits a real HTTP status before the SSE writer attaches.
                        val errBody =
                            runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        val retryAfter = parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis())
                        val error = AnthropicErrorConverter.convert(status, errBody)
                        throw UpstreamStreamException(
                            if (error is GatewayError.RateLimit &&
                                retryAfter != null
                            ) {
                                GatewayError.RateLimit(retryAfter)
                            } else {
                                error
                            },
                        )
                    }
                    SseFramer.frames(response.bodyAsChannel()).collect { anthropicFrame ->
                        streamConverter.onFrame(anthropicFrame).forEach { send(it) }
                    }
                }
        }

    override suspend fun embed(
        rawBody: JsonObject,
        target: UpstreamTarget,
        key: Key,
    ): ProviderResult = throw IllegalStateException("Anthropic provider does not support embeddings")

    private fun HttpRequestBuilder.anthropicHeaders(
        target: UpstreamTarget,
        key: Key,
    ) {
        header(target.authHeader, key.value) // x-api-key, no scheme (C-5, per call)
        target.providerVersion?.let { header("anthropic-version", it) }
    }

    private fun logStripped(stripped: List<String>) {
        if (stripped.isNotEmpty()) log.info("anthropic converter stripped OpenAI-dialect params: {}", stripped)
    }

    private companion object {
        val log = LoggerFactory.getLogger(AnthropicHandler::class.java)
        const val DEFAULT_MAX_TOKENS = 4096
    }
}
