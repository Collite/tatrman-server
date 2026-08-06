# SPDX-License-Identifier: Apache-2.0
"""Resuming a paused turn (P4.2).

    turn 1:  run_turn(...)          -> Ask{resume_token, snapshot_id, options[]}
    turn 2:  resume_turn(snapshot_id, pin, subject) -> Answer | Ask | RefusalWithGaps

Three properties this module exists to hold, each of which is a bug if it slips:

1. **Resume from the SNAPSHOT, never from live state.** At-least-once delivery is the
   norm, so the same resume WILL arrive twice; reading immutable bytes each time is
   what makes the second delivery harmless. Nothing here writes back to the store.
2. **The same OBO identity that asked.** The core signs the subject into the resume
   token and re-checks it (RG-P6 review C), so a mismatched resume is refused there
   anyway — refusing here too is defence in depth, and it means we never ship a round
   trip whose only possible outcome is a rejection.
3. **The ask budget rides the snapshot.** It was spent when the ask was emitted, so a
   replayed resume cannot buy a second question: the stored state already counts it.
"""

from __future__ import annotations

from golem_py.deps import Deps
from golem_py.errors import IdentitySubjectMismatch
from golem_py.graph import run_turn
from golem_py.observability import log_node
from golem_py.outputs import TurnOutput
from golem_py.state import Pin, ResolutionState


def load_snapshot(snapshot_id: str, *, caller_subject: str, deps: Deps) -> ResolutionState:
    """Fetch and identity-check. Raises `SnapshotNotFound` / `SnapshotExpired` /
    `IdentitySubjectMismatch` — all typed, all naming the contract they enforce."""
    state = deps.snapshots.get(snapshot_id)
    if state.caller_subject != caller_subject:
        raise IdentitySubjectMismatch(state.caller_subject, caller_subject)
    return state


async def resume_turn(
    snapshot_id: str,
    pin: Pin,
    *,
    caller_subject: str = "",
    deps: Deps,
) -> TurnOutput:
    """Deliver the user's answer to a paused turn.

    Idempotent by construction: the snapshot is immutable, the copy is fresh, and the
    graph is deterministic given the same recorded core/gate. Delivering the same
    resume twice yields the same output.
    """
    stored = load_snapshot(snapshot_id, caller_subject=caller_subject, deps=deps)
    # A COPY. The store's document must outlive this run unchanged, or the second
    # delivery of an at-least-once resume would read state the first one had mutated.
    state = stored.model_copy(deep=True)
    state.pin = pin
    log_node(state, "resume", snapshot=snapshot_id, option=pin.option_id, escape=pin.escape)
    return await run_turn(state, deps)
