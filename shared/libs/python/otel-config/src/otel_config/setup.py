import logging

from opentelemetry import metrics, trace
from opentelemetry._logs import set_logger_provider
from opentelemetry.exporter.otlp.proto.grpc._log_exporter import OTLPLogExporter as OTLPLogExporterGrpc
from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter as OTLPMetricExporterGrpc
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter as OTLPSpanExporterGrpc
from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter as OTLPLogExporterHttp
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter as OTLPMetricExporterHttp
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter as OTLPSpanExporterHttp
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

logger = logging.getLogger("otel_config")


def setup_opentelemetry(
    service_name: str,
    otel_endpoint: str,
    protocol: str = "grpc",
    insecure: bool = True,
) -> dict:
    """
    Initialize OpenTelemetry for tracing, metrics, and logging.

    Args:
        service_name: The service name for OTEL resource
        otel_endpoint: The OTEL collector endpoint
        protocol: "grpc" or "http"
        insecure: Whether to use insecure connection

    Returns:
        Dictionary with tracer, meter, and logger_provider
    """
    use_grpc = protocol.lower() == "grpc"

    resource = Resource(attributes={"service.name": service_name})

    trace.set_tracer_provider(TracerProvider(resource=resource))

    if use_grpc:
        trace_exporter = OTLPSpanExporterGrpc(endpoint=otel_endpoint, insecure=insecure)
    else:
        trace_exporter = OTLPSpanExporterHttp(endpoint=otel_endpoint)
    span_processor = BatchSpanProcessor(trace_exporter)
    trace.get_tracer_provider().add_span_processor(span_processor)

    if use_grpc:
        metric_exporter = OTLPMetricExporterGrpc(endpoint=otel_endpoint, insecure=insecure)
    else:
        metric_exporter = OTLPMetricExporterHttp(endpoint=otel_endpoint)
    metric_reader = PeriodicExportingMetricReader(exporter=metric_exporter)
    meter_provider = MeterProvider(resource=resource, metric_readers=[metric_reader])
    metrics.set_meter_provider(meter_provider)

    logger_provider = LoggerProvider(resource=resource)
    if use_grpc:
        log_exporter = OTLPLogExporterGrpc(endpoint=otel_endpoint, insecure=insecure)
    else:
        log_exporter = OTLPLogExporterHttp(endpoint=otel_endpoint)
    logger_provider.add_log_record_processor(BatchLogRecordProcessor(log_exporter))
    set_logger_provider(logger_provider)

    otel_handler = LoggingHandler(level=logging.INFO, logger_provider=logger_provider)

    root_logger = logging.getLogger()
    root_logger.addHandler(otel_handler)
    root_logger.setLevel(logging.INFO)

    logger.info(f"OpenTelemetry initialized for service={service_name} endpoint={otel_endpoint} protocol={protocol}")

    return {
        "tracer": trace.get_tracer(service_name),
        "meter": metrics.get_meter(service_name),
        "logger_provider": logger_provider,
    }


def instrument_fastapi(app):
    """Instrument a FastAPI application with OpenTelemetry."""
    FastAPIInstrumentor.instrument_app(app)
