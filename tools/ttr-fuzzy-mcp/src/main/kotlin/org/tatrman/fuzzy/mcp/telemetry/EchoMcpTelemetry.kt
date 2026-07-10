package org.tatrman.fuzzy.mcp.telemetry

import com.typesafe.config.ConfigFactory
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

class EchoMcpTelemetry {
    val openTelemetry: OpenTelemetry
    val tracer: Tracer

    init {
        val config = ConfigFactory.load()
        val enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled")
        val protocol = System.getenv("ECHO_MCP_OTEL_PROTOCOL") ?: "grpc"
        openTelemetry =
            createOpenTelemetrySdk(
                OtelEndpointConfig(serviceName = "echo-mcp", protocol = protocol),
                enabled = enabled,
            )
        tracer = openTelemetry.getTracer("echo-mcp")
    }
}
