# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.2 T6 — the cascade and LM-10 routing, without a service around them."""

from __future__ import annotations

import json

from ttrmorph.enrich.cascade import (
    AGREEMENT_CONFIDENCE,
    LAYER_CORE,
    LAYER_WORLD,
    STATUS_AUTO_VALIDATED,
    STATUS_PROPOSED,
    TIER_GUESSER,
    TIER_HUMAN,
    TIER_LLM,
    route,
    run_cascade,
)
from ttrmorph.enrich.guesser import SOURCE_GUESSER, Proposal
from ttrmorph.enrich.llm import SOURCE_LLM, LlmLeg, LlmSpec, LlmUnavailable


def a_leg(answer) -> LlmLeg:
    def transport(system: str, user: str) -> str:
        if isinstance(answer, Exception):
            raise answer
        return json.dumps(answer)

    return LlmLeg(LlmSpec(url="http://gateway", model="m"), transport=transport)


# ── the deterministic leg on its own ─────────────────────────────────────────


def test_kauflandu_auto_validates_without_any_model():
    """detailed-design §9, minus the inflector that is Wave C.

    An air-gapped world runs exactly this path, and the flagship case has to
    clear it: no `llm`, no network, and *Kaufland* still goes live.
    """
    result = run_cascade("Kauflandu")

    assert result.status == STATUS_AUTO_VALIDATED
    assert result.tier == TIER_GUESSER
    assert result.layer == LAYER_WORLD
    assert result.best.lemma == "Kaufland"
    assert result.notes == (), "an absent LLM leg is not a degradation"


def test_a_word_the_guesser_is_unsure_of_waits_for_a_human():
    result = run_cascade("loňském")

    assert result.status == STATUS_PROPOSED
    assert result.tier == TIER_HUMAN
    assert result.proposals, "unsure is not the same as nothing to show"


def test_a_token_that_is_not_a_word_still_queues_with_nothing_to_show():
    """A typo is a legitimate queue item; it is simply one nobody can act on."""
    result = run_cascade("%%%")

    assert result.proposals == ()
    assert result.status == STATUS_PROPOSED
    assert result.tier == TIER_HUMAN


# ── the tie-break ────────────────────────────────────────────────────────────


def test_agreement_between_the_two_legs_auto_validates():
    """The tie-break, and the only thing an LLM answer can do on its own."""
    answer = {"lemma": "loňský", "upos": "ADJ", "vzor": "mladý", "flags": []}
    result = run_cascade("loňském", llm=a_leg(answer))

    assert result.status == STATUS_AUTO_VALIDATED
    assert result.tier == TIER_LLM
    assert result.agreed is True
    assert result.best.lemma == "loňský"
    assert result.best.confidence == AGREEMENT_CONFIDENCE
    assert result.best.source == SOURCE_GUESSER, "the corroborated one, not the model's"


def test_a_lone_llm_answer_is_a_proposal_and_not_a_decision():
    answer = {"lemma": "loňsko", "upos": "NOUN", "vzor": "město", "flags": []}
    result = run_cascade("loňském", llm=a_leg(answer))

    assert result.status == STATUS_PROPOSED
    assert result.tier == TIER_HUMAN
    assert result.agreed is False
    assert any(p.source == SOURCE_LLM for p in result.proposals)


def test_agreement_on_a_proposal_the_engine_cannot_confirm_does_not_auto_validate():
    """Both legs can be wrong about the same word; the engine cannot be talked into it.

    The auto-validate rule is LM-14's — the observed form is in the generated
    paradigm — and it is re-checked here rather than inferred from agreement.
    """
    # `žena` cannot make `loňském` from any lemma the guesser proposed, so even
    # if the model names one of the guesser's own proposals it must not pass.
    answer = {"lemma": "loňském", "upos": "NOUN", "vzor": "indeclinable-n", "flags": []}
    result = run_cascade("Kauflandem", llm=a_leg(answer))

    assert result.agreed is False


