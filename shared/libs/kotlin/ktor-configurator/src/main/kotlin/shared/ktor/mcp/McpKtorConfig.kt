package shared.ktor.mcp

import io.ktor.http.*
import shared.ktor.CorsConfig
import shared.ktor.JsonConfig
import shared.ktor.KtorEngine
import shared.ktor.defaultCorsHosts
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

data class McpKtorConfig(
    val serviceName: String,
    val serverPort: Int,
    val engine: KtorEngine = KtorEngine.CIO,
    val corsAllowedHosts: List<String> = defaultCorsHosts(),
    val corsConfig: CorsConfig = createMcpCorsConfig(),
    val jsonConfig: JsonConfig = JsonConfig(),
    val telemetryEnabled: Boolean = true,
    val callLoggingConfig: CallLoggingConfig? = null,
    val sseEnabled: Boolean = true,
    val healthEndpoint: Boolean = true,
    val shutdownEndpoint: Boolean = true,
    val shutdownUrlPath: String = "/shutdown",
    val connectionIdleTimeoutSeconds: Long = 120,
    val requestTimeoutSeconds: Long = 90,
    val defaultToolTimeoutMs: Long = 60_000,
    val toolTimeouts: Map<String, Long> = emptyMap(),
    val readinessProbe: () -> Boolean = { true },
) {
    fun timeoutFor(toolName: String): Long = toolTimeouts[toolName] ?: defaultToolTimeoutMs

    data class CallLoggingConfig(
        val level: org.slf4j.event.Level = org.slf4j.event.Level.INFO,
        val customFormat: ((io.ktor.server.request.ApplicationRequest) -> String)? = null,
    )
}

private fun createMcpCorsConfig(): CorsConfig =
    CorsConfig(
        allowCredentials = true,
        allowNonSimpleContentTypes = true,
        allowedMethods =
            listOf(
                HttpMethod.Options,
                HttpMethod.Get,
                HttpMethod.Post,
                HttpMethod.Put,
                HttpMethod.Delete,
                HttpMethod.Patch,
            ),
        allowedHeaders =
            listOf(
                HttpHeaders.Authorization,
                HttpHeaders.ContentType,
                HttpHeaders.Accept,
                HttpHeaders.AccessControlAllowOrigin,
                "mcp-session-id",
                "mcp-protocol-version",
                "Last-Event-ID",
                "X-User-Id",
                "X-Request-Id",
            ),
        exposedHeaders =
            listOf(
                "mcp-session-id",
                "mcp-protocol-version",
            ),
    )

data class McpServerConfig(
    val serviceName: String,
    val serverPort: Int,
    val corsAllowedHosts: List<String>,
    val shutdownUrlPath: String,
    val telemetryOtlpProtocol: String?,
) {
    companion object {
        fun fromConfig(
            config: Config,
            defaultServiceName: String,
        ): McpServerConfig {
            val serverSection =
                if (config.hasPath("server")) {
                    config.getConfig("server")
                } else {
                    config
                }

            val corsHosts =
                System
                    .getenv("KTOR_CORS_ALLOWED_HOSTS")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .takeIf { it.isNotEmpty() } ?: listOf("localhost:5173", "localhost:7010")

            return McpServerConfig(
                serviceName = defaultServiceName,
                serverPort = serverSection.getString("port").toInt(),
                corsAllowedHosts = corsHosts,
                shutdownUrlPath =
                    try {
                        serverSection.getString("shutdownUrlPath")
                    } catch (e: com.typesafe.config.ConfigException.Missing) {
                        "/shutdown"
                    },
                telemetryOtlpProtocol =
                    if (config.hasPath("telemetry.otlp.protocol")) {
                        config.getString("telemetry.otlp.protocol").takeIf { it.isNotEmpty() }
                    } else {
                        null
                    },
            )
        }
    }
}

fun loadMcpServerConfig(
    config: Config = ConfigFactory.load(),
    defaultServiceName: String,
    defaultPort: Int,
): McpServerConfig {
    val serverSection =
        if (config.hasPath("server")) {
            config.getConfig("server")
        } else {
            config
        }

    val port =
        try {
            serverSection.getString("port").toInt()
        } catch (e: com.typesafe.config.ConfigException.Missing) {
            defaultPort
        }

    val shutdownPath =
        try {
            serverSection.getString("shutdownUrlPath")
        } catch (e: com.typesafe.config.ConfigException.Missing) {
            "/shutdown"
        }

    val telemetrySection =
        if (config.hasPath("telemetry")) {
            config.getConfig("telemetry")
        } else {
            config
        }
    val otlpProtocol =
        try {
            telemetrySection.getString("otlp.protocol").takeIf { it.isNotEmpty() }
        } catch (e: com.typesafe.config.ConfigException.Missing) {
            null
        }

    val corsHosts =
        System
            .getenv("KTOR_CORS_ALLOWED_HOSTS")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("localhost:5173", "localhost:7010")

    return McpServerConfig(
        serviceName = defaultServiceName,
        serverPort = port,
        corsAllowedHosts = corsHosts,
        shutdownUrlPath = shutdownPath,
        telemetryOtlpProtocol = otlpProtocol,
    )
}
