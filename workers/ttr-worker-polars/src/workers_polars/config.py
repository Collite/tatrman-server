"""Typed config holder loaded from HOCON.

Matches the platform-service convention (Kotlin services use Typesafe Config).
The Polars Worker is the first Python platform service; its config format
deliberately mirrors the rest of the platform via ``pyhocon`` rather than
following the ``agents/`` JSON + pydantic-settings pattern.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from pyhocon import ConfigFactory, ConfigTree


@dataclass(frozen=True)
class GrpcConfig:
    host: str
    port: int
    max_message_bytes: int


@dataclass(frozen=True)
class HttpConfig:
    host: str
    port: int


@dataclass(frozen=True)
class WorkspaceConfig:
    max_sessions: int
    max_dfs_per_session: int
    max_bytes_per_df: int
    max_total_bytes: int
    idle_ttl_seconds: int
    sweeper_interval_seconds: int


@dataclass(frozen=True)
class CapabilityConfig:
    engine_name: str
    engine_version: str
    supports_stateful_sessions: bool
    max_concurrent_sessions: int
    session_idle_timeout_seconds: int
    max_session_memory_mb: int


@dataclass(frozen=True)
class LimitsConfig:
    max_rows_per_result: int
    execute_timeout_seconds: int
    default_batch_size_rows: int
    max_batch_size_rows: int
    max_blob_bytes_per_cell: int


@dataclass(frozen=True)
class MetadataConfig:
    host: str
    port: int
    poll_interval_seconds: int


@dataclass(frozen=True)
class TelemetryConfig:
    enabled: bool
    otlp_endpoint: str


@dataclass(frozen=True)
class WorkersPolarsConfig:
    grpc: GrpcConfig
    http: HttpConfig
    workspace: WorkspaceConfig
    capability: CapabilityConfig
    limits: LimitsConfig
    metadata: MetadataConfig
    telemetry: TelemetryConfig


def load_config(path: str | Path | None = None) -> WorkersPolarsConfig:
    """Load HOCON config from ``path`` (or ``WORKERS_POLARS_CONFIG`` env, or default)."""
    config_path = (
        Path(path)
        if path is not None
        else Path(os.environ.get("WORKERS_POLARS_CONFIG", "conf/application.conf"))
    )
    if not config_path.is_absolute():
        # Resolve relative to the package root (works from `uv run` and from tests).
        candidates = [Path.cwd() / config_path, Path(__file__).resolve().parents[3] / config_path]
        config_path = next((c for c in candidates if c.exists()), config_path)
    tree = ConfigFactory.parse_file(str(config_path)).get_config("workers-polars")
    return _from_tree(tree)


def _from_tree(tree: ConfigTree) -> WorkersPolarsConfig:
    return WorkersPolarsConfig(
        grpc=GrpcConfig(
            host=tree.get_string("grpc.host"),
            port=int(tree.get("grpc.port")),
            max_message_bytes=int(tree.get("grpc.max-message-bytes")),
        ),
        http=HttpConfig(
            host=tree.get_string("http.host"),
            port=int(tree.get("http.port")),
        ),
        workspace=WorkspaceConfig(
            max_sessions=int(tree.get("workspace.max-sessions")),
            max_dfs_per_session=int(tree.get("workspace.max-dfs-per-session")),
            max_bytes_per_df=int(tree.get("workspace.max-bytes-per-df")),
            max_total_bytes=int(tree.get("workspace.max-total-bytes")),
            idle_ttl_seconds=int(tree.get("workspace.idle-ttl-seconds")),
            sweeper_interval_seconds=int(tree.get("workspace.sweeper-interval-seconds")),
        ),
        capability=CapabilityConfig(
            engine_name=tree.get_string("capability.engine-name"),
            engine_version=tree.get_string("capability.engine-version"),
            supports_stateful_sessions=bool(tree.get("capability.supports-stateful-sessions")),
            max_concurrent_sessions=int(tree.get("capability.max-concurrent-sessions")),
            session_idle_timeout_seconds=int(tree.get("capability.session-idle-timeout-seconds")),
            max_session_memory_mb=int(tree.get("capability.max-session-memory-mb")),
        ),
        limits=LimitsConfig(
            max_rows_per_result=int(tree.get("limits.max-rows-per-result")),
            execute_timeout_seconds=int(tree.get("limits.execute-timeout-seconds")),
            default_batch_size_rows=int(tree.get("limits.default-batch-size-rows")),
            max_batch_size_rows=int(tree.get("limits.max-batch-size-rows")),
            max_blob_bytes_per_cell=int(tree.get("limits.max-blob-bytes-per-cell")),
        ),
        metadata=MetadataConfig(
            host=tree.get_string("metadata.host"),
            port=int(tree.get("metadata.port")),
            poll_interval_seconds=int(tree.get("metadata.poll-interval-seconds")),
        ),
        telemetry=TelemetryConfig(
            enabled=bool(tree.get("telemetry.enabled")),
            otlp_endpoint=tree.get_string("telemetry.otlp.endpoint"),
        ),
    )
