package shared.ktor

import com.typesafe.config.Config
import shared.ktor.mcp.McpKtorConfig

fun Config.getLongOrElse(
    path: String,
    default: Long,
): Long =
    try {
        if (hasPath(path)) getString(path).toLong() else default
    } catch (e: com.typesafe.config.ConfigException) {
        default
    }

fun Config.getMapOfLongOrElse(path: String): Map<String, Long> =
    try {
        if (hasPath(path)) {
            getConfig(path).entrySet().associate {
                it.key to
                    it.value
                        .unwrapped()
                        .toString()
                        .toLong()
            }
        } else {
            emptyMap()
        }
    } catch (e: com.typesafe.config.ConfigException) {
        emptyMap()
    }

object KtorConfigFactory {
    private const val DEFAULT_CORS_ENV_VAR = "KTOR_CORS_ALLOWED_HOSTS"
    private val DEFAULT_CORS_HOSTS = listOf("localhost:5173", "localhost:7010")

    fun fromConfig(
        config: Config,
        defaultServiceName: String,
        defaultPort: Int,
        engine: KtorEngine = KtorEngine.CIO,
    ): KtorServerConfig {
        val serverSection =
            try {
                val portTried = config.getInt("server.port")
                config.getConfig("server")
            } catch (e: com.typesafe.config.ConfigException.Missing) {
                config.getConfig("ktor.deployment")
            }
        val port =
            try {
                serverSection.getString("port").toInt()
            } catch (e: com.typesafe.config.ConfigException.Missing) {
                defaultPort
            }
        return KtorServerConfig(
            serviceName = defaultServiceName,
            serverPort = port,
            engine = engine,
            corsAllowedHosts = resolveCorsHosts(),
            telemetryEnabled = config.getBoolean("telemetry.enabled"),
            callLoggingConfig =
                if (config.hasPath("callLogging")) {
                    CallLoggingConfig(
                        level = org.slf4j.event.Level.INFO,
                    )
                } else {
                    null
                },
            forwardedHeaderEnabled = config.hasPath("forwardedHeader"),
        )
    }

    fun mcpFromConfig(
        config: Config,
        defaultServiceName: String,
        defaultPort: Int,
    ): McpKtorConfig {
        val serverSection =
            try {
                config.getConfig("server")
            } catch (e: com.typesafe.config.ConfigException.Missing) {
                config.getConfig("ktor.deployment")
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
        return McpKtorConfig(
            serviceName = defaultServiceName,
            serverPort = port,
            corsAllowedHosts = resolveCorsHosts(),
            telemetryEnabled = config.getBoolean("telemetry.enabled"),
            shutdownUrlPath = shutdownPath,
            connectionIdleTimeoutSeconds = config.getLongOrElse("mcp.connection-idle-timeout-seconds", 120),
            requestTimeoutSeconds = config.getLongOrElse("mcp.request-timeout-seconds", 90),
            defaultToolTimeoutMs = config.getLongOrElse("mcp.default-tool-timeout-ms", 60_000),
            toolTimeouts = config.getMapOfLongOrElse("mcp.tool-timeouts"),
        )
    }

    private fun resolveCorsHosts(): List<String> =
        System
            .getenv(DEFAULT_CORS_ENV_VAR)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: DEFAULT_CORS_HOSTS
}
