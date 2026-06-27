package org.tatrman.health.checker

import org.tatrman.health.service.HealthCheckResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class PrometheusHealthChecker(
    override val technology: String,
    private val prometheusUrl: String,
    private val job: String,
    private val metricName: String = "up",
    private val timeout: Long = 5000,
) : HealthChecker {
    private var client: HttpClient =
        HttpClient(Apache) {
            engine {
                connectTimeout = timeout.toInt()
                socketTimeout = timeout.toInt()
            }
        }

    fun replaceClient(newClient: HttpClient) {
        client.close()
        client = newClient
    }

    override suspend fun check(): HealthCheckResult {
        val query =
            if (job.isNotBlank()) {
                "$metricName{job=\"$job\"}"
            } else {
                "$metricName"
            }
        // Full percent-encoding of the PromQL query: it contains `{`, `}`, `"`, `=` and
        // (for ranged queries) spaces, all of which are illegal in a raw URL query string
        // and make the Apache client throw `URISyntaxException` before the request is sent.
        val url = "$prometheusUrl/api/v1/query?query=${URLEncoder.encode(query, StandardCharsets.UTF_8)}"
        return try {
            val response = client.get(url)
            val text = response.bodyAsText()
            val result = parsePrometheusResponse(text)
            if (result != null && result == 1.0) {
                HealthCheckResult.healthy(
                    technology = technology,
                    source = "prometheus",
                    details =
                        mapOf(
                            "url" to url,
                            "job" to job,
                            "metricValue" to result.toString(),
                        ),
                )
            } else {
                HealthCheckResult.unhealthy(
                    technology = technology,
                    source = "prometheus",
                    error = "Metric not found or value is not 1",
                    details = mapOf("url" to url, "job" to job),
                )
            }
        } catch (e: Exception) {
            HealthCheckResult.unhealthy(
                technology = technology,
                source = "prometheus",
                error = e.message ?: "Unknown error",
                details = mapOf("url" to url),
            )
        }
    }

    private fun parsePrometheusResponse(text: String): Double? {
        val json = Json.parseToJsonElement(text)
        if (json !is JsonObject) return null
        val status = json["status"]?.jsonPrimitive?.content
        if (status != "success") return null

        val data = json["data"]
        if (data !is JsonObject) return null
        val result = data["result"]
        if (result !is JsonArray) return null
        if (result.isEmpty()) return null

        val firstResult = result[0]
        if (firstResult !is JsonObject) return null
        val value = firstResult["value"]
        if (value !is JsonArray) return null
        if (value.size < 2) return null

        val valueStr = value[1].jsonPrimitive.content
        return valueStr.toDoubleOrNull()
    }
}
