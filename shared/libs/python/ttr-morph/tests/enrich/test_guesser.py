# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.2 T4 — the pattern-guesser.

The four named cases from the task list are the first four tests: they are the
shapes the enrichment queue is actually made of (a company, a surname, an
indeclinable borrowing, and rubbish), and each of them is a different reason the
guesser could be wrong.
"""

from __future__ import annotations

import pytest

from ttrmorph.enrich.guesser import (
    AUTO_VALIDATE_CONFIDENCE,
    MIN_CONFIDENCE,
    Proposal,
    guess,
    paradigm,
    validates,
)


def test_kauflandu_proposes_the_proper_masculine_at_high_confidence():
    """The whole detailed-design §9 story starts here: an inflected company name."""
    top, *_ = guess("Kauflandu")

    assert top.lemma == "Kaufland"
    assert top.upos == "PROPN"
    assert top.vzor == "hrad-proper"
    assert top.confidence >= AUTO_VALIDATE_CONFIDENCE


def test_novakova_proposes_the_adjectival_surname():
    top, *_ = guess("Nováková")

    assert top.lemma == "Nováková"
    assert top.vzor == "adj-ova"
    assert top.upos == "PROPN"
    assert top.confidence >= AUTO_VALIDATE_CONFIDENCE


def test_atase_proposes_the_indeclinable_and_does_not_auto_validate_it():
    """⚑ An indeclinable is the one proposal a single form cannot corroborate.

    Its paradigm is one surface form, so it "explains" any observation
    perfectly and carries no evidence: from *atašé* alone there is nothing to
    tell "this word never inflects" from "I have only seen its nominative". So
    the guesser proposes it — nothing else fits — at a confidence that sends it
    to the LLM leg or to a person, which is where that question belongs.
    """
    top, *_ = guess("atašé")

    assert top.lemma == "atašé"
    assert top.vzor == "indeclinable-m"
    assert "indeclinable" in paradigm_flags(top)
    assert top.confidence < AUTO_VALIDATE_CONFIDENCE


def test_an_indeclinable_reading_loses_to_a_pattern_that_really_fits():
    """The same penalty, doing the work it was added for.

    Every dative in -i and -ovi ends the way `indeclinable-m`'s hint wants, and
    that reading takes the token as its own citation form — so without the
    penalty it outscores the correct one for a large share of ordinary Czech
    common nouns, which is most of the core queue.
    """
    top, *_ = guess("zákazníkovi")

    assert top.lemma == "zákazník"
    assert top.vzor == "pan-velar"
    assert top.upos == "NOUN"


def hints_of(vzor: str) -> dict:
    """The pattern's own hints — empty for every base vzor."""
    from ttrmorph.engine.tables import load

    return dict(load("cs").vzory[vzor].hints)


def paradigm_flags(proposal: Proposal) -> set[str]:
    """The flags the paradigm actually runs with — declared plus implied."""
    from ttrmorph.engine.tables import load

    return set(proposal.flags) | set(load("cs").vzory[proposal.vzor].implied_flags)


@pytest.mark.parametrize("token", ["2026", "%%%", "a", "", "x1", "42x", "-", "..."])
def test_a_token_that_is_not_a_word_gets_nothing(token):
    """An editorial queue full of `2026` and `%` teaches reviewers to skim."""
    assert guess(token) == []


def test_a_hyphenated_word_is_still_a_word():
    """The word guard rejects punctuation, not compounds."""
    assert guess("e-shopu") != []


# ── the ranking, and why it is the ranking ───────────────────────────────────


def test_capitalization_puts_the_proper_pattern_above_its_common_twin():
    """`hrad-proper` and `hrad-u` generate the SAME forms for this token.

    Nothing but the `capitalized` hint separates them, and getting it wrong is
    not a cosmetic error: the vzor column is what a reviewer reads, and it is
    what LM-10 routing keys on.
    """
    ranked = guess("Kauflandu")
    names = [p.vzor for p in ranked]

    assert names[0] == "hrad-proper"
    assert "hrad-u" not in names, "the mismatched twin falls under MIN_CONFIDENCE"


