# SPDX-License-Identifier: Apache-2.0
"""DF-P01 / Phase 06 C4 — Polars Worker `Execute` emits an OTEL span hierarchy.

Uses opentelemetry's `InMemorySpanExporter` (the canonical SDK test harness): set a tracer
provider with the exporter, run the RPC, then assert on the captured spans. No collector / no
network. Tests live in their own module so they don't permanently mutate the global tracer
provider used by other tests in this package.
"""

from __future__ import annotations

import io

import polars as pl
import pyarrow.ipc as ipc
import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from org.tatrman.plan.v1 import context_pb2, plan_pb2
from org.tatrman.worker.v1 import worker_pb2

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
from workers_polars.grpc_service import WorkerService
from workers_polars.workspace import WorkspaceStore


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


@pytest.fixture(scope="module")
def _otel_provider() -> InMemorySpanExporter:
    """One-shot: install a TracerProvider for the whole module. OTEL's global provider can only be
    set once per process, so we install it module-scope and `clear()` the exporter between tests.
    """
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    trace.set_tracer_provider(provider)
    return exporter


@pytest.fixture
def otel_exporter(_otel_provider: InMemorySpanExporter) -> InMemorySpanExporter:
    _otel_provider.clear()
    return _otel_provider


async def _drain(stream):
    out = []
    async for batch in stream:
        out.append(batch)
    return out


def _workspace_ref(name: str) -> plan_pb2.PlanNode:
    return plan_pb2.PlanNode(workspace_ref=plan_pb2.WorkspaceRef(workspace_name=name))


@pytest.mark.asyncio
async def test_execute_emits_expected_span_hierarchy(otel_exporter: InMemorySpanExporter):
    cfg = _cfg()
    store = WorkspaceStore(cfg.workspace)
    await store.put("s1", "q1", pl.DataFrame({"id": [1, 2, 3], "amount": [10.0, 20.0, 30.0]}))
    service = WorkerService(cfg, store)
    request = worker_pb2.ExecuteRequest(
        plan=_workspace_ref("q1"),
        context=context_pb2.PipelineContext(session_id="s1", correlation_id="trace-1"),
    )

    batches = await _drain(service.execute(request, context=None))
    assert len(batches) == 1
    # Drain the underlying Arrow stream to ensure no surprises.
    ipc.RecordBatchStreamReader(io.BytesIO(batches[0].arrow_ipc)).read_all()

    spans = otel_exporter.get_finished_spans()
    names = [s.name for s in spans]
    assert "workers-polars.Execute" in names
    assert "workers-polars.convert" in names
    assert "workers-polars.collect" in names
    assert "workers-polars.serialize" in names

    root = next(s for s in spans if s.name == "workers-polars.Execute")
    attrs = dict(root.attributes or {})
    assert attrs.get("engine") == "polars"
    assert attrs.get("workspace.session_id") == "s1"
    assert attrs.get("correlation_id") == "trace-1"
    assert attrs.get("result.row_count") == 3
    # No error path expected on the happy case.
    assert "error.code" not in attrs

    # Sub-spans share the same trace context as the root.
    convert = next(s for s in spans if s.name == "workers-polars.convert")
    assert convert.context.trace_id == root.context.trace_id


@pytest.mark.asyncio
async def test_execute_error_path_records_error_code_on_root_span(otel_exporter: InMemorySpanExporter):
    cfg = _cfg()
    service = WorkerService(cfg, WorkspaceStore(cfg.workspace))
    # Missing session_id triggers the workspace_requires_session early-out.
    request = worker_pb2.ExecuteRequest(
        plan=_workspace_ref("q1"),
        context=context_pb2.PipelineContext(),
    )
    batches = await _drain(service.execute(request, context=None))
    assert batches[0].messages[0].code == "workspace_requires_session"

    root = next(s for s in otel_exporter.get_finished_spans() if s.name == "workers-polars.Execute")
    assert dict(root.attributes or {}).get("error.code") == "workspace_requires_session"
