# SPDX-License-Identifier: Apache-2.0
"""P4.2·T1/T4/T5 — the pause and the resume, mechanics first.

RV-11's "a resume rejoins `assessGaps`" taken literally, on the P0-3·T5 ruling: **the
token stays the CORE's, the state stays OURS**. Two things, never merged.
"""

from __future__ import annotations

import pytest

from golem_py.deps import Deps, GateResult, HypothesisOutcome
from golem_py.errors import (
    GateUnavailable,
    IdentitySubjectMismatch,
    SnapshotExpired,
    SnapshotNotFound,
    UnknownOption,
)
from golem_py.graph import run_turn
from golem_py.outputs import Answer, Ask, RefusalWithGaps
from golem_py.resume import resume_turn
from golem_py.snapshots import InMemorySnapshotStore
from golem_py.state import (
    Binding,
    Disposition,
    EvidenceClass,
    Hypothesis,
    Pin,
    ResolutionState,
    SignedOption,
    SourceTag,
    Span,
    TargetClass,
)
from tests.helpers import (
    RecordedCore,
    RecordedGate,
    fixture_library,
    g1_subject_gap,
    run_traced,
)


def _asking_lattice() -> ResolutionState:
    """H2's turn 1: a load-bearing G1, plus the option set the core signed."""
    lattice = g1_subject_gap()
    lattice.signed_options = [
        SignedOption(
            id="opt-1",
            label="Customer category: Gas stations",
            ref="md.dimension.Customer.category",
        ),
        SignedOption(
            id="opt-2", label="Distribution centre", ref="md.dimension.DistributionCentre"
        ),
    ]
    return lattice


def _accepted_gate() -> RecordedGate:
    """A gate that accepts the pinned ref — the only route a pin has to a binding."""
    hypothesis = Hypothesis(
        span=Span(start=18, end=34, text="čerpacích stanic"),
        ref="md.dimension.Customer.category",
        proposing_rung="user",
    )
    binding = Binding(
        ref="md.dimension.Customer.category",
        target_class=TargetClass.MODEL_OBJECT,
        evidence_class=EvidenceClass.DECLARED_ALIAS,
        source=SourceTag.DECLARED,
        in_class_score=0.95,
    )
    return RecordedGate(
        GateResult(
            gated_bindings=[binding],
            updated_gaps=[],
            outcomes=[HypothesisOutcome(hypothesis=hypothesis, accepted=True, binding=binding)],
        )
    )


def _deps(core: object, gate: object | None = None, **kwargs: object) -> Deps:
    from golem_py.ladder import load_default

    return Deps(
        core=core,  # type: ignore[arg-type]
        gate=gate,  # type: ignore[arg-type]
        ladder=load_default(),
        snapshots=kwargs.pop("snapshots", InMemorySnapshotStore()),  # type: ignore[arg-type]
        skills=fixture_library(),
        **kwargs,  # type: ignore[arg-type]
    )


async def _pause(deps: Deps, subject: str = "user-a") -> Ask:
    state = ResolutionState(
        question="Zobraz prvních 10 čerpacích stanic",
        conversation_id="c-h2",
        turn_id="t-1",
        caller_subject=subject,
    )
    out = await run_turn(state, deps)
    assert isinstance(out, Ask)
    return out


# ------------------------------------------------------------------ the ask (T1·a)


@pytest.mark.asyncio
async def test_the_ask_carries_the_cores_token_and_our_snapshot_side_by_side() -> None:
    """P0-3·T5's ruling, made observable. The token is opaque bytes we store; the
    snapshot id is ours. Merging them — signing our own option set — would let the agent
    fabricate "the user chose X"."""
    deps = _deps(RecordedCore(_asking_lattice()))

    ask = await _pause(deps)

    assert ask.resume_token == "signed-by-the-core"  # the core's, untouched
    assert ask.snapshot_id.startswith("snap-")  # ours
    assert [o.id for o in ask.options] == ["opt-1", "opt-2"]
    assert ask.escape  # the escape hatch is always offered (RV-15)
    assert deps.snapshots.get(ask.snapshot_id).hitl_rounds == 1


@pytest.mark.asyncio
async def test_an_ask_with_no_signed_options_still_pauses_honestly() -> None:
    """A G1 the core never clarified has no option set. Offering none is honest —
    inventing options would mean the agent proposing refs on its own authority."""
    lattice = g1_subject_gap()
    lattice.signed_options = []

    ask = await _pause(_deps(RecordedCore(lattice)))

    assert ask.options == []
    assert ask.question


