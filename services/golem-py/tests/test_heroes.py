# SPDX-License-Identifier: Apache-2.0
"""P4.3·T6/T7 — the four heroes through the whole service, against a recorded core.

H1 answers · H5 answers · H4 refuses · H2 asks (P4.2's fixture). Live-core versions of
these are P4.4's; what is proved here is that the answering leg holds together —
compose, the skill bodies, the envelope, and the refusal that carries the lattice.
"""

from __future__ import annotations

import pytest

from golem_py.graph import run_turn
from golem_py.outputs import Answer, RefusalWithGaps
from golem_py.query_client import QueryResult
from golem_py.state import GapKind, ResolutionState
from tests.helpers import RecordedCore, deps
from tests.lattice_fixtures import lattice_golden


class RecordedQueryDoor:
    """A recorded query door. The live client is NOT built at P4 — see
    `query_client.py`'s T5 ruling: the door's proto is not self-contained in this repo,
    and there is no surface that takes a structured question."""

    def __init__(self, rows: int = 3):
        self.rows = rows
        self.calls = 0
        self.last_question: object = None

    async def run(self, *, question: object, caller_subject: str = "") -> QueryResult:
        self.calls += 1
        self.last_question = question
        return QueryResult(
            rows=[{"period": f"2025-{i + 1:02d}", "value": 100 + i} for i in range(self.rows)],
            columns=["period", "value"],
            provenance={"engine": "recorded"},
        )


async def _run(case: str, **kwargs: object) -> Answer | RefusalWithGaps:
    core = RecordedCore(lattice_golden(case))
    out = await run_turn(ResolutionState(question=case), deps(core, **kwargs))  # type: ignore[arg-type]
    return out  # type: ignore[return-value]


# ------------------------------------------------------------------------ H1


@pytest.mark.asyncio
async def test_h1_answers_with_zero_llm_calls_and_zero_asks() -> None:
    """The 0-LLM proof, end to end through the service. Everything below the door line
    is deterministic, and the ladder's shipped default proposes nothing."""
    out = await _run("h1-cs")

    assert isinstance(out, Answer)
    assert out.llm_invocations == 0 and out.asks == 0
    assert out.envelope is not None
    assert out.envelope.question is not None
    assert out.envelope.question.measures == ["md.measure.cost"]
    assert "md.measure.cost" in out.content


@pytest.mark.asyncio
async def test_the_envelope_carries_the_layer_tuple_and_the_engines() -> None:
    """contracts §6's `provenance`: the RV-39 tuple + the S-1 echo. Same tuple + same
    question ⇒ same lattice is only checkable if the answer SAYS what the tuple was."""
    out = await _run("h1-cs")

    assert isinstance(out, Answer) and out.envelope is not None
    provenance = out.envelope.provenance
    assert provenance["lexicon_artifact_hash"] == "sha256:h1-lexicon"
    assert provenance["member_index:md.dimension.Account.code"] == "v3"


@pytest.mark.asyncio
async def test_formatting_directives_reach_the_envelope_from_the_skill_body() -> None:
    out = await _run("h1-cs")

    assert isinstance(out, Answer) and out.envelope is not None
    assert "table by default" in out.envelope.formatting_directives["op:show"]


@pytest.mark.asyncio
async def test_the_query_door_is_called_with_the_composed_question_when_one_exists() -> None:
    door = RecordedQueryDoor()

    out = await _run("h1-cs", query=door)

    assert isinstance(out, Answer) and out.envelope is not None
    assert door.calls == 1
    assert door.last_question is out.envelope.question
    assert "rows: 3" in out.content


@pytest.mark.asyncio
async def test_with_no_door_the_turn_still_answers_structurally() -> None:
    """The door is optional at P4 and the answer says so honestly: the structured
    question and its provenance, with no row count claimed."""
    out = await _run("h1-cs")

    assert isinstance(out, Answer)
    assert "rows:" not in out.content


# ------------------------------------------------------------------------ H5


@pytest.mark.asyncio
async def test_h5_answers_and_carries_the_honest_gap() -> None:
    """The measure-as-subject hero. *plánem* stays an honest `G1_UNBOUND` in FILTER
    position — carried as a note beside the answer, never asked about and never
    guessed."""
    out = await _run("h5-cs")

    assert isinstance(out, Answer)
    assert out.llm_invocations == 0 and out.asks == 0
    assert [g.kind for g in out.gaps_carried] == [GapKind.G1_UNBOUND]
    assert out.gaps_carried[0].span.text == "plánem"
    assert out.envelope is not None and out.envelope.question is not None
    assert out.envelope.question.inapplicable_operators == ["op:compare requires two-series"]


# ------------------------------------------------------------------------ H4


@pytest.mark.asyncio
async def test_h4_resolves_completely_and_still_refuses() -> None:
    """⚑ H4 is the whole point of the T1 predicate: *"Why did the costs of account
    501001 jump in Q2?"* binds the SAME entities H1 does and is gap-free — and the OS
    Golem still refuses, because "why" names an action no operator library holds a body
    for. "I understood you completely; I cannot investigate causes."
    """
    state = lattice_golden("h1-cs")  # H4's lattice: same entities as H1...
    for mention in state.mentions:  # ...minus the operator, because "Proč" is not one
        mention.bindings = [b for b in mention.bindings if b.ref != "op:show"]

    out = await run_turn(ResolutionState(question="Proč…"), deps(RecordedCore(state)))

    assert isinstance(out, RefusalWithGaps)
    assert out.reason == "NO_CAPABLE_PLUGIN"
    # Understanding PROVEN: the lattice rides along, fully bound.
    assert len([b for m in out.lattice.mentions for b in m.bindings]) >= 4
    assert out.lattice.gaps == []
    assert "no operator this Golem can perform" in out.explanation
    assert out.composable_residue == []  # nothing it could do instead — say so plainly


@pytest.mark.asyncio
async def test_the_refusal_names_what_it_can_still_do() -> None:
    """The refusal is structured, not apologetic: when some operators DO have bodies,
    the residue names them so a caller can offer something real."""
    state = lattice_golden("h5-cs")
    state.gaps = []
    for mention in state.mentions:
        mention.frame_roles = []  # nothing to select — refuse, but the ops are known

    out = await run_turn(ResolutionState(question="…"), deps(RecordedCore(state)))

    assert isinstance(out, RefusalWithGaps)
    assert out.reason == "NO_CAPABLE_PLUGIN"
    assert out.composable_residue == ["op:show", "op:trend", "op:compare"]


@pytest.mark.asyncio
async def test_an_estate_with_no_operator_library_refuses_rather_than_skipping() -> None:
    """T2(c) at the service level: an op the question named and we hold no body for
    changes the answer, so it is a refusal — never a silent skip that answers a
    different question."""
    from golem_py.skills import LayeredSkillLibrary

    out = await _run("h1-cs", skills=LayeredSkillLibrary())

    assert isinstance(out, RefusalWithGaps)
    assert "op:show" in out.explanation
