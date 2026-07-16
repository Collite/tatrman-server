// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Thin client over `infra/llm-gateway`'s `POST /api/v1/chat/responses` endpoint. chrono uses this
 * as the below-threshold fallback (A8.6): the prompt returns the SAME GroundingResult JSON schema,
 * which is re-validated + re-checked through the sql_preview round-trip before being returned with
 * `source = LLM`. Surface is one `chat(...)` method so tests can drop in a fake.
 */
interface LlmGatewayClient : AutoCloseable {
    /**
     * Send a system + user message to a configured model via the gateway. Returns the model's raw
     * text content (the gateway flattens provider response shapes into `content` / `output[0].text`
     * / `choices[0].message.content`; we accept any of them). Throws [LlmGatewayException] for
     * transport / non-2xx / parse failures so the caller can decide its failure posture.
     */
    suspend fun chat(
        model: String,
        system: String,
        user: String,
        responseFormat: GatewayResponseFormat? = null,
    ): String
}

@Serializable
data class GatewayResponseFormat(
    val type: String,
)

class LlmGatewayException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class KtorLlmGatewayClient(
    private val baseUrl: String,
    timeoutMs: Long = 10_000L,
    private val apiKey: String? = null,
) : LlmGatewayClient {
    private val logger = LoggerFactory.getLogger(KtorLlmGatewayClient::class.java)
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private val httpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
            expectSuccess = false
            HttpResponseValidator {
                validateResponse { response ->
                    if (!response.status.isSuccess()) {
                        throw LlmGatewayException(
                            "Gateway returned ${response.status.value} on ${response.call.request.url.encodedPath}",
                        )
                    }
                }
            }
        }

    override suspend fun chat(
        model: String,
        system: String,
        user: String,
        responseFormat: GatewayResponseFormat?,
    ): String {
        val req =
            ChatRequest(
                model = model,
                messages = listOf(Message("system", system), Message("user", user)),
                responseFormat = responseFormat,
            )
        val parsed: ChatResponse =
            try {
                val resp =
                    httpClient.post("$baseUrl/api/v1/chat/responses") {
                        contentType(ContentType.Application.Json)
                        if (!apiKey.isNullOrBlank()) {
                            headers { append("Authorization", "Bearer $apiKey") }
                        }
                        setBody(req)
                    }
                resp.body()
            } catch (e: LlmGatewayException) {
                throw e
            } catch (e: Exception) {
                logger.debug("Gateway request failed: {}", e.message)
                throw LlmGatewayException("Gateway request failed: ${e.message}", e)
            }
        return parsed.firstContent()
            ?: throw LlmGatewayException("Gateway returned a 2xx response with no content")
    }

    override fun close() {
        httpClient.close()
    }

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        @SerialName("response_format") val responseFormat: GatewayResponseFormat? = null,
    )

    @Serializable
    private data class Message(
        val role: String,
        val content: String,
    )

    @Serializable
    private data class ChatResponse(
        val content: String? = null,
        val output: List<Output>? = null,
        val choices: List<Choice>? = null,
    ) {
        fun firstContent(): String? {
            if (!content.isNullOrEmpty()) return content
            output?.firstOrNull { !it.text.isNullOrEmpty() }?.let { return it.text }
            choices
                ?.firstOrNull()
                ?.message
                ?.content
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            return null
        }
    }

    @Serializable
    private data class Output(
        val type: String = "",
        val text: String? = null,
    )

    @Serializable
    private data class Choice(
        val message: ChoiceMessage,
    )

    @Serializable
    private data class ChoiceMessage(
        val content: String = "",
    )
}