def test_a_gateway_that_will_not_answer_is_named_in_the_result():
    """A leg that failed silently is indistinguishable from a leg nobody configured."""
    result = run_cascade("loňském", llm=a_leg(LlmUnavailable("boom")))

    assert result.status == STATUS_PROPOSED
    assert result.tier == TIER_HUMAN
    assert any("llm leg unavailable" in note for note in result.notes)


def test_the_llm_is_not_asked_when_the_guesser_already_decided():
    """Cost, and latency, and the fact that there is nothing left to break a tie on."""
    asked: list[str] = []

    def transport(system: str, user: str) -> str:
        asked.append(user)
        return json.dumps({"lemma": "x", "upos": "NOUN", "vzor": "hrad", "flags": []})

    leg = LlmLeg(LlmSpec(url="http://gateway", model="m"), transport=transport)
    result = run_cascade("Kauflandu", llm=leg)

    assert result.status == STATUS_AUTO_VALIDATED
    assert asked == []


# ── LM-10 routing ────────────────────────────────────────────────────────────


def test_a_proper_noun_routes_to_the_world():
    proposal = Proposal("Kaufland", "PROPN", "hrad-proper")
    assert route("Kauflandu", proposal) == LAYER_WORLD


def test_a_common_noun_routes_to_the_core():
    assert route("zákazníkovi", Proposal("zákazník", "NOUN", "pan-velar")) == LAYER_CORE


def test_a_model_vocabulary_match_routes_to_the_world_whatever_its_part_of_speech():
    """LM-10's second clause: a term the model already names is the world's."""
    assert (
        route("tržbami", Proposal("tržba", "NOUN", "žena"), vocabulary=["Tržba"])
        == LAYER_WORLD
    )
    assert route("tržbami", Proposal("tržba", "NOUN", "žena")) == LAYER_CORE


def test_the_vocabulary_is_matched_folded():
    """*KAUFLAND* in a model export is the same word as *Kaufland* in a query."""
    assert route("kauflandu", vocabulary=["KAUFLAND"]) == LAYER_CORE, "not the token"
    assert route("Kaufland", vocabulary=["KAUFLAND"]) == LAYER_WORLD


def test_an_unrecognised_capitalized_token_goes_to_the_world_side():
    """Being wrong in a world layer is retractable; being wrong in the core ships."""
    assert route("Zzzt", None) == LAYER_WORLD
    assert route("zzzt", None) == LAYER_CORE


def test_the_cascade_routes_what_it_decided_rather_than_the_raw_token():
    result = run_cascade("zákazníkovi")
    assert result.layer == LAYER_CORE
    assert run_cascade("Kauflandu").layer == LAYER_WORLD


# ── the inflected-tail rule (NLS-P9.3 T6) ────────────────────────────────────


def test_a_token_ending_in_an_inflectional_ending_does_not_auto_validate():
    """⚑⚑ Found by the p9-3 bootstrap run, not by a unit test.

    *pololetích* scored 0.95 − `INFLECTED_TAIL_PENALTY` = exactly 0.80 — the
    auto-validate line, met rather than missed — and went through as
    `muzeum-um` with the invented lemma *pololetum*. The penalty was reaching
    for a categorical rule and expressing it as arithmetic.
    """
    result = run_cascade("pololetích")

    assert result.status == STATUS_PROPOSED
    assert result.tier == TIER_HUMAN
    # Still proposed and still ranked — the rule withholds the pass, it does
    # not withhold the guess.
    assert result.proposals
    assert any("inflectional ending" in note for note in result.notes)


def test_the_hero_is_untouched_by_it():
    """*Kauflandu* is the shape the whole enrichment loop was designed around.

    Single-character endings are excluded from the set on purpose (`-u`, `-a`,
    `-y`, `-e` are endings and are also how a great many citation forms end), so
    the rule cannot reach it.
    """
    result = run_cascade("Kauflandu")

    assert result.status == STATUS_AUTO_VALIDATED
    assert result.best is not None
    assert result.best.lemma == "Kaufland"
    assert result.notes == ()


def test_a_citation_form_is_untouched_by_it():
    assert run_cascade("kvartál").status == STATUS_AUTO_VALIDATED
    assert run_cascade("Kaufland").status == STATUS_AUTO_VALIDATED
