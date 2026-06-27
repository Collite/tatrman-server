package org.tatrman.kantheon.kadmos.mcp.client

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class KadmosToken(
    val text: String,
    val charStart: Int,
    val charEnd: Int,
    val lemma: String,
    val upos: String,
    val xpos: String,
    val feats: Map<String, String>,
    val depHead: Int,
    val depRelation: String,
)

@Serializable
data class KadmosSpan(
    val charStart: Int,
    val charEnd: Int,
)

@Serializable
data class KadmosEntity(
    val text: String,
    val label: String,
    val charStart: Int,
    val charEnd: Int,
    val normalizedValue: String,
    val sourceEngine: String,
)

@Serializable
data class KadmosMessage(
    val severity: String,
    val code: String,
    val message: String,
)

@Serializable
data class KadmosAnalyzeResult(
    val language: String,
    val languageConfidence: Double,
    val engineUsed: String,
    val tokens: List<KadmosToken>,
    val sentences: List<KadmosSpan>,
    val paragraphs: List<KadmosSpan>,
    val entities: List<KadmosEntity>,
    val traceId: String,
    val elapsedMs: Long,
    val messages: List<KadmosMessage>,
)

/**
 * HTTP client for the Kadmos NLP service. Caller owns [httpClient] and its
 * lifecycle. Timeouts and connection config belong on the injected client.
 * W3C trace context is injected into every request so traces stitch across the
 * boundary. Forked from ai-platform `tools/nlp-mcp` `NlpClient` (Stage 2.3) —
 * the wire is unchanged (`POST /v1/analyze`); only the persona name moves.
 */
class KadmosClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyze(
        text: String,
        language: String = "",
        ops: Set<String>,
        mode: String = "NORMAL",
        engineHints: Map<String, String> = emptyMap(),
        authHeaders: Map<String, String> = emptyMap(),
    ): KadmosAnalyzeResult {
        val requestBody =
            buildJsonObject {
                put("text", JsonPrimitive(text))
                put("language", JsonPrimitive(language))
                put("ops", JsonArray(ops.map { JsonPrimitive(it) }))
                put("mode", JsonPrimitive(mode))
                if (engineHints.isNotEmpty()) {
                    putJsonObject("engineHints") {
                        engineHints.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    }
                }
            }

        val traceCarrier = mutableMapOf<String, String>()
        W3CTraceContextPropagator.getInstance().inject(
            Context.current(),
            traceCarrier,
            TextMapSetter { c, k, v -> c?.set(k, v) },
        )

        val analyzeUrl = "$baseUrl/v1/analyze"
        val response =
            try {
                httpClient.post(analyzeUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody.toString())
                    traceCarrier.forEach { (k, v) -> header(k, v) }
                    authHeaders.forEach { (k, v) -> header(k, v) }
                }
            } catch (e: Exception) {
                throw KadmosClientException(
                    "Kadmos service call failed at $analyzeUrl: ${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
            }

        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw KadmosClientException(
                "Kadmos service error at $analyzeUrl: ${response.status.value} - $responseText",
            )
        }
        return json.decodeFromString<KadmosAnalyzeResult>(responseText)
    }
}

class KadmosClientException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
