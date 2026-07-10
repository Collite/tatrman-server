package shared.ktor.mcp

import com.typesafe.config.ConfigFactory
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

class McpTelemetry(
    private val serviceName: String,
    private val protocol: String = "grpc",
    enabled: Boolean = defaultEnabledFromHocon(),
) {
    // Type is `OpenTelemetry` (interface) — concrete value is `OpenTelemetrySdk` when enabled,
    // `OpenTelemetry.noop()` when disabled. Kept under the historical `openTelemetrySdk` name so
    // existing MCP Application.kt callers (`installMcpKtorBase(..., telemetry.openTelemetrySdk)`)
    // keep compiling.
    val openTelemetrySdk: OpenTelemetry
    val openTelemetry: OpenTelemetry
    val tracer: Tracer

    init {
        val otelConfig = OtelEndpointConfig(serviceName = serviceName, protocol = protocol)
        openTelemetrySdk = createOpenTelemetrySdk(otelConfig, enabled = enabled)
        openTelemetry = openTelemetrySdk
        tracer = openTelemetrySdk.getTracer(serviceName)
    }

    companion object {
        private fun defaultEnabledFromHocon(): Boolean {
            val config = ConfigFactory.load()
            return config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled")
        }
    }
}
