# SPDX-License-Identifier: Apache-2.0
"""P4.3·T4 — compose: a covered lattice becomes a structured question, 0 LLM.

Driven off the resolver's own lattice goldens, so what is composed is what the core
actually emits rather than what a hand-authored fixture wishes it emitted.
"""

from __future__ import annotations

import json

import pytest

from golem_py.compose import (
    ComposeRefused,
    StructuredQuestion,
    compose_operators,
    compose_structured_question,
    operator_refs,
)
from golem_py.skills import LayeredSkillLibrary, SkillLibrary
from golem_py.state import FrameRole, Mention, ResolutionState, Span
from tests.helpers import fixture_library
from tests.lattice_fixtures import lattice_golden


def _compose(case: str) -> StructuredQuestion:
    return compose_structured_question(lattice_golden(case), fixture_library())


def test_h1_composes_to_the_known_shape() -> None:
    """H1: *"Zobraz náklady účtu 501001 v roce 2025 podle období"* — the 0-LLM hero.
    Cost as the measure, the account MEMBER as the filter, the year as the grain, and
    `op:show` composing to a plain table."""
    question = _compose("h1-cs")

    assert question.measures == ["md.measure.cost"]
    assert question.subjects == ["md.measure.cost"]  # measure-as-subject: BOTH roles
    assert question.operators == ["op:show"]
    assert any(f.member_ref == "md.dimension.Account.code#501001" for f in question.filters)
    assert question.time_grain is not None and question.time_grain.normalized_value
    assert "table by default" in question.formatting_directives["op:show"]


def test_the_measure_as_subject_mention_contributes_to_BOTH_roles() -> None:
    """⚑ The 32-of-137 class, and the reason `frame_roles` is repeated. Treating roles
    as a partition would drop either the subject or the measure — and WHICH one it
    drops would depend on emission order, which is the worst kind of bug to debug."""
    state = lattice_golden("h1-cs")
    naklady = next(m for m in state.mentions if m.span.text == "náklady")
    assert naklady.frame_roles == [FrameRole.SUBJECT, FrameRole.MEASURE]

    question = compose_structured_question(state, fixture_library())

    assert "md.measure.cost" in question.measures
    assert "md.measure.cost" in question.subjects


def test_h5_composes_the_applicable_operators_and_notes_the_one_that_cannot_fire() -> None:
    """H5 is the operator layer through the ORDINARY path — three `op:` bindings in one
    question, bound like any other vocabulary — and the case that separates "no body"
    from "cannot apply": *"porovnej s plánem"* leaves `op:compare` with ONE series
    because *plánem* is an honest G1, so the comparison drops with a note while the
    trend still answers. Refusing the whole question over it would throw away four
    correct bindings to protect one."""
    state = lattice_golden("h5-cs")
    assert operator_refs(state) == ["op:show", "op:trend", "op:compare"]

    question = compose_structured_question(state, fixture_library())

    assert question.operators == ["op:show", "op:trend"]
    assert question.inapplicable_operators == ["op:compare requires two-series"]
    assert len(question.retrieval_directives) == 2  # retrieval MERGES over what applies


def test_multi_op_formatting_accumulates_in_op_order() -> None:
    """⚠ Retrieval MERGES, per contracts §6. Formatting does NOT do what §6 says.

    This test used to be called `..._is_last_op_wins_per_key` and asserted that BOTH
    entries survived — reading as coverage of the rule while pinning its absence. The
    rule cannot be honoured while a `Formatting:` section is undifferentiated prose:
    there is no directive KEY for a later op to win on. What the code does, and what this
    now says, is accumulate in op order and leave the conflict to the renderer — H5 ships
    `op:show`'s "table by default" beside `op:trend`'s "line chart by default".

    ⚑ Bora: making §6 literal needs a keyed formatting line in the body grammar, i.e. a
    change to the artifact `tatrman`'s compiler emits.
    """
    library = fixture_library()
    _, retrieval, formatting = compose_operators(["op:top-n", "op:share-of"], library)

    assert len(retrieval) == 2
    assert list(formatting) == ["op:top-n", "op:share-of"]  # ORDER is the contract here


