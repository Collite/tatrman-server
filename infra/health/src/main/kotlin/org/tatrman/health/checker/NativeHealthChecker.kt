package org.tatrman.health.checker

import org.tatrman.health.service.HealthCheckResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class NativeHealthChecker(
    override val technology: String,
    private val url: String,
    private val healthEndpoint: String,
    private val timeout: Long = 5000,
    private val expectedStatus: ExpectedStatus = ExpectedStatus.AnySuccess,
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

    enum class ExpectedStatus {
        AnySuccess,
        StatusField,
        TextResponse,
    }

    override suspend fun check(): HealthCheckResult {
        val fullUrl = "$url$healthEndpoint"
        return try {
            val response: HttpResponse = client.get(fullUrl)
            val isHealthy =
                when (expectedStatus) {
                    ExpectedStatus.AnySuccess -> response.status.value in 200..299
                    ExpectedStatus.StatusField -> parseStatusField(response.bodyAsText())
                    ExpectedStatus.TextResponse -> response.status == HttpStatusCode.OK
                }
            if (isHealthy) {
                HealthCheckResult.healthy(
                    technology = technology,
                    source = "native",
                    details = mapOf("url" to fullUrl, "statusCode" to response.status.value.toString()),
                )
            } else {
                HealthCheckResult.unhealthy(
                    technology = technology,
                    source = "native",
                    error = "Unhealthy status code: ${response.status.value}",
                    details = mapOf("url" to fullUrl, "statusCode" to response.status.value.toString()),
                )
            }
        } catch (e: Exception) {
            HealthCheckResult.unhealthy(
                technology = technology,
                source = "native",
                error = e.message ?: "Unknown error",
                details = mapOf("url" to fullUrl),
            )
        }
    }

    private fun parseStatusField(text: String): Boolean =
        try {
            val json = Json.parseToJsonElement(text)
            if (json is JsonObject) {
                val statusValue = json["status"]
                when (statusValue) {
                    is kotlinx.serialization.json.JsonPrimitive ->
                        statusValue.jsonPrimitive.content.lowercase() == "ok" ||
                            statusValue.jsonPrimitive.booleanOrNull == true ||
                            statusValue.jsonPrimitive.intOrNull == 1 ||
                            statusValue.jsonPrimitive.longOrNull == 1L ||
                            statusValue.jsonPrimitive.doubleOrNull == 1.0
                    else -> false
                }
            } else if (json is kotlinx.serialization.json.JsonPrimitive) {
                json.content.lowercase() == "ok" || json.booleanOrNull == true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
}
