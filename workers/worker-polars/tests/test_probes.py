# SPDX-License-Identifier: Apache-2.0
"""Phase 2.4 §J — HTTP probes + metrics surface."""

from __future__ import annotations

from fastapi.testclient import TestClient

from workers_polars.config import (
    CapabilityConfig,
    GrpcConfig,
    HttpConfig,
    LimitsConfig,
    MetadataConfig,
    TelemetryConfig,
    WorkersPolarsConfig,
    WorkspaceConfig,
)
from workers_polars.probes import make_probes_app


def _cfg() -> WorkersPolarsConfig:
    return WorkersPolarsConfig(
        grpc=GrpcConfig(host="0", port=7501, max_message_bytes=33554432),
        http=HttpConfig(host="0", port=7502),
        workspace=WorkspaceConfig(
            max_sessions=10,
            max_dfs_per_session=10,
            max_bytes_per_df=10_000_000,
            max_total_bytes=100_000_000,
            idle_ttl_seconds=60,
            sweeper_interval_seconds=10,
        ),
        capability=CapabilityConfig(
            engine_name="polars",
            engine_version="polars 1.13",
            supports_stateful_sessions=True,
            max_concurrent_sessions=1000,
            session_idle_timeout_seconds=3600,
            max_session_memory_mb=8192,
        ),
        limits=LimitsConfig(
            max_rows_per_result=1000000,
            execute_timeout_seconds=300,
            default_batch_size_rows=10000,
            max_batch_size_rows=100000,
            max_blob_bytes_per_cell=8388608,
        ),
        metadata=MetadataConfig(host="localhost", port=7204, poll_interval_seconds=60),
        telemetry=TelemetryConfig(enabled=False, otlp_endpoint=""),
    )


def test_health_always_returns_ok():
    cfg = _cfg()
    app = make_probes_app(cfg, is_ready=lambda: False, workspace_stats=lambda: {})
    client = TestClient(app)
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_ready_returns_503_until_grpc_bound():
    cfg = _cfg()
    flag = {"ready": False}
    app = make_probes_app(cfg, is_ready=lambda: flag["ready"], workspace_stats=lambda: {})
    client = TestClient(app)

    r = client.get("/ready")
    assert r.status_code == 503

    flag["ready"] = True
    r = client.get("/ready")
    assert r.status_code == 200


def test_status_includes_engine_and_workspace_stats():
    cfg = _cfg()
    stats = {"sessions": 3, "dfs": 7, "bytes": 12345, "max_sessions": 10}
    app = make_probes_app(cfg, is_ready=lambda: True, workspace_stats=lambda: stats)
    client = TestClient(app)
    r = client.get("/status")
    assert r.status_code == 200
    body = r.json()
    assert body["engine"] == "polars"
    assert body["supports_stateful_sessions"] is True
    assert body["workspace"] == stats


def test_metrics_includes_polars_metric_names():
    cfg = _cfg()
    app = make_probes_app(cfg, is_ready=lambda: True, workspace_stats=lambda: {})
    client = TestClient(app)
    r = client.get("/metrics")
    assert r.status_code == 200
    body = r.text
    # The counters / gauges declared in probes.py should at least be
    # registered (Prometheus exposition lists registered metrics whether
    # or not they have observed values).
    for name in (
        "polars_executes_total",
        "polars_execute_duration_seconds",
        "polars_workspace_sessions",
        "polars_workspace_dfs",
        "polars_workspace_bytes",
        "polars_workspace_evictions_total",
    ):
        assert name in body, f"expected metric {name!r} in /metrics output"
