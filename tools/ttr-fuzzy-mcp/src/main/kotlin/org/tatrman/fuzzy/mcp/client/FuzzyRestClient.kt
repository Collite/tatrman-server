package org.tatrman.fuzzy.mcp.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fuzzy.common.FuzzyMatchResponse
import org.slf4j.LoggerFactory
import org.tatrman.fuzzy.mcp.telemetry.FuzzyMcpTelemetry

class FuzzyRestClient(
    baseUrl: String,
    private val telemetry: FuzzyMcpTelemetry? = null,
) : FuzzyClient {
    private val logger = LoggerFactory.getLogger(FuzzyRestClient::class.java)

    @Serializable
    private data class MatchRequestDto(
        val query: String,
        val category: String? = null,
        val algorithm: String = "TATRMAN",
        val limit: Int = 10,
    )

    private val client =
        HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
            install(ContentNegotiation) {
                json(McpJson)
            }
        }

    private val url = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl

    override suspend fun match(
        category: String,
        name: String,
        algorithm: String,
        limit: Int,
    ): FuzzyMatchResponse =
        try {
            logger.info(
                "Calling matcher via REST: name='{}', category='{}', algorithm='{}', limit={}",
                name,
                category,
                algorithm,
                limit,
            )

            val response: FuzzyMatchResponse =
                client
                    .post("$url/match") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            MatchRequestDto(
                                query = name,
                                category = category.ifBlank { null },
                                algorithm = algorithm,
                                limit = limit,
                            ),
                        )
                    }.body()

            logger.info(
                "matcher REST call completed: name='{}', category='{}', results_size={}",
                name,
                category,
                response.matches.size,
            )

            response
        } catch (e: Exception) {
            logger.error("Error calling matcher via REST: name='{}', category='{}'", name, category, e)
            FuzzyMatchResponse(isError = true, error = "Error calling REST API: ${e.message}")
        }

    override fun getTelemetry(): FuzzyMcpTelemetry? = telemetry

    override fun close() {
        client.close()
    }
}
