# SPDX-License-Identifier: Apache-2.0
"""Profile accounting — the three budgets that bound the RV-11 loop (contracts §3).

Three, not one, and they are counted separately on purpose:

* **LLM invocations** (`max_llm_invocations`) — what stops a ladder from grinding.
  Lookup rounds are NOT counted (RV-33: `lookup` is deterministic), which is the whole
  reason the rung vocabulary distinguishes it.
* **Ladder wall clock** (`ladder_budget_ms`) — what bounds the deterministic rounds,
  which have no invocation count to bound them.
* **HITL rounds** (`hitl_rounds`) — ONE pool per conversation (RV-17), shared with any
  delegated plugin in the platform Golem. The counter rides the SNAPSHOT, so a
  replayed resume cannot double-spend it (P4.2 T5).

The clock is injectable so a test can prove the wall-clock branch without sleeping —
a budget that can only be observed by waiting is a budget nobody tests.
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass, field

from golem_py.ladder import DETERMINISTIC_RUNGS, LadderConfig, Profile
from golem_py.state import ResolutionState

Clock = Callable[[], float]


@dataclass
class Budgets:
    """Turn-scoped accounting. Counters live on the STATE (so they survive a pause);
    only the turn's start instant lives here."""

    profile: Profile
    clock: Clock = time.monotonic
    started_at: float = field(default=0.0)

    def __post_init__(self) -> None:
        if not self.started_at:
            self.started_at = self.clock()

    @classmethod
    def for_state(
        cls, state: ResolutionState, ladder: LadderConfig, clock: Clock = time.monotonic
    ) -> Budgets:
        return cls(profile=ladder.profile(state.profile), clock=clock)

    # ------------------------------------------------------------------ queries

    def elapsed_ms(self) -> int:
        return int((self.clock() - self.started_at) * 1000)

    def wall_clock_left(self) -> bool:
        return self.elapsed_ms() < self.profile.ladder_budget_ms

    def llm_left(self, state: ResolutionState) -> bool:
        return state.llm_invocations < self.profile.max_llm_invocations

    def hitl_left(self, state: ResolutionState) -> bool:
        return state.hitl_rounds < self.profile.hitl_rounds

    def can_run(self, rung: str, state: ResolutionState) -> bool:
        """A rung is runnable if the wall clock allows it and — for the LLM rungs
        only — the invocation budget does."""
        if not self.wall_clock_left():
            return False
        if rung in DETERMINISTIC_RUNGS:
            return True
        return self.llm_left(state)

    def runnable(self, rungs: list[str], state: ResolutionState) -> list[str]:
        return [r for r in rungs if self.can_run(r, state)]