# --------------------------------------------------------------- the resume (T1·b)


@pytest.mark.asyncio
async def test_a_pin_becomes_a_binding_only_through_the_gate() -> None:
    """RV-7 with the user in the loop. The user is the proposer MOST likely to name
    something the vocabulary does not have, so "the user said so" is precisely the
    authority that must not bypass the gate."""
    gate = _accepted_gate()
    deps = _deps(RecordedCore(_asking_lattice()), gate)
    ask = await _pause(deps)

    out = await resume_turn(
        ask.snapshot_id, Pin(option_id="opt-1"), caller_subject="user-a", deps=deps
    )

    assert isinstance(out, Answer)
    assert gate.calls == 1
    assert gate.last_hypotheses[0].ref == "md.dimension.Customer.category"
    # ⚑ `user`, deliberately outside the four-rung vocabulary: this proposal did not
    # come from a rung, and logging it as one would make the ladder's numbers lie.
    assert gate.last_hypotheses[0].proposing_rung == "user"
    subject = next(m for m in out.lattice.mentions if m.span.text == "čerpacích stanic")
    assert subject.bindings[0].ref == "md.dimension.Customer.category"


@pytest.mark.asyncio
async def test_the_resume_rejoins_assess_gaps_and_never_recalls_the_core() -> None:
    core = RecordedCore(_asking_lattice())
    deps = _deps(core, _accepted_gate())
    ask = await _pause(deps)
    assert core.calls == 1

    state = deps.snapshots.get(ask.snapshot_id)
    state.pin = Pin(option_id="opt-1")
    _, visited = await run_traced(state, deps)

    assert "call_core" not in visited
    assert core.calls == 1


@pytest.mark.asyncio
async def test_a_pin_naming_an_unsigned_option_is_refused() -> None:
    deps = _deps(RecordedCore(_asking_lattice()), _accepted_gate())
    ask = await _pause(deps)

    with pytest.raises(UnknownOption):
        await resume_turn(
            ask.snapshot_id, Pin(option_id="opt-99"), caller_subject="user-a", deps=deps
        )


@pytest.mark.asyncio
async def test_a_pin_with_no_gate_configured_raises_rather_than_binding_locally() -> None:
    deps = _deps(RecordedCore(_asking_lattice()))  # no gate
    ask = await _pause(deps)

    with pytest.raises(GateUnavailable):
        await resume_turn(
            ask.snapshot_id, Pin(option_id="opt-1"), caller_subject="user-a", deps=deps
        )


@pytest.mark.asyncio
async def test_the_escape_settles_the_gap_without_pretending_anything_bound() -> None:
    """ "None of these" is an ANSWER (RV-15): the question was asked and settled. It
    gates nothing, because it proposes nothing."""
    gate = _accepted_gate()
    deps = _deps(RecordedCore(_asking_lattice()), gate)
    ask = await _pause(deps)

    out = await resume_turn(
        ask.snapshot_id,
        Pin(escape=True, free_text="none of these"),
        caller_subject="user-a",
        deps=deps,
    )

    assert gate.calls == 0
    assert isinstance(out, (Answer, RefusalWithGaps))
    lattice = out.lattice
    assert lattice.gaps[0].disposition == Disposition.USER_CONFIRMED_UNKNOWN
    subject = next(m for m in lattice.mentions if m.span.text == "čerpacích stanic")
    assert subject.bindings == []  # nothing bound, and nothing pretended


# ------------------------------------------------------------ idempotency (T1·c)


@pytest.mark.asyncio
async def test_the_same_resume_delivered_twice_is_byte_identical() -> None:
    """At-least-once delivery is the norm, so this WILL happen. Resuming from the
    immutable snapshot rather than from mutated live state is what makes the second
    delivery harmless."""
    deps = _deps(RecordedCore(_asking_lattice()), _accepted_gate(), clock=lambda: 1000.0)
    ask = await _pause(deps)
    pin = Pin(option_id="opt-1")

    first = await resume_turn(ask.snapshot_id, pin, caller_subject="user-a", deps=deps)
    second = await resume_turn(ask.snapshot_id, pin, caller_subject="user-a", deps=deps)

    assert first.model_dump_json() == second.model_dump_json()


