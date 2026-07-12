// SPDX-License-Identifier: Apache-2.0
package shared.ktor.mcp

import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private fun jsonRpcErrorBody(cause: Throwable) =
    buildJsonObject {
        put("error", cause.message ?: "Internal server error")
        put("type", cause::class.simpleName ?: "Unknown")
    }

fun Application.installMcpKtorBase(
    config: McpKtorConfig,
    openTelemetry: OpenTelemetry,
) {
    val logger = LoggerFactory.getLogger("${config.serviceName}-mcp-server")

    install(ContentNegotiation) {
        json(McpJson)
    }

    install(StatusPages) {
        exception<Throwable> { call: io.ktor.server.application.ApplicationCall, cause: Throwable ->
            logger.error("Unhandled exception in non-MCP route", cause)
            call.respondText(
                contentType = ContentType.Application.Json,
                status = io.ktor.http.HttpStatusCode.InternalServerError,
                text = jsonRpcErrorBody(cause).toString(),
            )
        }
    }

    install(KtorServerTelemetry) {
        setOpenTelemetry(openTelemetry)
    }

    install(CORS) {
        val defaultSchemes = listOf("http", "https")
        config.corsAllowedHosts.forEach {
            val host = it.trimEnd('/')
            allowHost(host, schemes = defaultSchemes)
            allowHost("$host/", schemes = defaultSchemes)
        }
        config.corsConfig.allowedMethods.forEach { allowMethod(it) }
        config.corsConfig.allowedHeaders.forEach { allowHeader(it) }
        allowNonSimpleContentTypes = config.corsConfig.allowNonSimpleContentTypes
        allowCredentials = config.corsConfig.allowCredentials
        exposeHeader("mcp-session-id")
        exposeHeader("mcp-protocol-version")
    }

    config.callLoggingConfig?.let { callLoggingConfig ->
        install(CallLogging) {
            level = callLoggingConfig.level
            format { call ->
                callLoggingConfig.customFormat?.invoke(call.request)
                    ?: "-> ${call.request.httpMethod.value} ${call.request.path()}"
            }
        }
    }

    routing {
        if (config.healthEndpoint) {
            get("/health") {
                call.respondText("""{"status": "ok"}""", contentType = ContentType.Application.Json)
            }
        }
        get("/ready") {
            if (config.readinessProbe()) {
                call.respondText("""{"status": "ready"}""", contentType = ContentType.Application.Json)
            } else {
                call.respondText(
                    status = io.ktor.http.HttpStatusCode.ServiceUnavailable,
                    text = """{"status": "not-ready"}""",
                    contentType = ContentType.Application.Json,
                )
            }
        }
        if (config.shutdownEndpoint) {
            get(config.shutdownUrlPath) {
                call.respondText("Shutting down...")
            }
        }
    }

    logger.info("${config.serviceName} MCP server base configured on port ${config.serverPort}")
}