def test_a_capitalized_common_noun_still_reaches_its_common_pattern():
    """Sentence-initial capitals are why the mismatch is a penalty, not a veto.

    The proper-noun reading wins, as it should for a capitalized word seen
    without its sentence — but the common one survives in the list, which is
    what a reviewer (and the LLM leg's agreement check) needs.
    """
    ranked = guess("Zákazník", limit=20)

    assert ranked[0].vzor == "hrad-proper"
    assert any(p.vzor == "pan-velar" for p in ranked), "the common reading survives"


def test_an_inflected_tail_stops_a_wrong_confident_citation_form():
    """⚑ The guesser's worst failure mode, pinned.

    *loňském* is an adjective and *fakturám* a feminine dative plural, and both
    are impeccable masculine nominatives as far as surface shape goes. The
    tables know that `-ém` and `-ám` are endings; without that, both
    auto-validated as masculine nouns whose lemma is the inflected form.
    """
    for token in ("loňském", "fakturám"):
        ranked = guess(token, limit=20)
        assert ranked[0].confidence < AUTO_VALIDATE_CONFIDENCE, token
        assert any(p.lemma != token for p in ranked), "the real lemma is proposed"

    # ...and the words that end in nothing the language inflects with are
    # untouched: this is a penalty on evidence, not a blanket loss of nerve.
    assert guess("krupel")[0].confidence >= AUTO_VALIDATE_CONFIDENCE


def test_a_hintless_base_pattern_is_proposed_and_never_auto_validates():
    """The floor rule (module docstring).

    Base patterns have to be proposed — the core analytical queue is mostly
    common nouns whose base pattern carries no hints — and with no hints to
    match they cannot reach the auto-validate line, because the generate check
    that produced them is satisfied by any letter string at all.
    """
    ranked = guess("krupel", limit=20)  # well-formed, and not a Czech word
    assert ranked, "a well-shaped nonsense word is still a proposal"
    assert all(p.confidence >= MIN_CONFIDENCE for p in ranked)

    hintless = [p for p in ranked if not hints_of(p.vzor)]
    assert hintless, "the base patterns are in the list"
    assert all(p.confidence < AUTO_VALIDATE_CONFIDENCE for p in hintless)
    assert max(p.confidence for p in hintless) < ranked[0].confidence


def test_proposals_are_ranked_and_capped():
    ranked = guess("tržbami", limit=3)
    assert len(ranked) <= 3
    assert ranked == sorted(ranked, key=lambda p: -p.confidence)


def test_the_same_token_guesses_identically_twice():
    """Determinism is the whole claim of a deterministic leg."""
    assert guess("Kauflandu") == guess("Kauflandu")


# ── the auto-validate primitive ──────────────────────────────────────────────


def test_validates_is_the_observed_form_in_the_generated_paradigm():
    proposal = Proposal(lemma="Kaufland", upos="PROPN", vzor="hrad-proper")

    assert validates(proposal, "Kauflandu")
    assert validates(proposal, "Kaufland")
    # `hrad-proper`'s narrowing IS the locative singular: -u, never the -ě
    # doublet its parent allows. That one cell is the whole difference, and it
    # is the cell a Czech query most often lands on ("v Kauflandu").
    assert not validates(proposal, "Kauflandě")
    assert not validates(proposal, "Microsoftu")


def test_validates_refuses_a_proposal_the_engine_cannot_even_build():
    """A pattern that raises is a `False`, not an exception into the endpoint."""
    assert not validates(Proposal(lemma="Kaufland", upos="PROPN", vzor="no-such"), "x")
    assert not validates(Proposal(lemma="a", upos="NOUN", vzor="žena"), "a")


def test_paradigm_is_sorted_so_two_views_are_one_view():
    table = paradigm(Proposal(lemma="Kaufland", upos="PROPN", vzor="hrad-proper"))

    assert table == sorted(table)
    assert ("Kauflandu", "Animacy=Inan|Case=Dat|Gender=Masc|Number=Sing") in table
    assert paradigm(Proposal(lemma="x", upos="NOUN", vzor="no-such")) == []


def test_the_proposal_round_trips_through_its_jsonb_shape():
    proposal = guess("Kauflandu")[0]
    assert Proposal.from_dict(proposal.as_dict()) == proposal
    assert list(proposal.as_dict()) == [
        "lemma",
        "upos",
        "vzor",
        "flags",
        "confidence",
        "source",
    ]
