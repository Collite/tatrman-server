package org.tatrman.llm.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Where the LLM gateway lives (host/port + request timeout). Lib-local so the
 *  client doesn't couple to any one agent's config type. */
data class LlmGatewayEndpoint(
    val host: String,
    val port: Int,
    val timeoutMs: Long,
)

/**
 * Client for the Prometheus LLM gateway's OpenAI-shaped `/v1/chat/completions`
 * (Prometheus's ChatController serves this as an alias of `/api/v1/chat/completions`).
 * Shared across the constellation (Themis nodes, Golem's PlanComposer). `model`
 * is a flat tier key (`"haiku"` CHEAP / `"sonnet"` FAST / `"opus"`), mapped to a
 * Prometheus tag downstream. Failures return a [Result.failure] — callers decide
 * fallback (never throws out of [complete]).
 */
class LlmGatewayClient(
    private val endpoint: LlmGatewayEndpoint,
) {
    private val logger = LoggerFactory.getLogger(LlmGatewayClient::class.java)

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val httpClient =
        HttpClient(ClientCIO) {
            install(ContentNegotiation) {
                json(this@LlmGatewayClient.json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = endpoint.timeoutMs
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = endpoint.timeoutMs
            }
        }

    suspend fun complete(
        prompt: String,
        systemPrompt: String = "",
        model: String = "sonnet",
        temperature: Double = 0.0,
        maxTokens: Int = 2000,
    ): Result<String> =
        try {
            val request =
                ChatCompletionRequest(
                    model = model,
                    messages =
                        buildList {
                            if (systemPrompt.isNotBlank()) {
                                add(ChatMessage(role = "system", content = systemPrompt))
                            }
                            add(ChatMessage(role = "user", content = prompt))
                        },
                    temperature = temperature,
                    maxTokens = maxTokens,
                )

            val response: ChatCompletionResponse =
                httpClient
                    .post("http://${endpoint.host}:${endpoint.port}/v1/chat/completions") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }.body()

            val content =
                response.choices
                    ?.firstOrNull()
                    ?.message
                    ?.content
                    ?: ""
            logger.debug("LLM response: {}", content.take(200))
            Result.success(content)
        } catch (e: Exception) {
            logger.error("Error calling LLM gateway: {}", e.message, e)
            Result.failure(LlmGatewayException("LLM gateway unavailable: ${e.message}"))
        }

    fun close() {
        httpClient.close()
    }
}

class LlmGatewayException(
    message: String,
) : Exception(message)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.0,
    @SerialName("max_tokens")
    val maxTokens: Int = 2000,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val `object`: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<Choice>? = null,
    val usage: Usage? = null,
)

@Serializable
data class Choice(
    val index: Int? = null,
    val message: ChatMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null,
)