@pytest.mark.asyncio
async def test_a_replayed_resume_cannot_buy_a_second_ask() -> None:
    """T5: the ask budget rides the SNAPSHOT. It was spent when the ask was emitted, so
    a replay reads a state that already counts it — CHAT_QUICK's single round cannot be
    double-spent by a redelivery."""
    gate = RecordedGate(GateResult(updated_gaps=g1_subject_gap().gaps))  # the pin does not close it
    deps = _deps(RecordedCore(_asking_lattice()), gate)
    ask = await _pause(deps)

    again = await resume_turn(
        ask.snapshot_id, Pin(option_id="opt-1"), caller_subject="user-a", deps=deps
    )

    assert isinstance(again, RefusalWithGaps)  # not a second question
    assert again.lattice.hitl_rounds == 1


# ------------------------------------------------------ expiry + identity (T1·d, T4)


@pytest.mark.asyncio
async def test_a_missing_snapshot_is_a_typed_error_naming_the_contract() -> None:
    deps = _deps(RecordedCore(_asking_lattice()), _accepted_gate())

    with pytest.raises(SnapshotNotFound):
        await resume_turn("snap-gone", Pin(option_id="opt-1"), caller_subject="user-a", deps=deps)


@pytest.mark.asyncio
async def test_an_expired_snapshot_says_so_rather_than_stack_tracing() -> None:
    now = [1000.0]
    deps = _deps(
        RecordedCore(_asking_lattice()),
        _accepted_gate(),
        snapshots=InMemorySnapshotStore(ttl_s=60, clock=lambda: now[0]),
    )
    ask = await _pause(deps)
    now[0] += 61

    with pytest.raises(SnapshotExpired) as exc:
        await resume_turn(
            ask.snapshot_id, Pin(option_id="opt-1"), caller_subject="user-a", deps=deps
        )
    assert exc.value.ttl_s == 60


@pytest.mark.asyncio
async def test_a_resume_under_a_different_subject_is_refused_before_the_core_sees_it() -> None:
    """T4: the core re-checks the signed subject and would refuse anyway (RG-P6 review
    C). Refusing here is defence in depth — and it means we never ship a round trip
    whose only possible outcome is a rejection."""
    gate = _accepted_gate()
    deps = _deps(RecordedCore(_asking_lattice()), gate)
    ask = await _pause(deps, subject="user-a")

    with pytest.raises(IdentitySubjectMismatch):
        await resume_turn(
            ask.snapshot_id, Pin(option_id="opt-1"), caller_subject="user-b", deps=deps
        )
    assert gate.calls == 0  # nothing left the process


@pytest.mark.asyncio
async def test_the_subject_travels_to_the_core_on_the_first_call() -> None:
    core = RecordedCore(_asking_lattice())
    await _pause(_deps(core), subject="user-a")

    assert core.last_kwargs["caller_subject"] == "user-a"


# ------------------------------------------------------------- free text (T1·e)


@pytest.mark.asyncio
async def test_a_free_text_answer_re_resolves_instead_of_gating() -> None:
    """⚑ RULED at T1(e): the user was asked "what does X refer to", so a free-text
    answer REPLACES X in the question and the amended question is resolved
    deterministically — the same thing that would have happened had they typed it
    first. There is nothing to gate: they did not choose what we offered."""
    core = RecordedCore(_asking_lattice())
    gate = _accepted_gate()
    deps = _deps(core, gate)
    ask = await _pause(deps)

    _, visited = await run_traced(_pinned(deps, ask.snapshot_id, Pin(free_text="benzínky")), deps)

    assert "amend_question" in visited and "call_core" in visited
    assert "apply_pin" not in visited
    assert gate.calls == 0
    assert core.calls == 2
    assert core.last_kwargs["question"] == "Zobraz prvních 10 benzínky"


@pytest.mark.asyncio
async def test_free_text_is_appended_when_the_asked_span_is_not_in_the_question() -> None:
    """Losing what the user typed is the one outcome that is never right."""
    core = RecordedCore(_asking_lattice())
    deps = _deps(core, _accepted_gate())
    ask = await _pause(deps)
    state = _pinned(deps, ask.snapshot_id, Pin(free_text="benzínky"))
    state.question = "a question that no longer contains the span"

    await run_turn(state, deps)

    assert str(core.last_kwargs["question"]).endswith("benzínky")


def _pinned(deps: Deps, snapshot_id: str, pin: Pin) -> ResolutionState:
    state = deps.snapshots.get(snapshot_id)
    state.pin = pin
    return state
