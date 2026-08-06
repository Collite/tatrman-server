# SPDX-License-Identifier: Apache-2.0
"""What a run needs injected. Ports as Protocols, so a test can hand in a recorded
core and the graph cannot tell the difference (and, more importantly, so nothing in
the graph reaches for a channel it was not given).
"""

from __future__ import annotations

import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable

from golem_py.ladder import LadderConfig, load_default
from golem_py.settings import GolemSettings
from golem_py.snapshots import InMemorySnapshotStore, SnapshotStore
from golem_py.state import Binding, GapRecord, Hypothesis, ResolutionState, RungLogEntry


@runtime_checkable
class CorePort(Protocol):
    """`resolve.bind:v1` — text in, lattice out. ZERO LLM below this line."""

    async def resolve(
        self,
        *,
        question: str,
        locale: str = "cs",
        conversation_id: str = "",
        caller_subject: str = "",
    ) -> ResolutionState: ...


@dataclass
class GateResult:
    """What `resolve.gate:v1` gave back. `outcomes` is per-hypothesis, because a
    proposer that cannot learn which of its guesses were wrong will keep making them."""

    gated_bindings: list[Binding] = field(default_factory=list)
    updated_gaps: list[GapRecord] = field(default_factory=list)
    rung_log_entry: RungLogEntry | None = None
    outcomes: list[HypothesisOutcome] = field(default_factory=list)


@dataclass
class HypothesisOutcome:
    hypothesis: Hypothesis
    accepted: bool
    binding: Binding | None = None
    # NO_CANDIDATE | WEAK | AMBIGUOUS | NO_SPAN | REF_MISMATCH | LOOKUP_FAILED
    reason: str = ""

    def is_infrastructure_failure(self) -> bool:
        """`LOOKUP_FAILED` is not a verdict about the hypothesis — the matcher could
        not be ASKED. Retrying is safe (`Gate` is stateless/idempotent), and treating
        it as NO_CANDIDATE would teach a proposer that a correct guess does not exist.
        """
        return self.reason == "LOOKUP_FAILED"


@runtime_checkable
class GatePort(Protocol):
    """`resolve.gate:v1` — the ONLY way a hypothesis can become a binding (RV-7)."""

    async def gate(
        self, *, lattice: ResolutionState, hypotheses: Sequence[Hypothesis]
    ) -> GateResult: ...


@dataclass
class Deps:
    """Injected per run.

    `gate` is optional at construction but not at RESUME: a pin becomes a binding only
    by surviving `resolve.gate:v1` (RV-7), so a resume without one raises rather than
    honouring the pin locally.
    """

    core: CorePort
    gate: GatePort | None = None
    ladder: LadderConfig = field(default_factory=load_default)
    settings: GolemSettings = field(default_factory=GolemSettings)
    snapshots: SnapshotStore = field(default_factory=InMemorySnapshotStore)
    clock: Callable[[], float] = time.monotonic
