"""Polars Worker entry point.

Boot sequence:
  1. Load HOCON config.
  2. OTEL setup via the shared ``otel_config.setup_opentelemetry`` (DF-P01,
     Phase 06 C4) when ``workers-polars.telemetry.enabled`` is true.
  3. Start the workspace store (with TTL sweeper).
  4. Start the gRPC server with WorkerService handlers.
  5. Start the FastAPI probes/metrics server (separate port).
  6. Block on shutdown signal; gracefully drain.

Run via:
    uv run python -m workers_polars.main
"""

from __future__ import annotations

import asyncio
import logging
import signal
import traceback
from typing import Any

import grpc
import uvicorn
from otel_config import setup_opentelemetry

from workers_polars.config import WorkersPolarsConfig, load_config
from workers_polars.grpc_service import WorkerService, build_worker_service_handlers
from workers_polars.probes import (
    make_probes_app,
    workspace_bytes,
    workspace_dfs,
    workspace_evictions_total,
    workspace_sessions,
)
from workers_polars.workspace import WorkspaceStore

_SERVICE_NAME = "workers-polars"
logger = logging.getLogger(_SERVICE_NAME)


def _configure_logging(level: int = logging.DEBUG) -> None:
    logging.basicConfig(
        level=level,
        format=f"%(asctime)s %(levelname)s {_SERVICE_NAME} — %(message)s",
    )


def _log_exception(exc: BaseException, msg: str) -> None:
    logger.error("%s: %s: %s", msg, type(exc).__name__, exc)
    stack = traceback.format_exception(type(exc), exc, exc.__traceback__, limit=8)
    for line in stack:
        for ln in line.rstrip().split("\n"):
            logger.debug("  %s", ln)


async def _run_grpc(
    cfg: WorkersPolarsConfig,
    service: WorkerService,
    ready: asyncio.Event,
) -> None:
    server = grpc.aio.server(
        options=[
            ("grpc.max_send_message_length", cfg.grpc.max_message_bytes),
            ("grpc.max_receive_message_length", cfg.grpc.max_message_bytes),
        ],
    )
    server.add_generic_rpc_handlers((build_worker_service_handlers(service),))
    addr = f"{cfg.grpc.host}:{cfg.grpc.port}"
    try:
        server.add_insecure_port(addr)
    except Exception as exc:  # noqa: BLE001
        _log_exception(exc, f"Failed to add port {addr}")
        raise
    await server.start()
    logger.info("gRPC server listening on %s", addr)
    ready.set()
    try:
        await server.wait_for_termination()
    except Exception as exc:  # noqa: BLE001
        _log_exception(exc, "gRPC server terminated unexpectedly")
        raise


async def _run_http(cfg: WorkersPolarsConfig, ready: asyncio.Event, workspace_stats: Any) -> None:
    app = make_probes_app(cfg, is_ready=ready.is_set, workspace_stats=workspace_stats)
    config = uvicorn.Config(
        app,
        host=cfg.http.host,
        port=cfg.http.port,
        log_level="info",
        access_log=False,
    )
    server = uvicorn.Server(config)
    logger.info("HTTP probes listening on %s:%d", cfg.http.host, cfg.http.port)
    await server.serve()


async def _amain() -> None:
    _configure_logging()
    cfg = load_config()
    logger.info(
        "Starting %s (engine=%s grpc_port=%d http_port=%d stateful=%s)",
        _SERVICE_NAME,
        cfg.capability.engine_name,
        cfg.grpc.port,
        cfg.http.port,
        cfg.capability.supports_stateful_sessions,
    )

    # DF-P01 — wire OTEL when enabled. Off by default (local dev w/o a collector); a deployment
    # overlay sets OTEL_ENABLED_POLARS=true + OTEL_EXPORTER_OTLP_ENDPOINT.
    if cfg.telemetry.enabled:
        setup_opentelemetry(
            service_name="workers-polars",
            otel_endpoint=cfg.telemetry.otlp_endpoint,
            protocol="grpc",
        )
        logger.info("OpenTelemetry enabled (endpoint=%s)", cfg.telemetry.otlp_endpoint)

    ready = asyncio.Event()

    store = WorkspaceStore(cfg.workspace)
    store.add_eviction_callback(lambda reason: workspace_evictions_total.labels(reason=reason).inc())
    service = WorkerService(cfg, store)

    def workspace_stats() -> dict[str, Any]:
        stats = store.stats()
        # Refresh the gauges so /metrics reflects current state on every scrape.
        workspace_sessions.set(stats["sessions"])
        workspace_dfs.set(stats["dfs"])
        workspace_bytes.set(stats["bytes"])
        return stats

    sweeper_task = asyncio.create_task(store.run_sweeper(), name="workspace-sweeper")
    grpc_task = asyncio.create_task(_run_grpc(cfg, service, ready), name="grpc")
    http_task = asyncio.create_task(_run_http(cfg, ready, workspace_stats), name="http")

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except NotImplementedError:
            # Windows / non-POSIX. Fall back to the default Ctrl-C handler.
            pass

    try:
        await stop.wait()
    finally:
        logger.info("Shutdown requested — cancelling tasks…")
        for task in (grpc_task, http_task, sweeper_task):
            task.cancel()
        await asyncio.gather(grpc_task, http_task, sweeper_task, return_exceptions=True)


def main() -> None:
    _configure_logging()
    try:
        asyncio.run(_amain())
    except KeyboardInterrupt:
        pass
    except Exception as exc:  # noqa: BLE001
        _log_exception(exc, f"{_SERVICE_NAME} terminated unexpectedly")
        raise


if __name__ == "__main__":
    main()
