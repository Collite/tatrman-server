# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.2 T5 — the LLM classifier leg.

Every test here drives the real prompt construction, the real parser and the
real validator over a fake transport. What is NOT tested is the HTTP client, on
purpose: it is a copy of `nlp_service.engines.llm_gateway`'s retry loop, which
has its own tests, and a test that stood up a socket to prove `httpx` posts
would be testing `httpx`.
"""

from __future__ import annotations

import json

import pytest

from ttrmorph.enrich.guesser import Proposal
from ttrmorph.enrich.llm import (
    DEFAULT_MODEL,
    ENV_API_KEY,
    ENV_MODEL,
    ENV_URL,
    LLM_CONFIDENCE,
    SOURCE_LLM,
    LlmLeg,
    LlmRefused,
    LlmSpec,
)

ANSWER = {"lemma": "Kaufland", "upos": "PROPN", "vzor": "hrad-proper", "flags": []}


def leg(answer, *, seen=None) -> LlmLeg:
    """A leg over a canned transport; `seen` collects (system, user) pairs."""

    def transport(system: str, user: str) -> str:
        if seen is not None:
            seen.append((system, user))
        return answer if isinstance(answer, str) else json.dumps(answer)

    return LlmLeg(LlmSpec(url="http://gateway", model="m"), transport=transport)


# ── the air gap ──────────────────────────────────────────────────────────────


def test_no_gateway_configured_means_no_leg():
    """An air-gapped world is a supported deployment, not a degraded one."""
    assert LlmLeg.from_env({}) is None
    assert LlmLeg.from_env({ENV_MODEL: "m"}) is None
    assert LlmLeg.from_env({ENV_URL: "   "}) is None


def test_a_configured_gateway_makes_a_leg_and_defaults_to_the_haiku_class():
    built = LlmLeg.from_env({ENV_URL: "http://gateway"}, transport=lambda s, u: "{}")

    assert built is not None
    assert built.spec.model == DEFAULT_MODEL


def test_a_keyless_gateway_is_a_leg_and_a_warning(caplog):
    """The nlp ruling, restated: no address ⇒ uncallable; no key ⇒ ordinary dev."""
    built = LlmLeg.from_env(
        {ENV_URL: "http://gateway", ENV_API_KEY: ""}, transport=lambda s, u: "{}"
    )

    assert built is not None
    assert any(ENV_API_KEY in record.message for record in caplog.records)


# ── the prompt ───────────────────────────────────────────────────────────────


def test_the_inventory_is_printed_into_the_prompt():
    """A closed choice is only closed if the model is shown the choices."""
    seen: list[tuple[str, str]] = []
    leg(ANSWER, seen=seen).classify("Kauflandu")
    system, user = seen[0]

    assert "hrad-proper" in system and "adj-ova" in system
    assert "fleeting-e" in system
    assert "PROPN" in system
    assert "Kauflandu" in user


def test_the_model_is_told_not_to_write_forms():
    """The whole design: it points at a pattern, the engine makes the forms."""
    seen: list[tuple[str, str]] = []
    leg(ANSWER, seen=seen).classify("Kauflandu")

    assert "Do NOT write out any inflected forms" in seen[0][0]


def test_a_context_span_is_passed_through_when_there_is_one():
    seen: list[tuple[str, str]] = []
    leg(ANSWER, seen=seen).classify("ženu", context="Ženu auto do servisu")

    assert "Ženu auto do servisu" in seen[0][1]


def test_no_context_span_leaves_the_prompt_alone():
    seen: list[tuple[str, str]] = []
    leg(ANSWER, seen=seen).classify("ženu")

    assert "Seen in" not in seen[0][1]


# ── the answer ───────────────────────────────────────────────────────────────


def test_a_valid_answer_becomes_a_proposal():
    proposal = leg(ANSWER).classify("Kauflandu")

    assert proposal == Proposal(
        lemma="Kaufland",
        upos="PROPN",
        vzor="hrad-proper",
        flags=(),
        confidence=LLM_CONFIDENCE,
        source=SOURCE_LLM,
    )


def test_an_llm_proposal_alone_never_reaches_the_auto_validate_line():
    from ttrmorph.enrich.guesser import AUTO_VALIDATE_CONFIDENCE

    assert LLM_CONFIDENCE < AUTO_VALIDATE_CONFIDENCE


def test_a_fenced_answer_is_still_read():
    """Models fence JSON however firmly they are told not to."""
    proposal = leg("```json\n" + json.dumps(ANSWER) + "\n```").classify("Kauflandu")

    assert proposal.lemma == "Kaufland"


def test_an_invented_vzor_is_refused_rather_than_stored():
    """The prompt asks; this is the check.

    A pattern name the tables do not have would reach the database, fail to
    generate, and sit in the queue as a proposal no reviewer can act on.
    """
    with pytest.raises(LlmRefused, match="inventory is closed"):
        leg({**ANSWER, "vzor": "hrad-ish"}).classify("Kauflandu")


def test_an_invented_pos_is_refused():
    with pytest.raises(LlmRefused, match="not a UD part of speech"):
        leg({**ANSWER, "upos": "COMPANY"}).classify("Kauflandu")


def test_an_invented_flag_is_refused():
    with pytest.raises(LlmRefused, match="unknown flags"):
        leg({**ANSWER, "flags": ["fleeting-a"]}).classify("Kauflandu")


def test_a_missing_lemma_is_refused():
    with pytest.raises(LlmRefused, match="no lemma"):
        leg({**ANSWER, "lemma": "  "}).classify("Kauflandu")


def test_prose_instead_of_json_is_refused():
    with pytest.raises(LlmRefused, match="not JSON"):
        leg("I think this is probably a masculine noun!").classify("Kauflandu")


def test_a_json_array_is_refused():
    with pytest.raises(LlmRefused, match="not an object"):
        leg("[1, 2]").classify("Kauflandu")


def test_flags_are_normalised_so_agreement_can_be_equality():
    """A one-flag answer as a bare string, and order, must not defeat the
    cascade's agreement check — which compares tuples."""
    one = leg({**ANSWER, "vzor": "hrad", "flags": "palatal"}).classify("Kauflandu")
    many = leg(
        {**ANSWER, "vzor": "hrad", "flags": ["shorten", "palatal"]}
    ).classify("Kauflandu")

    assert one.flags == ("palatal",)
    assert many.flags == ("palatal", "shorten")
