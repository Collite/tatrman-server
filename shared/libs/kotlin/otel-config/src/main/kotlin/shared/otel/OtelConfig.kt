package shared.otel

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor

/**
 * Configuration for resolving the OTLP endpoint from environment variables.
 *
 * @param serviceName The OpenTelemetry `service.name` attribute value (e.g. "erp-api").
 * @param protocol    Transport protocol value: "grpc" (default), "http", or "https".
 *                    The caller is responsible for reading the appropriate env var and passing the value.
 * @param hostEnvVar  Env var name for the collector host. Defaults to "OTEL_EXPORTER_OTLP_HOST".
 * @param defaultHost Fallback host when the env var is absent. Defaults to "localhost".
 */
data class OtelEndpointConfig(
    val serviceName: String,
    val protocol: String = "grpc",
    val hostEnvVar: String = "OTEL_EXPORTER_OTLP_HOST",
    val defaultHost: String = "localhost",
)

/**
 * Creates an [OpenTelemetry] instance.
 *
 * When [enabled] is `false`, returns [OpenTelemetry.noop] — no exporters are constructed,
 * no background threads started, no Logback appender installed. Callers should plumb the
 * `telemetry.enabled` HOCON flag through so a single config knob silences OTel everywhere.
 *
 * When [enabled] is `true`, returns a fully-configured [OpenTelemetrySdk] (subtype of
 * `OpenTelemetry`) with all three signal providers:
 * - Traces  → [SdkTracerProvider] + [BatchSpanProcessor] + [OtlpGrpcSpanExporter]
 * - Metrics → [SdkMeterProvider] + [PeriodicMetricReader] + [OtlpGrpcMetricExporter]
 * - Logs    → [SdkLoggerProvider] + [BatchLogRecordProcessor] + [OtlpGrpcLogRecordExporter]
 *
 * All three providers share the same OTLP endpoint and resource, resolved from environment
 * variables defined in [config].
 */
fun createOpenTelemetrySdk(
    config: OtelEndpointConfig,
    enabled: Boolean = true,
): OpenTelemetry {
    if (!enabled) {
        println("[OTEL-CONFIG] service=${config.serviceName} telemetry disabled — returning noop OpenTelemetry")
        return OpenTelemetry.noop()
    }
    // Resolve transport protocol and build the full endpoint URL
    val host = System.getenv(config.hostEnvVar) ?: config.defaultHost
    val protocol = config.protocol.lowercase()
    val port =
        when (protocol) {
            "https" -> System.getenv("OTEL_EXPORTER_OTLP_HTTPS_PORT") ?: "4319"
            "http" -> System.getenv("OTEL_EXPORTER_OTLP_HTTP_PORT") ?: "4318"
            else -> System.getenv("OTEL_EXPORTER_OTLP_GRPC_PORT") ?: "4317"
        }
    val scheme =
        when (protocol) {
            "https", "grpcs" -> "https"
            else -> "http"
        }
    val otelUrl = "$scheme://$host:$port"

    // Diagnostic: print resolved config so it is visible in pod logs even before logging is wired up
    println("[OTEL-CONFIG] service=${config.serviceName} protocol=$protocol host=$host port=$port url=$otelUrl")
    println(
        "[OTEL-CONFIG] env ${config.hostEnvVar}=${System.getenv(
            config.hostEnvVar,
        )} OTEL_EXPORTER_OTLP_GRPC_PORT=${System.getenv("OTEL_EXPORTER_OTLP_GRPC_PORT")}",
    )

    // Shared resource carrying the service identity
    val otelResource =
        Resource.getDefault().merge(
            Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), config.serviceName),
            ),
        )

    // Trace provider: OTLP gRPC span exporter with batch processing
    val spanExporter = OtlpGrpcSpanExporter.builder().setEndpoint(otelUrl).build()
    val tracerProvider =
        SdkTracerProvider
            .builder()
            .setResource(otelResource)
            .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
            .build()

    // Meter provider: OTLP gRPC metric exporter with periodic reading
    val metricExporter = OtlpGrpcMetricExporter.builder().setEndpoint(otelUrl).build()
    val meterProvider =
        SdkMeterProvider
            .builder()
            .setResource(otelResource)
            .registerMetricReader(PeriodicMetricReader.builder(metricExporter).build())
            .build()

    // Logger provider: OTLP gRPC log record exporter with batch processing
    val logExporter = OtlpGrpcLogRecordExporter.builder().setEndpoint(otelUrl).build()
    val loggerProvider =
        SdkLoggerProvider
            .builder()
            .setResource(otelResource)
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build())
            .build()

    val sdk =
        OpenTelemetrySdk
            .builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .setLoggerProvider(loggerProvider)
            .build()

    // Install the SDK into the Logback OpenTelemetryAppender so that SLF4J/Logback
    // log records are forwarded to the SdkLoggerProvider and exported via OTLP.
    OpenTelemetryAppender.install(sdk)
    println("[OTEL-CONFIG] OpenTelemetryAppender installed for service=${config.serviceName}")

    return sdk
}
