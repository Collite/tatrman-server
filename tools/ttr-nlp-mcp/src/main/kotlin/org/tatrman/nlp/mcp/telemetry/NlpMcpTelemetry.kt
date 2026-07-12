// SPDX-License-Identifier: Apache-2.0
package org.tatrman.nlp.mcp.telemetry

import com.typesafe.config.ConfigFactory
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

class NlpMcpTelemetry {
    val openTelemetry: OpenTelemetry
    val tracer: Tracer

    init {
        val config = ConfigFactory.load()
        val enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled")
        val protocol = System.getenv("NLP_MCP_OTEL_PROTOCOL") ?: "grpc"
        openTelemetry =
            createOpenTelemetrySdk(
                OtelEndpointConfig(serviceName = "nlp-mcp", protocol = protocol),
                enabled = enabled,
            )
        tracer = openTelemetry.getTracer("nlp-mcp")
    }
}
