# SPDX-License-Identifier: Apache-2.0
import logging

from opentelemetry import trace
from opentelemetry.sdk._logs import LoggingHandler as SDKLoggingHandler


class OTELLoggingHandler(SDKLoggingHandler):
    """
    Custom logging handler that enriches log records with trace context.
    """

    def __init__(
        self,
        level: int = logging.INFO,
        logger_provider=None,
        include_trace_context: bool = True,
    ):
        super().__init__(level=level, logger_provider=logger_provider)
        self.include_trace_context = include_trace_context

    def emit(self, record: logging.LogRecord) -> None:
        if self.include_trace_context:
            span = trace.get_current_span()
            span_context = span.get_span_context()

            if span_context.is_valid:
                record.trace_id = format(span_context.trace_id, "032x")
                record.span_id = format(span_context.span_id, "016x")
                record.trace_flags = format(span_context.trace_flags, "02x")
            else:
                record.trace_id = ""
                record.span_id = ""
                record.trace_flags = ""

        super().emit(record)


class StructuredLoggingFormatter(logging.Formatter):
    """
    JSON formatter for structured logging with trace context.
    """

    def format(self, record: logging.LogRecord) -> str:
        import json

        log_data = {
            "timestamp": self.formatTime(record),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }

        if hasattr(record, "trace_id") and record.trace_id:
            log_data["trace_id"] = record.trace_id
        if hasattr(record, "span_id") and record.span_id:
            log_data["span_id"] = record.span_id
        if hasattr(record, "user_id") and record.user_id:
            log_data["user_id"] = record.user_id
        if hasattr(record, "request_id") and record.request_id:
            log_data["request_id"] = record.request_id

        if record.exc_info:
            log_data["exception"] = self.formatException(record.exc_info)

        return json.dumps(log_data)


class SensitiveDataFilter(logging.Filter):
    """
    Filter that redacts sensitive fields from log messages.
    """

    SENSITIVE_FIELDS = {
        "password",
        "token",
        "secret",
        "authorization",
        "api_key",
        "apikey",
        "access_token",
        "refresh_token",
    }

    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()

        for field in self.SENSITIVE_FIELDS:
            import re

            pattern = rf'({field}["\']?\s*[:=]\s*["\']?)([^"\'&\s,}}]+)'
            replacement = r"\1***REDACTED***"
            message = re.sub(pattern, replacement, message, flags=re.IGNORECASE)

        record.msg = message
        return True
