# SPDX-License-Identifier: Apache-2.0
"""P4.2·T2/T3 — the snapshot store: immutability, TTL, and the sweep.

The store is the half of the pause that is OURS. Its one load-bearing property is that
nothing is ever updated in place, because that is what makes an at-least-once resume
safe rather than merely usually-safe.
"""

from __future__ import annotations

import pytest

from golem_py.errors import ConfigInvalid, SnapshotExpired, SnapshotNotFound
from golem_py.settings import GolemSettings
from golem_py.snapshots import (
    FileSnapshotStore,
    InMemorySnapshotStore,
    build_store,
    snapshot_id,
)
from golem_py.state import Pin, ResolutionState
from tests.helpers import g1_subject_gap


def _state(**kwargs: object) -> ResolutionState:
    state = g1_subject_gap()
    state.conversation_id = "c-1"
    state.turn_id = "t-1"
    for key, value in kwargs.items():
        setattr(state, key, value)
    return state


# ------------------------------------------------------------------- the interface


@pytest.mark.parametrize("kind", ["memory", "file"])
def test_a_snapshot_round_trips_whole(kind: str, tmp_path) -> None:  # type: ignore[no-untyped-def]
    store = InMemorySnapshotStore() if kind == "memory" else FileSnapshotStore(tmp_path)
    state = _state(llm_invocations=1, hitl_rounds=1)

    key = store.put(state)

    assert store.get(key) == state


def test_the_key_is_the_conversation_and_turn() -> None:
    store = InMemorySnapshotStore()

    assert store.put(_state()) == snapshot_id("c-1", "t-1")
    # A different TURN of the same conversation is a different snapshot — the whole
    # point of the key: one paused turn must not overwrite another.
    assert store.put(_state(turn_id="t-2")) != snapshot_id("c-1", "t-1")


def test_a_stored_snapshot_is_never_mutated_by_a_later_put() -> None:
    """Immutability by construction (T2). If a resume could see state a previous
    delivery had written back, replaying an at-least-once delivery would compound
    instead of repeating."""
    store = InMemorySnapshotStore()
    first = _state()
    key = store.put(first)

    first.hitl_rounds = 99  # mutate the caller's copy after storing
    assert store.get(key).hitl_rounds == 0

    fetched = store.get(key)
    fetched.llm_invocations = 42  # mutate what we were handed
    assert store.get(key).llm_invocations == 0


def test_a_missing_snapshot_is_a_typed_error_not_a_none() -> None:
    with pytest.raises(SnapshotNotFound):
        InMemorySnapshotStore().get("snap-nope")


# -------------------------------------------------------------------------- TTL


def test_an_expired_snapshot_names_the_ttl_contract() -> None:
    now = [1000.0]
    store = InMemorySnapshotStore(ttl_s=60, clock=lambda: now[0])
    key = store.put(_state())

    now[0] += 61
    with pytest.raises(SnapshotExpired) as exc:
        store.get(key)
    assert exc.value.ttl_s == 60
    assert "resume token" in str(exc.value)


def test_the_sweep_removes_only_the_expired(tmp_path) -> None:  # type: ignore[no-untyped-def]
    now = [1000.0]
    store = FileSnapshotStore(tmp_path, ttl_s=60, clock=lambda: now[0])
    old = store.put(_state())
    now[0] += 61
    fresh = store.put(_state(turn_id="t-2"))
    # `put` writes with the real mtime, so age the old file explicitly.
    import os

    os.utime(tmp_path / f"{old}.json", (now[0] - 100, now[0] - 100))
    store.clock = lambda: now[0]

    assert store.sweep() == 1
    with pytest.raises(SnapshotNotFound):
        store.get(old)
    assert store.get(fresh)


def test_a_snapshot_survives_a_process_restart(tmp_path) -> None:  # type: ignore[no-untyped-def]
    """The file store's reason to exist: a paused conversation outlives the process
    that paused it, which is what "the OS single-node posture" has to mean."""
    key = FileSnapshotStore(tmp_path).put(_state(pin=Pin(option_id="x")))

    reopened = FileSnapshotStore(tmp_path)  # a "new process"

    assert reopened.get(key).pin is not None


def test_build_store_picks_the_file_store_only_when_a_directory_is_given(tmp_path) -> None:  # type: ignore[no-untyped-def]
    assert isinstance(build_store(None, ttl_s=60), InMemorySnapshotStore)
    assert isinstance(build_store(tmp_path, ttl_s=60), FileSnapshotStore)


# ------------------------------------------------------ the TTL inequality (T3)


def test_a_ttl_shorter_than_the_cores_token_refuses_to_start() -> None:
    """⚑ "The likely first production bug", named in advance and made unshippable.

    If our TTL is shorter than the window the core still honours, a user answering in
    good time is told their question expired — a self-inflicted failure that looks like
    a core bug and only shows up under load.
    """
    with pytest.raises(ValueError, match="shorter than the core"):
        GolemSettings(snapshot_ttl_s=600, core_token_max_age_s=3600)


def test_the_defaults_satisfy_the_inequality() -> None:
    settings = GolemSettings()
    assert settings.snapshot_ttl_s >= settings.core_token_max_age_s == 3600


def test_a_longer_snapshot_ttl_is_fine() -> None:
    """Only one direction is a bug. Holding state longer than the token is live merely
    means we can still explain what the expired token was about."""
    assert GolemSettings(snapshot_ttl_s=7200, core_token_max_age_s=3600).snapshot_ttl_s == 7200


def test_env_config_failures_are_reported_as_config_errors(monkeypatch) -> None:  # type: ignore[no-untyped-def]
    monkeypatch.setenv("GOLEM_SNAPSHOT_TTL_S", "60")
    monkeypatch.setenv("GOLEM_CORE_TOKEN_MAX_AGE_S", "3600")

    with pytest.raises(ConfigInvalid):
        GolemSettings.from_env()
