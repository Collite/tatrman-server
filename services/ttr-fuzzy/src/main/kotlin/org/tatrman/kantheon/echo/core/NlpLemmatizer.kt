package org.tatrman.kantheon.echo.core

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Lemmatises tokens via `infra/nlp` (`POST /v1/analyze` with `ops = ["LEMMATIZE"]`).
 *
 * Degradable: any connection failure or non-2xx response is logged and the call
 * returns the identity (folded-surface) map, so matching falls back to Phase 02
 * Stage A behaviour rather than failing. The caller owns [httpClient]'s lifecycle.
 */
class NlpLemmatizer(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val language: String = "cs",
) : Lemmatizer {
    private val logger = LoggerFactory.getLogger(NlpLemmatizer::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lemmatize(tokens: Collection<String>): Map<String, String> {
        val unique = tokens.toCollection(LinkedHashSet())
        if (unique.isEmpty()) return emptyMap()

        val fallback = unique.associateWith { TextNormalizer.fold(it) }

        return try {
            val requestBody =
                buildJsonObject {
                    put("text", JsonPrimitive(unique.joinToString(" ")))
                    put("language", JsonPrimitive(language))
                    put("ops", JsonArray(listOf(JsonPrimitive("LEMMATIZE"))))
                    put("mode", JsonPrimitive("NORMAL"))
                }
            val response =
                httpClient.post("$baseUrl/v1/analyze") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody.toString())
                }
            if (!response.status.isSuccess()) {
                logger.warn("NLP lemmatize returned {} — falling back to folded surface forms", response.status.value)
                return fallback
            }
            parseLemmas(response.bodyAsText(), unique, fallback)
        } catch (e: Exception) {
            logger.warn("NLP lemmatize call failed ({}) — falling back to folded surface forms", e.message)
            fallback
        }
    }

    private fun parseLemmas(
        body: String,
        requested: Set<String>,
        fallback: Map<String, String>,
    ): Map<String, String> {
        // Build folded-surface -> folded-lemma from the response, then key the result by the
        // original requested tokens (NLP re-tokenises, so match on the folded surface text).
        val byFoldedSurface = mutableMapOf<String, String>()
        try {
            json
                .parseToJsonElement(body)
                .jsonObject["tokens"]
                ?.jsonArray
                ?.forEach { tok ->
                    val obj = tok.jsonObject
                    val surface = obj["text"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val lemma = obj["lemma"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    byFoldedSurface[TextNormalizer.fold(surface)] = TextNormalizer.fold(lemma)
                }
        } catch (e: Exception) {
            logger.warn("Could not parse NLP lemmatize response ({}) — using folded surface forms", e.message)
        }

        if (byFoldedSurface.isEmpty()) return fallback
        return requested.associateWith { token ->
            byFoldedSurface[TextNormalizer.fold(token)] ?: TextNormalizer.fold(token)
        }
    }
}