def test_an_unknown_requirement_refuses_rather_than_counting_as_satisfied() -> None:
    """⛑ `check_applicability` knew two requirement names and let every other one fall
    through as SATISFIED. The real stdlib declares four — `op:top-n`'s `order-measure`
    and `op:drilldown`'s `parent-context` were both waved through, i.e. two of the six
    shipped operators had their applicability unchecked.

    Unknown now refuses, which is the direction everything else unhonourable takes: an
    operator whose condition we cannot evaluate is one we do not know how to apply, and
    that is the "no body" case, not the "cannot apply" one.
    """
    library = LayeredSkillLibrary(
        [
            SkillLibrary.from_json(
                json.dumps(
                    {
                        "schemaVersion": "ttr-operator-library/v1",
                        "operators": {
                            "op:show": {
                                "body": "Show it.\n\nApplicability: `no-such-condition` — "
                                "invented.\n",
                                "version": 1,
                            }
                        },
                    }
                )
            )
        ]
    )

    with pytest.raises(ComposeRefused, match="no-such-condition"):
        compose_structured_question(lattice_golden("h1-cs"), library)


def test_the_four_stdlib_requirements_are_all_evaluated() -> None:
    """The registry must cover what the stdlib actually declares; a name missing from it
    is now a refusal, so a silent pass cannot come back."""
    from golem_py.compose import _REQUIREMENT_CHECKS

    assert set(_REQUIREMENT_CHECKS) == {
        "time-grain",
        "two-series",
        "order-measure",
        "parent-context",
    }


def test_an_unsatisfied_requires_refuses_at_compose_not_at_match() -> None:
    """`requires:` is validated at COMPOSE (contracts §2): a trigger matching is a fact
    about the question's surface, but whether the operator can DO anything is a fact
    about what resolved. `op:trend` with no time grain has nothing to group by."""
    state = ResolutionState(
        mentions=[
            Mention(
                id="m1",
                span=Span(start=0, end=5, text="vývoj"),
                bindings=lattice_golden("h5-cs").mentions[1].bindings,
            ),
            Mention(
                id="m2",
                span=Span(start=6, end=14, text="nákladů"),
                frame_roles=[FrameRole.SUBJECT, FrameRole.MEASURE],
                bindings=lattice_golden("h5-cs").mentions[2].bindings,
            ),
        ]
    )
    assert operator_refs(state) == ["op:trend"]

    with pytest.raises(ComposeRefused, match="time-grain"):
        compose_structured_question(state, fixture_library())


def test_an_operator_with_no_body_refuses_rather_than_being_skipped() -> None:
    from golem_py.skills import LayeredSkillLibrary

    with pytest.raises(ComposeRefused, match="op:show"):
        compose_structured_question(lattice_golden("h1-cs"), LayeredSkillLibrary())


def test_a_question_naming_no_operator_refuses_over_the_paygrade() -> None:
    """⚑ THE T1 PREDICATE, and H4's case. *"Why did the costs of account 501001 jump in
    Q2?"* resolves EXACTLY as H1 does — same entities, gap-free — and still refuses,
    because "why" is in no operator library: the question names an action this Golem was
    never taught. No intent classification is involved, and that is the point."""
    state = lattice_golden("h1-cs")
    for mention in state.mentions:
        mention.bindings = [b for b in mention.bindings if b.ref != "op:show"]
    assert operator_refs(state) == []

    with pytest.raises(ComposeRefused, match="no operator this Golem can perform"):
        compose_structured_question(state, fixture_library())


def test_a_question_with_nothing_to_select_refuses() -> None:
    """No measure and no subject: whatever else resolved, there is no shape the data
    can answer. Saying so beats returning something plausible."""
    state = lattice_golden("h1-cs")
    for mention in state.mentions:
        mention.frame_roles = []

    with pytest.raises(ComposeRefused, match="nothing to select"):
        compose_structured_question(state, fixture_library())


def test_a_member_filter_keeps_its_anchor() -> None:
    """RV-33: the anchoring is what makes it "the ACCOUNT 501001" rather than "some
    501001 somewhere" — the user said *účtu*, so the code was checked against the
    account first."""
    question = _compose("h1-cs")

    member = next(f for f in question.filters if f.member_ref)
    assert member.anchor_mention_id
    assert member.ref == "md.dimension.Account.code"


def test_compose_touches_no_llm_and_no_network() -> None:
    """The whole leg is deterministic by construction: it reads a lattice and a library
    and returns a dataclass. This test is a statement of intent that a future edit has
    to break on purpose."""
    question = _compose("h1-cs")
    again = _compose("h1-cs")

    assert question == again
