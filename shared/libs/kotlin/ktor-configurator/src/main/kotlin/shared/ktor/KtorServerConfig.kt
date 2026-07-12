// SPDX-License-Identifier: Apache-2.0
package shared.ktor

import io.ktor.http.*

data class KtorServerConfig(
    val serviceName: String,
    val serverPort: Int,
    val engine: KtorEngine = KtorEngine.CIO,
    val corsAllowedHosts: List<String> = defaultCorsHosts(),
    val corsConfig: CorsConfig = CorsConfig(),
    val jsonConfig: JsonConfig = JsonConfig(),
    val telemetryEnabled: Boolean = true,
    val callLoggingConfig: CallLoggingConfig? = null,
    val forwardedHeaderEnabled: Boolean = false,
)

fun defaultCorsHosts(): List<String> =
    System
        .getenv("KTOR_CORS_ALLOWED_HOSTS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: listOf("localhost:5173", "localhost:7010")

data class CorsConfig(
    val allowCredentials: Boolean = true,
    val allowNonSimpleContentTypes: Boolean = true,
    val allowedMethods: List<HttpMethod> = defaultAllowedMethods(),
    val allowedHeaders: List<String> = defaultAllowedHeaders(),
    val exposedHeaders: List<String> = defaultExposedHeaders(),
)

private fun defaultAllowedMethods(): List<HttpMethod> =
    listOf(
        HttpMethod.Options,
        HttpMethod.Get,
        HttpMethod.Post,
        HttpMethod.Put,
        HttpMethod.Delete,
        HttpMethod.Patch,
    )

private fun defaultAllowedHeaders(): List<String> =
    listOf(
        HttpHeaders.Authorization,
        HttpHeaders.ContentType,
        HttpHeaders.Accept,
        HttpHeaders.AccessControlAllowOrigin,
        "X-User-Id",
        "X-Request-Id",
    )

private fun defaultExposedHeaders(): List<String> = emptyList()

data class JsonConfig(
    val prettyPrint: Boolean = true,
    val isLenient: Boolean = true,
    val encodeDefaults: Boolean = true,
    val ignoreUnknownKeys: Boolean = true,
)

data class CallLoggingConfig(
    val level: org.slf4j.event.Level = org.slf4j.event.Level.INFO,
    val customFormat: ((io.ktor.server.request.ApplicationRequest) -> String)? = null,
)
