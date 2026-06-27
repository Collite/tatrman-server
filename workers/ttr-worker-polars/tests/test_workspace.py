"""Phase 2.4 §E — workspace store: put/get, TTL, caps."""

from __future__ import annotations

import polars as pl
import pytest

from workers_steropes.config import WorkspaceConfig
from workers_steropes.workspace import WorkspaceCapExceeded, WorkspaceStore


def _cfg(**overrides) -> WorkspaceConfig:
    base = dict(
        max_sessions=10,
        max_dfs_per_session=3,
        max_bytes_per_df=10_000_000,
        max_total_bytes=20_000_000,
        idle_ttl_seconds=60,
        sweeper_interval_seconds=10,
    )
    base.update(overrides)
    return WorkspaceConfig(**base)


def _df() -> pl.DataFrame:
    return pl.DataFrame({"id": [1, 2, 3], "name": ["a", "b", "c"]})


@pytest.mark.asyncio
async def test_put_then_get_round_trips():
    store = WorkspaceStore(_cfg())
    await store.put("s1", "q1", _df())
    entry = await store.get("s1", "q1")
    assert entry is not None
    assert entry.df.shape == (3, 2)
    assert entry.bytes_estimate > 0


@pytest.mark.asyncio
async def test_get_unknown_returns_none():
    store = WorkspaceStore(_cfg())
    assert await store.get("nope", "q1") is None
    await store.put("s1", "q1", _df())
    assert await store.get("s1", "nope") is None


@pytest.mark.asyncio
async def test_max_dfs_per_session_caps_third_distinct_put():
    store = WorkspaceStore(_cfg(max_dfs_per_session=2))
    await store.put("s1", "q1", _df())
    await store.put("s1", "q2", _df())
    with pytest.raises(WorkspaceCapExceeded):
        await store.put("s1", "q3", _df())


@pytest.mark.asyncio
async def test_overwrite_same_name_does_not_count_against_cap():
    store = WorkspaceStore(_cfg(max_dfs_per_session=1))
    await store.put("s1", "q1", _df())
    # Same name overwrites — must not trip the cap.
    await store.put("s1", "q1", _df())


@pytest.mark.asyncio
async def test_max_bytes_per_df_rejects_oversize():
    store = WorkspaceStore(_cfg(max_bytes_per_df=10))  # ridiculously small
    with pytest.raises(WorkspaceCapExceeded):
        await store.put("s1", "q1", _df())


@pytest.mark.asyncio
async def test_max_sessions_rejects_new_session_after_cap():
    store = WorkspaceStore(_cfg(max_sessions=2))
    await store.put("s1", "q1", _df())
    await store.put("s2", "q1", _df())
    with pytest.raises(WorkspaceCapExceeded):
        await store.put("s3", "q1", _df())


@pytest.mark.asyncio
async def test_evict_idle_removes_stale_entries():
    clock_value = [1000.0]
    store = WorkspaceStore(_cfg(idle_ttl_seconds=60), clock=lambda: clock_value[0])
    await store.put("s1", "q1", _df())
    # Advance clock past TTL.
    clock_value[0] = 1100.0
    evicted = await store.evict_idle()
    assert evicted == 1
    assert await store.get("s1", "q1") is None


@pytest.mark.asyncio
async def test_evict_idle_keeps_recently_accessed_entries():
    clock_value = [1000.0]
    store = WorkspaceStore(_cfg(idle_ttl_seconds=60), clock=lambda: clock_value[0])
    await store.put("s1", "q1", _df())
    # Touch it just before TTL would expire.
    clock_value[0] = 1050.0
    await store.get("s1", "q1")  # bumps last_accessed_at
    clock_value[0] = 1100.0  # >60s past *put* but only 50s past last_accessed
    assert await store.evict_idle() == 0
    assert await store.get("s1", "q1") is not None


@pytest.mark.asyncio
async def test_evict_session_removes_all_entries_for_that_session():
    store = WorkspaceStore(_cfg())
    await store.put("s1", "q1", _df())
    await store.put("s1", "q2", _df())
    await store.put("s2", "q1", _df())
    removed = await store.evict_session("s1")
    assert removed == 2
    assert await store.get("s1", "q1") is None
    assert await store.get("s2", "q1") is not None


@pytest.mark.asyncio
async def test_stats_surfaces_session_and_df_counts():
    store = WorkspaceStore(_cfg())
    await store.put("s1", "q1", _df())
    await store.put("s1", "q2", _df())
    await store.put("s2", "q1", _df())
    stats = store.stats()
    assert stats["sessions"] == 2
    assert stats["dfs"] == 3
    assert stats["bytes"] > 0


@pytest.mark.asyncio
async def test_eviction_callback_fires_on_ttl_eviction():
    clock_value = [1000.0]
    store = WorkspaceStore(_cfg(idle_ttl_seconds=60), clock=lambda: clock_value[0])
    fired: list[str] = []
    store.add_eviction_callback(lambda reason: fired.append(reason))
    await store.put("s1", "q1", _df())
    clock_value[0] = 1100.0
    await store.evict_idle()
    assert fired == ["ttl"]
