"""HTTP probes / status / metrics for the Polars Worker.

Runs alongside the gRPC server on a dedicated HTTP port (default 7502).
Endpoints:
  * ``GET /health``   — liveness; always 200 if process is up.
  * ``GET /ready``    — 200 once the gRPC server is bound.
  * ``GET /status``   — JSON: capability + workspace stats + active streams.
  * ``GET /metrics``  — Prometheus exposition (text/plain; version=0.0.4).
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from fastapi import FastAPI, Response
from prometheus_client import (
    CONTENT_TYPE_LATEST,
    Counter,
    Gauge,
    Histogram,
    generate_latest,
)

from workers_polars.config import WorkersPolarsConfig

# ----- Prometheus metrics (module-level so they are singletons) -----

executes_total = Counter(
    "polars_executes_total",
    "Number of Execute RPCs handled by the Polars Worker.",
    labelnames=("result",),
)
execute_duration_seconds = Histogram(
    "polars_execute_duration_seconds",
    "Duration of Execute RPCs (seconds).",
)
workspace_sessions = Gauge(
    "polars_workspace_sessions",
    "Number of active sessions in the workspace store.",
)
workspace_dfs = Gauge(
    "polars_workspace_dfs",
    "Number of stored dataframes across all sessions.",
)
workspace_bytes = Gauge(
    "polars_workspace_bytes",
    "Estimated total bytes held in the workspace store.",
)
workspace_evictions_total = Counter(
    "polars_workspace_evictions_total",
    "Number of workspace entries evicted.",
    labelnames=("reason",),
)


def make_probes_app(
    cfg: WorkersPolarsConfig,
    is_ready: Callable[[], bool],
    workspace_stats: Callable[[], dict[str, Any]],
) -> FastAPI:
    """Build the FastAPI app for /health /ready /status /metrics."""
    app = FastAPI(title="workers-polars", version="0.1.0")

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/ready")
    async def ready() -> Response:
        if is_ready():
            return Response(content='{"status":"ready"}', media_type="application/json")
        return Response(content='{"status":"not_ready"}', media_type="application/json", status_code=503)

    @app.get("/status")
    async def status() -> dict[str, Any]:
        return {
            "service": "workers-polars",
            "version": "0.1.0",
            "engine": cfg.capability.engine_name,
            "engine_version": cfg.capability.engine_version,
            "supports_stateful_sessions": cfg.capability.supports_stateful_sessions,
            "grpc_port": cfg.grpc.port,
            "http_port": cfg.http.port,
            "workspace": workspace_stats(),
        }

    @app.get("/metrics")
    async def metrics() -> Response:
        return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)

    return app
