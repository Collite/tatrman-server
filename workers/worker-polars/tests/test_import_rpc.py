# SPDX-License-Identifier: Apache-2.0
"""Charon Stage 3.1 closeout — ImportDataFrame + DropWorkspaceEntry RPCs.

The session-workspace ingest path that closes the POLARS stage-in gap: Charon's
WorkerEndpoint (POLARS) client-streams an external Arrow DataFrame into a
Polars session, symmetric to the WorkspaceRef read-out.
"""

from __future__ import annotations

import io

import grpc
import polars as pl
import pyarrow.ipc as ipc
import pytest
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


def _cfg(max_dfs_per_session: int = 10) -> WorkersPolarsConfig:
    return WorkersPolarsConfig(
        grpc=GrpcConfig(host="0", port=7501, max_message_bytes=33554432),
        http=HttpConfig(host="0", port=7502),
        workspace=WorkspaceConfig(
            max_sessions=10,
            max_dfs_per_session=max_dfs_per_session,
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


class _Aborted(Exception):
    def __init__(self, code: grpc.StatusCode, details: str) -> None:
        self.code = code
        self.details = details


class _FakeContext:
    """Minimal ServicerContext stand-in: abort() raises so the test can assert."""

    async def abort(self, code: grpc.StatusCode, details: str) -> None:  # noqa: D401
        raise _Aborted(code, details)


def _ipc_bytes(df: pl.DataFrame) -> bytes:
    table = df.to_arrow()
    sink = io.BytesIO()
    with ipc.new_stream(sink, table.schema) as writer:
        for batch in table.to_batches():
            writer.write_batch(batch)
    return sink.getvalue()


async def _chunks(session: str, df_name: str, payloads, expected_fp: str | None = None):
    header = worker_pb2.ImportHeader(session_id=session, df_name=df_name)
    if expected_fp is not None:
        header.expected_schema_fingerprint = expected_fp
    for i, payload in enumerate(payloads):
        chunk = worker_pb2.ImportChunk(ipc_payload=payload)
        if i == 0:
            chunk.header.CopyFrom(header)
        yield chunk


@pytest.mark.asyncio
async def test_import_data_frame_stores_df_and_returns_fingerprint():
    cfg = _cfg()
    store = WorkspaceStore(cfg.workspace)
    service = WorkerService(cfg, store)
    df = pl.DataFrame({"id": [1, 2, 3], "amount": [10.0, 20.0, 30.0]})

    result = await service.import_data_frame(
        _chunks("s1", "evidence", [_ipc_bytes(df)]),
        context=_FakeContext(),
    )
    assert result.df_name == "evidence"
    assert result.rows == 3
    assert result.schema_fingerprint != ""

    entry = await store.get("s1", "evidence")
    assert entry is not None
    assert entry.df.shape == (3, 2)


@pytest.mark.asyncio
async def test_import_then_drop_is_idempotent():
    cfg = _cfg()
    store = WorkspaceStore(cfg.workspace)
    service = WorkerService(cfg, store)
    df = pl.DataFrame({"id": [1, 2]})
    await service.import_data_frame(_chunks("s1", "d1", [_ipc_bytes(df)]), context=_FakeContext())

    dropped = await service.drop_workspace_entry(
        worker_pb2.DropWorkspaceRequest(session_id="s1", name="d1"), _context=None
    )
    assert dropped.existed is True
    # idempotent — dropping again reports not-existed, no error.
    again = await service.drop_workspace_entry(
        worker_pb2.DropWorkspaceRequest(session_id="s1", name="d1"), _context=None
    )
    assert again.existed is False
    assert await store.get("s1", "d1") is None


@pytest.mark.asyncio
async def test_import_missing_header_aborts_invalid_argument():
    cfg = _cfg()
    service = WorkerService(cfg, WorkspaceStore(cfg.workspace))
    df = pl.DataFrame({"id": [1]})

    async def _no_header_chunks():
        yield worker_pb2.ImportChunk(ipc_payload=_ipc_bytes(df))  # no header set

    with pytest.raises(_Aborted) as ei:
        await service.import_data_frame(_no_header_chunks(), context=_FakeContext())
    assert ei.value.code == grpc.StatusCode.INVALID_ARGUMENT


@pytest.mark.asyncio
async def test_import_fingerprint_mismatch_aborts_failed_precondition():
    cfg = _cfg()
    service = WorkerService(cfg, WorkspaceStore(cfg.workspace))
    df = pl.DataFrame({"id": [1, 2]})
    with pytest.raises(_Aborted) as ei:
        await service.import_data_frame(
            _chunks("s1", "d1", [_ipc_bytes(df)], expected_fp="deadbeef"),
            context=_FakeContext(),
        )
    assert ei.value.code == grpc.StatusCode.FAILED_PRECONDITION


@pytest.mark.asyncio
async def test_import_cap_exceeded_aborts_resource_exhausted():
    cfg = _cfg(max_dfs_per_session=1)
    store = WorkspaceStore(cfg.workspace)
    service = WorkerService(cfg, store)
    await store.put("s1", "already", pl.DataFrame({"x": [1]}))
    df = pl.DataFrame({"id": [1, 2]})
    with pytest.raises(_Aborted) as ei:
        await service.import_data_frame(_chunks("s1", "d2", [_ipc_bytes(df)]), context=_FakeContext())
    assert ei.value.code == grpc.StatusCode.RESOURCE_EXHAUSTED


@pytest.mark.asyncio
async def test_import_then_execute_workspace_ref_round_trips():
    """End-to-end within Polars: import a DF, then read it back via Execute(WorkspaceRef)."""
    from org.tatrman.plan.v1 import context_pb2, plan_pb2

    cfg = _cfg()
    store = WorkspaceStore(cfg.workspace)
    service = WorkerService(cfg, store)
    df = pl.DataFrame({"id": [1, 2, 3, 4], "label": ["a", "b", "c", "d"]})
    await service.import_data_frame(_chunks("s9", "staged", [_ipc_bytes(df)]), context=_FakeContext())

    request = worker_pb2.ExecuteRequest(
        plan=plan_pb2.PlanNode(workspace_ref=plan_pb2.WorkspaceRef(workspace_name="staged")),
        context=context_pb2.PipelineContext(session_id="s9"),
    )
    out = []
    async for batch in service.execute(request, context=None):
        out.append(batch)
    assert out[0].batch_row_count == 4
