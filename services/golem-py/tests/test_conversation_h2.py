# SPDX-License-Identifier: Apache-2.0
"""P4.2·T6 — the two-turn conversation, driven from the shared fixture.

H2's shape end to end: an unknown SUBJECT the zero-rung ladder cannot help with becomes
ONE question; the pin comes back through `resolve.gate:v1`; the turn answers, carrying
the gap it could not close rather than pretending it did.

The fixture is the deliverable here as much as the assertions are — P4.4 graduates it
into the shared conformance-conversation suite, and the Kotlin Golem (RV-P5) must pass
the SAME file. So every expectation below is stated in lattice/turn vocabulary, never in
golem-py internals.
"""

from __future__ import annotations

import pytest

from golem_py.outputs import Answer, Ask
from golem_py.state import Disposition, GapKind
from tests.conversation_runner import drive, load_fixture


@pytest.mark.asyncio
async def test_h2_asks_once_then_answers_on_the_pin() -> None:
    run = await drive("h2-ask-pin-resume")
    ask, answer, replay = run.outputs

    # --- turn 1: an ask is not an answer ---------------------------------------
    assert isinstance(ask, Ask)
    assert ask.gap_kind == GapKind.G1_UNBOUND
    assert ask.resume_token == "core-signed-h2"  # the CORE's
    assert ask.snapshot_id  # ours
    assert len(ask.options) == 2 and ask.escape

    # --- turn 2: the pin answers, through the gate -----------------------------
    assert isinstance(answer, Answer)
    assert run.gate_calls == [0, 1, 1]  # turn 1 gates nothing; the resume gates once
    assert run.gate is not None
    assert run.gate.last_hypotheses[0].proposing_rung == "user"
    bound = [b.ref for m in answer.lattice.mentions for b in m.bindings]
    assert "md.dimension.Customer.category" in bound

    # The zero-rung default did its job: no LLM was invoked at any point, and the
    # deterministic core was called exactly ONCE across both turns.
    assert answer.llm_invocations == 0
    assert run.core is not None and run.core.calls == 1
    assert answer.asks == 1

    # --- the unanchored LOCATION hint is CARRIED, not asked about --------------
    carried = [g.kind for g in answer.gaps_carried]
    assert carried == [GapKind.G3_UNATTRIBUTED]
    assert answer.gaps_carried[0].span.text == "Praze"
    assert answer.gaps_carried[0].disposition == Disposition.IGNORED

    # --- the replay is byte-identical ------------------------------------------
    # It re-gates, and that is correct: `Gate` is stateless and idempotent, which is
    # exactly what makes an at-least-once redelivery safe rather than compounding.
    assert replay.model_dump_json() == answer.model_dump_json()


@pytest.mark.asyncio
async def test_the_single_question_is_spent_on_the_subject_gap_not_the_value_gap() -> None:
    """⚑ Ruled at T6: a VALUE gap with no roles is not load-bearing — a value's
    structural position is its anchor's (contracts §1), and *Praze* has no anchor. A
    MENTION gap with no roles still is. Without that distinction the conversation's one
    question gets spent on a word the answer does not depend on."""
    run = await drive("h2-ask-pin-resume")
    ask = run.outputs[0]

    assert isinstance(ask, Ask)
    assert "čerpacích stanic" in ask.question
    assert "Praze" not in ask.question


def test_the_fixture_states_its_invariants_in_the_suites_vocabulary() -> None:
    """P2.4's rule for this corpus: a fixture says what it asserts, in words a second
    shell can be held to. The Kotlin Golem must pass this same file."""
    fixture = load_fixture("h2-ask-pin-resume")

    assert fixture["corpus"] == "hartland_cz"
    assert any("hypothesis is not evidence" in line for line in fixture["invariants"])
    assert any("ask is not an answer" in line for line in fixture["invariants"])
    # The lattice is NAMED, not copied — the same golden the Kotlin core is held to.
    assert fixture["turns"][0]["core"]["lattice"] == "h2-cs"
