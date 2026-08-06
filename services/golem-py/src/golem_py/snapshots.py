# SPDX-License-Identifier: Apache-2.0
"""The snapshot store — OUR half of the pause (P4.2·T2).

Two things travel out of an `ask`, and they are never merged (P0-3·T5):

* the **`resume_token`** is the CORE's. It is a signed option set (RS-26) — opaque
  bytes we store and hand back. Signing one agent-side would let the agent fabricate
  "the user chose X".
* the **snapshot** is OURS. The whole `ResolutionState` plus the loop counters, as one
  Pydantic JSON document, turn-local. The door has no business knowing it.

**Immutable by construction.** `put` mints a NEW snapshot every time and `get` returns a
fresh object parsed from stored bytes; nothing updates in place. That single property is
what makes an at-least-once resume safe: replaying the same delivery twice reads the same
bytes and produces the same answer, because there is no mutated live state for the second
delivery to land on.

⛑ **The key is per PAUSE, not per turn** — `(conversation_id, turn_id, subject, round)` —
and the round is what a review found missing. A turn may ask more than once
(`INVESTIGATION_DEEP` ships `hitl_rounds: 3`), and with a turn-scoped key the second ask
OVERWROTE the first ask's document. A redelivery of the first resume then read the state
the first delivery had produced and answered differently — the exact failure "immutable by
construction" is written to prevent, arriving through the key rather than through the
value. `CHAT_QUICK`'s single round is why no test saw it.

⚑ Recorded per T2: the platform-grade implementation is a Postgres JSON column, per the
kantheon persistence topology. It lands with a platform estate, not here — the OS Golem
is single-node by posture. The INTERFACE is the contract; the file store below is a real
implementation of it, not a placeholder, and it is what makes "a paused conversation
survives a process restart" true for an open deployment.
"""

from __future__ import annotations

import hashlib
import json
import time
from collections.abc import Callable
from pathlib import Path
from typing import Protocol, runtime_checkable

from golem_py.errors import SnapshotExpired, SnapshotNotFound
from golem_py.state import ResolutionState

Clock = Callable[[], float]


def snapshot_id(conversation_id: str, turn_id: str, subject: str = "", round_: int = 0) -> str:
    """Key = `(conversation_id, turn_id, round)`, with the SUBJECT folded in.

    Folding the subject into the id is defence in depth for T4: two conversations can
    share an id across tenants by accident, but they cannot share one under different
    subjects and still collide. The id is not a secret — the identity check is explicit
    in `resume.py` — but a derived key means a mistake there cannot silently read
    another principal's snapshot.

    ⛑ `round_` is the HITL round the pause belongs to. Without it a turn's second ask
    overwrites its first, and "never update in place" stops being true at the key while
    still looking true at the value (see the module docstring).
    """
    raw = f"{conversation_id}\x00{turn_id}\x00{subject}\x00{round_}"
    digest = hashlib.sha256(raw.encode()).hexdigest()
    return f"snap-{digest[:32]}"


@runtime_checkable
class SnapshotStore(Protocol):
    def put(self, state: ResolutionState) -> str: ...

    def get(self, snapshot_id: str) -> ResolutionState: ...

    def sweep(self) -> int: ...


class _Base:
    def __init__(self, ttl_s: int, clock: Clock = time.time):
        self.ttl_s = ttl_s
        self.clock = clock

    def _key(self, state: ResolutionState) -> str:
        return snapshot_id(
            state.conversation_id, state.turn_id, state.caller_subject, state.hitl_rounds
        )


class InMemorySnapshotStore(_Base):
    """For tests and the single-shot CLI. Same semantics as the file store."""

    def __init__(self, ttl_s: int = 3600, clock: Clock = time.time):
        super().__init__(ttl_s, clock)
        self._items: dict[str, tuple[float, str]] = {}

    def put(self, state: ResolutionState) -> str:
        key = self._key(state)
        # A NEW document each time — never an update of the one already there.
        self._items[key] = (self.clock(), state.model_dump_json())
        return key

    def get(self, snapshot_id: str) -> ResolutionState:
        item = self._items.get(snapshot_id)
        if item is None:
            raise SnapshotNotFound(snapshot_id)
        stored_at, payload = item
        age = self.clock() - stored_at
        if age > self.ttl_s:
            raise SnapshotExpired(snapshot_id, age, self.ttl_s)
        return ResolutionState.model_validate_json(payload)

    def sweep(self) -> int:
        now = self.clock()
        dead = [k for k, (ts, _) in self._items.items() if now - ts > self.ttl_s]
        for key in dead:
            del self._items[key]
        return len(dead)


class FileSnapshotStore(_Base):
    """One JSON document per snapshot, under `dir/`. The OS single-node posture.

    ⚑ Age is taken from the file's **mtime** rather than a field inside the document.
    That is fine at this grade and the reason is worth writing down: the two can only
    disagree if someone edits a snapshot by hand, and a hand-edited snapshot is already
    outside the contract. A stored timestamp would additionally have to be trusted, and
    it is the one field an attacker with write access would change first.
    """

    def __init__(self, directory: str | Path, ttl_s: int = 3600, clock: Clock = time.time):
        super().__init__(ttl_s, clock)
        self.dir = Path(directory)
        self.dir.mkdir(parents=True, exist_ok=True)

    def _path(self, key: str) -> Path:
        return self.dir / f"{key}.json"

    def put(self, state: ResolutionState) -> str:
        key = self._key(state)
        path = self._path(key)
        # Write-then-rename: a reader never sees a half-written snapshot, and a crash
        # mid-write leaves the PREVIOUS document intact rather than a truncated one.
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(state.model_dump_json(), encoding="utf-8")
        tmp.replace(path)
        return key

    def get(self, snapshot_id: str) -> ResolutionState:
        path = self._path(snapshot_id)
        if not path.exists():
            raise SnapshotNotFound(snapshot_id)
        age = self.clock() - path.stat().st_mtime
        if age > self.ttl_s:
            raise SnapshotExpired(snapshot_id, age, self.ttl_s)
        try:
            return ResolutionState.model_validate_json(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, ValueError) as exc:  # pragma: no cover - corruption
            raise SnapshotNotFound(snapshot_id) from exc

    def sweep(self) -> int:
        now = self.clock()
        swept = 0
        for path in self.dir.glob("snap-*.json"):
            if now - path.stat().st_mtime > self.ttl_s:
                path.unlink(missing_ok=True)
                swept += 1
        return swept


def build_store(
    directory: str | Path | None, ttl_s: int, clock: Clock = time.time
) -> SnapshotStore:
    if directory:
        return FileSnapshotStore(directory, ttl_s=ttl_s, clock=clock)
    return InMemorySnapshotStore(ttl_s=ttl_s, clock=clock)
