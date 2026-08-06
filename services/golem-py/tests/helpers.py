# SPDX-License-Identifier: Apache-2.0
"""Test-only helpers: a recorded core, a traced run, and the hero lattices.

The lattices here are SYNTHETIC and hand-built from `contracts.md` §1 — they are the
shapes the door emits, not recordings of it. The recorded/live distinction matters for
the conformance tier (P4.4), which drives the same heroes against a real core; these
exist so the loop's control flow can be tested without one.
"""

from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path

from pydantic_graph import EndMarker

from golem_py.deps import Deps, GateResult
from golem_py.graph import graph
from golem_py.ladder import LadderConfig
from golem_py.outputs import TurnOutput
from golem_py.skills import LayeredSkillLibrary, SkillLibrary
from golem_py.state import (
    Binding,
    EvidenceClass,
    FrameRole,
    GapKind,
    GapRecord,
    Hypothesis,
    Mention,
    ResolutionState,
    SourceTag,
    Span,
    TargetClass,
)


class RecordedCore:
    """Anything with `.resolve(...)` returning a `ResolutionState` is a core."""

    def __init__(self, lattice: ResolutionState):
        self._lattice = lattice
        self.calls = 0
        self.last_kwargs: dict[str, object] = {}

    async def resolve(
        self,
        *,
        question: str,
        locale: str = "cs",
        conversation_id: str = "",
        caller_subject: str = "",
    ) -> ResolutionState:
        self.calls += 1
        self.last_kwargs = {
            "question": question,
            "locale": locale,
            "conversation_id": conversation_id,
            "caller_subject": caller_subject,
        }
        return self._lattice.model_copy(deep=True)


class RecordedGate:
    """A gate that replays a canned `GateResult`, recording what it was asked."""

    def __init__(self, result: GateResult | None = None):
        self.result = result or GateResult()
        self.calls = 0
        self.last_hypotheses: list[Hypothesis] = []

    async def gate(
        self, *, lattice: ResolutionState, hypotheses: Sequence[Hypothesis]
    ) -> GateResult:
        self.calls += 1
        self.last_hypotheses = list(hypotheses)
        return self.result


async def run_traced(state: ResolutionState, deps: Deps) -> tuple[TurnOutput, list[str]]:
    """Run a turn and report which nodes actually EXECUTED, in order.

    This is how the fan-out trap is caught behaviourally as well as structurally: a
    silent fan-out shows up here as two terminal nodes in one run.
    """
    visited: list[str] = []
    async with graph().iter(state=state, deps=deps) as run:
        async for item in run:
            if isinstance(item, EndMarker):
                continue
            visited.extend(str(getattr(task, "node_id", task)) for task in item)
    return run.output, visited


FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures"


def fixture_library() -> LayeredSkillLibrary:
    """The repo-owned operator library: five stdlib-shaped bodies, checksummed.

    A FIXTURE, deliberately — hartland's real archive lives in a sibling repo CI cannot
    see, so the suite owns a small library of its own and
    `test_skills.py::test_the_real_hartland_archive_parses` pins the FORMAT against the
    real 8 845-byte artifact whenever that repo is on disk.
    """
    return LayeredSkillLibrary(
        [
            SkillLibrary.from_json(
                (FIXTURE_DIR / "lexicon" / "operator-library.json").read_text("utf-8")
            )
        ]
    )


def deps(
    core: object,
    ladder: LadderConfig | None = None,
    gate: object | None = None,
    skills: LayeredSkillLibrary | None = None,
    query: object | None = None,
) -> Deps:
    from golem_py.ladder import load_default

    return Deps(
        core=core,  # type: ignore[arg-type]
        gate=gate,  # type: ignore[arg-type]
        ladder=ladder or load_default(),
        skills=skills if skills is not None else fixture_library(),
        query=query,  # type: ignore[arg-type]
    )


# ------------------------------------------------------------------ hero lattices


def h1_lattice() -> ResolutionState:
    """H1 — everything binds, no gaps. "Zobraz tržby za rok 2025 na účtu 501001"
    reduced to what the loop reads: bound mentions, zero gaps."""
    return ResolutionState(
        mentions=[
            Mention(
                id="m1",
                span=Span(start=7, end=12, text="tržby"),
                lemma="tržba",
                # The measure-as-subject class: 32 of 137 corpus mentions carry both.
                frame_roles=[FrameRole.SUBJECT, FrameRole.MEASURE],
                bindings=[
                    Binding(
                        ref="md.measure.revenue",
                        target_class=TargetClass.MODEL_OBJECT,
                        evidence_class=EvidenceClass.DECLARED_ALIAS,
                        source=SourceTag.DECLARED,
                        in_class_score=1.0,
                    )
                ],
            ),
            Mention(
                id="m2",
                span=Span(start=0, end=6, text="Zobraz"),
                lemma="zobrazit",
                bindings=[
                    Binding(
                        ref="op:show",
                        target_class=TargetClass.OPERATOR,
                        evidence_class=EvidenceClass.EXACT,
                        source=SourceTag.DECLARED,
                        in_class_score=1.0,
                    )
                ],
            ),
        ],
        gaps=[],
        trace_id="t-h1",
    )


def g1_subject_gap() -> ResolutionState:
    """H2's turn 1 — an unknown word in SUBJECT position: the load-bearing G1 that
    makes the Golem ask instead of guessing."""
    return ResolutionState(
        mentions=[
            # As `h2-cs` has it, the question still names an ACTION even while its
            # subject is unknown — without the operator the composed question would have
            # nothing this Golem is taught to do, and the answer after the pin would be a
            # refusal for the wrong reason.
            Mention(
                id="m0",
                span=Span(start=0, end=6, text="Zobraz"),
                lemma="zobrazit",
                bindings=[
                    Binding(
                        ref="op:show",
                        target_class=TargetClass.OPERATOR,
                        evidence_class=EvidenceClass.EXACT,
                        source=SourceTag.DECLARED,
                        in_class_score=1.0,
                    )
                ],
            ),
            Mention(
                id="m1",
                span=Span(start=18, end=34, text="čerpacích stanic"),
                # As `h2-cs` has it: the mention carries the role even with no binding —
                # the role is derived from the parse, not from what bound.
                frame_roles=[FrameRole.SUBJECT],
            ),
        ],
        gaps=[
            GapRecord(
                span=Span(start=18, end=34, text="čerpacích stanic"),
                kind=GapKind.G1_UNBOUND,
                frame_roles=[FrameRole.SUBJECT],
                mention_id="m1",
            )
        ],
        trace_id="t-g1",
        resume_token="signed-by-the-core",
    )
