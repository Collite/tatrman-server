"""Session-scoped workspace store.

Holds Polars DataFrames keyed by ``(session_id, workspace_name)``. The
Polars Worker is the first stateful Worker in the platform — this store
is what makes the v1 architecture's "session-scoped workspace doors"
contract concrete.

Eviction policy:
  * Per-entry TTL on ``last_accessed_at`` (config: ``idle-ttl-seconds``,
    default 60 min — matches Dispatcher sticky-session TTL).
  * Hard caps from config: max-sessions, max-dfs-per-session,
    max-bytes-per-df, max-total-bytes. ``put()`` raises
    ``WorkspaceCapExceeded`` when a cap would be breached.
  * Background ``sweeper()`` task runs ``evict_idle()`` every
    ``sweeper-interval-seconds``.

Concurrency:
  * One asyncio.Lock per session protects mutating ops on that
    session's entries; a global lock protects per-session lock creation
    and total-bytes accounting.
  * Read paths return a reference to the (immutable) DataFrame; we
    never mutate stored entries in place.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import polars as pl

from workers_steropes.config import WorkspaceConfig

logger = logging.getLogger("workers_steropes.workspace")


class WorkspaceCapExceeded(Exception):
    """Raised when ``put()`` would breach a workspace cap.

    The ``code`` carries the structured-message code the Worker should
    surface in the Execute response (``workspace_cap_exceeded``).
    """

    code: str = "workspace_cap_exceeded"


@dataclass
class WorkspaceEntry:
    """One stored DataFrame plus accounting."""

    df: pl.DataFrame
    schema_metadata: dict[str, dict[str, str]] = field(default_factory=dict)
    created_at: float = field(default_factory=time.monotonic)
    last_accessed_at: float = field(default_factory=time.monotonic)
    bytes_estimate: int = 0


class WorkspaceStore:
    """Per-session, in-memory store of Polars DataFrames.

    Thread-safe under cooperative scheduling (asyncio).
    """

    def __init__(
        self,
        cfg: WorkspaceConfig,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._cfg = cfg
        self._clock = clock
        self._sessions: dict[str, dict[str, WorkspaceEntry]] = {}
        self._session_locks: dict[str, asyncio.Lock] = {}
        self._global_lock = asyncio.Lock()
        self._eviction_callbacks: list[Callable[[str], None]] = []

    # ----- accessors -----

    async def get(self, session_id: str, name: str) -> WorkspaceEntry | None:
        async with self._global_lock:
            entry = self._sessions.get(session_id, {}).get(name)
            # Touch the TTL inside the lock so a concurrent evict_idle/drop can't
            # race the accounting (and we never extend a detached entry).
            if entry is not None:
                entry.last_accessed_at = self._clock()
        return entry

    async def put(
        self,
        session_id: str,
        name: str,
        df: pl.DataFrame,
        schema_metadata: dict[str, dict[str, str]] | None = None,
    ) -> WorkspaceEntry:
        if not session_id:
            raise ValueError("session_id is required")
        if not name:
            raise ValueError("workspace name is required")

        bytes_estimate = int(df.estimated_size("b"))
        if bytes_estimate > self._cfg.max_bytes_per_df:
            raise WorkspaceCapExceeded(
                f"workspace df '{name}' is {bytes_estimate} bytes — exceeds max-bytes-per-df {self._cfg.max_bytes_per_df}",
            )

        async with self._global_lock:
            current_total = self._total_bytes_locked()
            if current_total + bytes_estimate > self._cfg.max_total_bytes:
                raise WorkspaceCapExceeded(
                    f"adding df '{name}' ({bytes_estimate} bytes) would exceed max-total-bytes {self._cfg.max_total_bytes} (current: {current_total})",
                )

            session_entries = self._sessions.get(session_id)
            if session_entries is None:
                if len(self._sessions) >= self._cfg.max_sessions:
                    raise WorkspaceCapExceeded(
                        f"max-sessions {self._cfg.max_sessions} reached; cannot create session '{session_id}'",
                    )
                session_entries = {}
                self._sessions[session_id] = session_entries
                self._session_locks[session_id] = asyncio.Lock()

            if name not in session_entries and len(session_entries) >= self._cfg.max_dfs_per_session:
                raise WorkspaceCapExceeded(
                    f"max-dfs-per-session {self._cfg.max_dfs_per_session} reached for session '{session_id}'",
                )

            now = self._clock()
            entry = WorkspaceEntry(
                df=df,
                schema_metadata=schema_metadata or {},
                created_at=now,
                last_accessed_at=now,
                bytes_estimate=bytes_estimate,
            )
            session_entries[name] = entry
            return entry

    async def drop(self, session_id: str, name: str) -> bool:
        """Drop a single named DataFrame from a session. Returns whether it
        existed (idempotent — dropping an absent entry is not an error).
        Backs WorkerService.DropWorkspaceEntry / Charon Evict(worker_df)."""
        async with self._global_lock:
            entries = self._sessions.get(session_id)
            if entries is None or name not in entries:
                return False
            entries.pop(name, None)
            if not entries:
                self._sessions.pop(session_id, None)
                self._session_locks.pop(session_id, None)
            return True

    async def evict_session(self, session_id: str) -> int:
        async with self._global_lock:
            entries = self._sessions.pop(session_id, None)
            self._session_locks.pop(session_id, None)
            return len(entries) if entries else 0

    async def evict_idle(self, now: float | None = None) -> int:
        cutoff = (now or self._clock()) - self._cfg.idle_ttl_seconds
        evicted = 0
        async with self._global_lock:
            for session_id, entries in list(self._sessions.items()):
                stale = [name for name, e in entries.items() if e.last_accessed_at < cutoff]
                for name in stale:
                    entries.pop(name, None)
                    evicted += 1
                    for cb in self._eviction_callbacks:
                        try:
                            cb("ttl")
                        except Exception:
                            logger.exception("eviction callback failed")
                if not entries:
                    self._sessions.pop(session_id, None)
                    self._session_locks.pop(session_id, None)
        if evicted:
            logger.info("Evicted %d idle workspace dfs (idle > %ds)", evicted, self._cfg.idle_ttl_seconds)
        return evicted

    # ----- introspection -----

    def total_bytes(self) -> int:
        # Read-only snapshot; concurrent writes may make this slightly stale.
        return self._total_bytes_locked()

    def _total_bytes_locked(self) -> int:
        return sum(e.bytes_estimate for entries in self._sessions.values() for e in entries.values())

    def stats(self) -> dict[str, Any]:
        sessions = len(self._sessions)
        dfs = sum(len(entries) for entries in self._sessions.values())
        return {
            "sessions": sessions,
            "dfs": dfs,
            "bytes": self._total_bytes_locked(),
            "max_sessions": self._cfg.max_sessions,
            "max_dfs_per_session": self._cfg.max_dfs_per_session,
            "max_total_bytes": self._cfg.max_total_bytes,
        }

    def add_eviction_callback(self, cb: Callable[[str], None]) -> None:
        """Register a callback fired with the eviction reason ("ttl" / "cap")."""
        self._eviction_callbacks.append(cb)

    # ----- background sweeper -----

    async def run_sweeper(self) -> None:
        """Periodically evict idle entries. Cancel-safe."""
        interval = self._cfg.sweeper_interval_seconds
        try:
            while True:
                await asyncio.sleep(interval)
                try:
                    await self.evict_idle()
                except Exception:
                    logger.exception("workspace sweeper iteration failed")
        except asyncio.CancelledError:
            logger.info("workspace sweeper cancelled")
            raise
