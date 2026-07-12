# SPDX-License-Identifier: Apache-2.0
import logging
from typing import Optional

import httpx
from opentelemetry import trace

logger = logging.getLogger("otel_config.client")


class HttpClientLoggingInterceptor:
    """
    httpx client interceptor that logs outgoing requests and incoming responses.

    Logs:
    - Outgoing request at INFO level (full payload)
    - Incoming response at INFO level (just received confirmation)
    - Incoming response at DEBUG level (full payload)
    - Propagates W3C trace context headers
    """

    def __init__(
        self,
        service_name: str,
        log_request_payload: bool = True,
        log_response_payload: bool = False,
    ):
        self.service_name = service_name
        self.log_request_payload = log_request_payload
        self.log_response_payload = log_response_payload

    def _get_trace_context_headers(self) -> dict:
        """Get current trace context headers for propagation."""
        span = trace.get_current_span()
        span_context = span.get_span_context()

        headers = {}
        if span_context.is_valid:
            trace_flags = format(span_context.trace_flags, "02x")
            headers["traceparent"] = (
                f"00-{format(span_context.trace_id, '032x')}-{format(span_context.span_id, '016x')}-{trace_flags}"
            )
            headers["tracestate"] = ""

        return headers

    async def log_request(self, method: str, url: str, headers: dict, body: Optional[bytes] = None) -> None:
        """Log outgoing request at INFO level."""
        user_id = headers.get("X-User-ID", "unknown")
        request_id = headers.get("X-Request-ID", "unknown")

        logger.info(
            f"Calling service: {self.service_name} method={method} url={url} userId={user_id} requestId={request_id}",
        )

        if self.log_request_payload and body:
            try:
                body_str = body.decode("utf-8")[:2000]
                logger.info(f"Request payload to {self.service_name}: {body_str}")
            except Exception:
                logger.info(f"Request payload to {self.service_name}: <binary data>")

    async def log_response(
        self,
        status: int,
        headers: dict,
        body: Optional[bytes] = None,
        user_id: Optional[str] = None,
        request_id: Optional[str] = None,
    ) -> None:
        """Log incoming response."""
        user_id = user_id or headers.get("X-User-ID", "unknown")
        request_id = request_id or headers.get("X-Request-ID", "unknown")

        logger.info(
            f"Response received from {self.service_name}: status={status} userId={user_id} requestId={request_id}",
        )

        if self.log_response_payload and body:
            try:
                body_str = body.decode("utf-8")[:2000]
                logger.debug(f"Response payload from {self.service_name}: {body_str}")
            except Exception:
                logger.debug(f"Response payload from {self.service_name}: <binary data>")


class LoggingTransport:
    """
    httpx transport that wraps another transport and logs requests/responses.
    """

    def __init__(
        self,
        transport: httpx.AsyncHTTPTransport,
        interceptor: HttpClientLoggingInterceptor,
    ):
        self.transport = transport
        self.interceptor = interceptor

    async def handle(self, request: httpx.Request) -> httpx.Response:
        headers = dict(request.headers)
        headers.update(self.interceptor._get_trace_context_headers())

        await self.interceptor.log_request(
            method=request.method,
            url=str(request.url),
            headers=headers,
            body=request.content,
        )

        response = await self.transport.handle(request)

        await self.interceptor.log_response(
            status=response.status_code,
            headers=dict(response.headers),
            body=response.content,
        )

        return response


def install_logging_interceptor(
    client: httpx.AsyncClient,
    service_name: str,
    log_request_payload: bool = True,
    log_response_payload: bool = False,
) -> None:
    """
    Install logging interceptor on an httpx AsyncClient.

    Args:
        client: The httpx AsyncClient to instrument
        service_name: Name of the service being called (for logging)
        log_request_payload: Whether to log request payload at INFO level
        log_response_payload: Whether to log response payload at DEBUG level
    """
    interceptor = HttpClientLoggingInterceptor(
        service_name=service_name,
        log_request_payload=log_request_payload,
        log_response_payload=log_response_payload,
    )

    original_dispatch = client._dispatch

    async def logging_dispatch(request: httpx.Request) -> httpx.Response:
        headers = dict(request.headers)
        headers.update(interceptor._get_trace_context_headers())

        modified_request = httpx.Request(
            method=request.method,
            url=request.url,
            headers=headers,
            content=request.content,
        )

        await interceptor.log_request(
            method=request.method,
            url=str(request.url),
            headers=headers,
            body=request.content,
        )

        response = await original_dispatch(modified_request)

        await interceptor.log_response(
            status=response.status_code,
            headers=dict(response.headers),
            body=response.content,
        )

        return response

    client._dispatch = logging_dispatch
