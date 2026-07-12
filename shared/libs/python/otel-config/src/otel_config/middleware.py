# SPDX-License-Identifier: Apache-2.0
import logging
from typing import Callable, Optional

from fastapi import Request, Response
from opentelemetry import trace
from starlette.middleware.base import BaseHTTPMiddleware

logger = logging.getLogger("otel_config.middleware")


class LoggingMiddleware(BaseHTTPMiddleware):
    """
    Middleware that logs incoming requests and outgoing responses with trace context.

    Logs:
    - Incoming request at INFO level (full payload)
    - Outgoing response at INFO level (full payload, BEFORE sending)
    - Includes trace_id, span_id, user_id in all log entries
    """

    def __init__(
        self,
        app,
        service_name: str,
        log_request_payload: bool = True,
        log_response_payload: bool = True,
        redact_sensitive: bool = True,
    ):
        super().__init__(app)
        self.service_name = service_name
        self.log_request_payload = log_request_payload
        self.log_response_payload = log_response_payload
        self.redact_sensitive = redact_sensitive
        if redact_sensitive:
            from .logging_handler import SensitiveDataFilter

            logging.getLogger().addFilter(SensitiveDataFilter())

    async def dispatch(self, request: Request, call_next: Callable) -> Response:
#         tracer = trace.get_tracer(self.service_name)
        span = trace.get_current_span()
        span_context = span.get_span_context()

        trace_id = format(span_context.trace_id, "032x") if span_context.is_valid else "unknown"
        span_id = format(span_context.span_id, "016x") if span_context.is_valid else "unknown"
        user_id = request.headers.get("X-User-ID", "unknown")
        request_id = request.headers.get("X-Request-ID", trace_id)

        extra_ctx = {
            "trace_id": trace_id,
            "span_id": span_id,
            "user_id": user_id,
            "request_id": request_id,
        }

        request_body = None
        if self.log_request_payload:
            try:
                request_body = await request.body()
                if request_body:
                    request_body_str = request_body.decode("utf-8")[:2000]
                    logger.info(
                        f"Incoming request: method={request.method} uri={request.url.path} "
                        f"userId={user_id} traceId={trace_id} spanId={span_id} "
                        f"body={request_body_str}",
                        extra=extra_ctx,
                    )
                else:
                    logger.info(
                        f"Incoming request: method={request.method} uri={request.url.path} "
                        f"userId={user_id} traceId={trace_id} spanId={span_id}",
                        extra=extra_ctx,
                    )
            except Exception as e:
                logger.warning(f"Could not read request body: {e}", extra=extra_ctx)
        else:
            logger.info(
                f"Incoming request: method={request.method} uri={request.url.path} "
                f"userId={user_id} traceId={trace_id} spanId={span_id}",
                extra=extra_ctx,
            )

        response = await call_next(request)

        if self.log_response_payload:
            response_body_str = ""
            if hasattr(response, "body"):
                try:
                    response_body_str = response.body.decode("utf-8")[:2000] if response.body else ""
                except Exception:
                    response_body_str = "<unable to read>"

            logger.info(
                f"Outgoing response: status={response.status_code} uri={request.url.path} "
                f"userId={user_id} traceId={trace_id} spanId={span_id} "
                f"body={response_body_str}",
                extra=extra_ctx,
            )
        else:
            logger.info(
                f"Outgoing response: status={response.status_code} uri={request.url.path} "
                f"userId={user_id} traceId={trace_id} spanId={span_id}",
                extra=extra_ctx,
            )

        return response


def get_user_id(request: Request) -> Optional[str]:
    """Extract user ID from request headers."""
    return request.headers.get("X-User-ID")


def get_trace_context(request: Request) -> dict:
    """Extract trace context from current span."""
    span = trace.get_current_span()
    span_context = span.get_span_context()

    return {
        "trace_id": format(span_context.trace_id, "032x") if span_context.is_valid else "unknown",
        "span_id": format(span_context.span_id, "016x") if span_context.is_valid else "unknown",
    }
