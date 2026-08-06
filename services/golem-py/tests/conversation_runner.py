# SPDX-License-Identifier: Apache-2.0
"""Drive a conversation fixture through the service (P4.2·T6; graduates at P4.4).

The fixtures speak the `calls:` vocabulary from
`services/resolver/src/test/resources/conformance/calls/SCHEMA.md`, extended with two
turn tools the door does not have (`golem.turn:v1` / `golem.resume:v1`) and with the
`core:`/`gate:` blocks that say what the recorded services answer with. A turn names a
LATTICE by golden id (`"lattice": "h2-cs"`) exactly the way the P2 gate fixtures name
one — no lattice is duplicated into this tree.

⚑ Whether conversation fixtures extend that SCHEMA or get a sibling one is P4.4·T1's
decision to record. They are authored in its vocabulary from the start so that the
decision is about where the file LIVES, not about rewriting it.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from golem_py.deps import Deps, GateResult, HypothesisOutcome
from golem_py.graph import run_turn
from golem_py.ladder import load_default
from golem_py.outputs import TurnOutput
from golem_py.resume import resume_turn
from golem_py.snapshots import InMemorySnapshotStore
from golem_py.state import (
    Binding,
    Disposition,
    EvidenceClass,
    GapKind,
    GapRecord,
    Hypothesis,
    Pin,
    ResolutionState,
    RungLogEntry,
    SignedOption,
    SourceTag,
    Span,
    TargetClass,
)
from tests.helpers import RecordedCore, RecordedGate, fixture_library
from tests.lattice_fixtures import lattice_golden

FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "conversations"


def load_fixture(name: str) -> dict[str, Any]:
    data: dict[str, Any] = json.loads((FIXTURE_DIR / f"{name}.json").read_text(encoding="utf-8"))
    return data


def _span(raw: dict[str, Any]) -> Span:
    return Span(start=raw.get("start", 0), end=raw.get("end", 0), text=raw.get("text", ""))


def _strip(value: str, prefix: str) -> str:
    return value.removeprefix(prefix)


def _binding(raw: dict[str, Any]) -> Binding:
    """Fixture JSON → a Binding. Allowed here for the same reason the wire mapper is:
    this IS a recorded gate response, standing in for one the core produced."""
    return Binding(
        ref=raw["ref"],
        target_class=TargetClass(_strip(raw["target_class"], "TARGET_CLASS_")),
        evidence_class=EvidenceClass(_strip(raw["evidence_class"], "EVIDENCE_CLASS_")),
        source=SourceTag(_strip(raw["source"], "SOURCE_TAG_")),
        in_class_score=raw.get("in_class_score", 0.0),
    )


def _gap(raw: dict[str, Any]) -> GapRecord:
    return GapRecord(
        span=_span(raw.get("span", {})),
        kind=GapKind(_strip(raw["kind"], "GAP_KIND_")),
        value_id=raw.get("value_id", ""),
        mention_id=raw.get("mention_id", ""),
        disposition=Disposition(
            _strip(raw.get("disposition", "DISPOSITION_UNRESOLVED"), "DISPOSITION_")
        ),
    )


def build_core(turn: dict[str, Any]) -> RecordedCore:
    spec = turn["core"]
    lattice = lattice_golden(spec["lattice"])
    lattice.resume_token = spec.get("resume_token", "")
    lattice.signed_options = [
        SignedOption(id=o["id"], label=o.get("label", ""), ref=o.get("ref", ""))
        for o in spec.get("options", [])
    ]
    return RecordedCore(lattice)


def build_gate(turn: dict[str, Any]) -> RecordedGate:
    spec = turn.get("gate")
    if spec is None:
        return RecordedGate()
    outcomes = [
        HypothesisOutcome(
            hypothesis=Hypothesis(
                span=_span(o["hypothesis"].get("span", {})),
                ref=o["hypothesis"].get("ref", ""),
                proposing_rung=o["hypothesis"].get("proposing_rung", ""),
            ),
            accepted=o["accepted"],
            binding=_binding(o["binding"]) if o.get("accepted") else None,
            reason=o.get("reason", ""),
        )
        for o in spec.get("outcomes", [])
    ]
    entry = spec.get("rung_log_entry")
    return RecordedGate(
        GateResult(
            gated_bindings=[o.binding for o in outcomes if o.binding is not None],
            updated_gaps=[_gap(g) for g in spec.get("updated_gaps", [])],
            rung_log_entry=RungLogEntry(**entry) if entry else None,
            outcomes=outcomes,
        )
    )


class ConversationRun:
    """One fixture, driven end to end, keeping every turn's output for assertions."""

    def __init__(self, fixture: dict[str, Any]):
        self.fixture = fixture
        self.outputs: list[TurnOutput] = []
        self.core: RecordedCore | None = None
        self.gate: RecordedGate | None = None
        self.deps: Deps | None = None
        self.last_snapshot_id = ""
        # Gate calls counted PER TURN. A replayed resume legitimately re-gates — `Gate`
        # is stateless and idempotent by contract, which is precisely what makes an
        # at-least-once redelivery safe — so a cumulative count would read as a bug.
        self.gate_calls: list[int] = []

    async def run(self) -> list[TurnOutput]:
        for index, turn in enumerate(self.fixture["turns"]):
            tool = turn["tool"]
            if tool == "golem.turn:v1":
                await self._turn(turn)
            elif tool == "golem.resume:v1":
                await self._resume(turn, index)
            else:  # pragma: no cover - fixture authoring error
                raise AssertionError(f"unknown turn tool {tool!r}")
        return self.outputs

    async def _turn(self, turn: dict[str, Any]) -> None:
        self.core = build_core(turn)
        self.gate = build_gate(turn)
        self.deps = Deps(
            core=self.core,
            gate=self.gate,
            ladder=load_default(),
            snapshots=InMemorySnapshotStore(),
            skills=fixture_library(),
            clock=lambda: 1000.0,  # frozen: a byte-identical replay must not race a clock
        )
        args = turn["args"]
        state = ResolutionState(
            question=args["text"],
            locale=args.get("locale", "cs"),
            conversation_id=args.get("conversation_id", ""),
            turn_id=args.get("turn_id", ""),
            caller_subject=args.get("caller_subject", ""),
            profile=args.get("profile", "CHAT_QUICK"),
        )
        before = self.gate.calls
        output = await run_turn(state, self.deps)
        self.outputs.append(output)
        self.gate_calls.append(self.gate.calls - before)
        self.last_snapshot_id = getattr(output, "snapshot_id", "")

    async def _resume(self, turn: dict[str, Any], index: int) -> None:
        assert self.deps is not None, "a resume turn must follow a turn"
        if "gate" in turn:
            self.gate = build_gate(turn)
            self.deps.gate = self.gate
        pin_spec = turn.get("pin", {})
        pin = Pin(
            option_id=pin_spec.get("option_id", ""),
            free_text=pin_spec.get("free_text", ""),
            escape=pin_spec.get("escape", False),
        )
        assert self.gate is not None
        before = self.gate.calls
        output = await resume_turn(
            self.last_snapshot_id,
            pin,
            caller_subject=turn.get("caller_subject", ""),
            deps=self.deps,
        )
        self.outputs.append(output)
        self.gate_calls.append(self.gate.calls - before)


async def drive(name: str) -> ConversationRun:
    run = ConversationRun(load_fixture(name))
    await run.run()
    return run
