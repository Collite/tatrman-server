# SPDX-License-Identifier: Apache-2.0
"""The metric math, on a canned split with hand-computed numbers (T1).

Every expected value here was worked out on paper from the six tokens below,
not read off a run. A metrics test whose expectations came from the code it
tests measures that the code has not changed, which is not the same thing as
measuring that it is right — and these four numbers are what a release gate and
a Wave C brief are both read off.

The canned corpus, and what each token is for::

    tržby   / tržba   NOUN   answered, gold lemma is the head       ✓✓
    tržbě   / tržba   NOUN   answered, gold in the set, NOT the head ✓✗
    trzby   / tržba   NOUN   answered via the FOLD index             ✓✓ folded
    kaufland/ Kaufland PROPN  no entry at all — uncovered
    nás     / já      PRON   the UD convention: our lemma is `my`
    xyzzy   / xyzzy   NOUN   uncovered
"""

from __future__ import annotations

import pytest
from ttrnlp.morph import (
    MATCHED_EXACT,
    MATCHED_FOLDED,
    PROVENANCE_LEXICON,
    PROVENANCE_PROVISIONAL,
    Analysis,
    LookupResult,
)

from ttrmorph.eval.metrics import (
    UD_CONVENTIONS,
    GoldToken,
    accepted_lemmas,
    lexicon_analyses,
    score,
)


def analysis(lemma, upos="NOUN", provenance=PROVENANCE_LEXICON):
    return Analysis(
        lemma=lemma, upos=upos, feats=frozenset({"Case=Nom"}), provenance=provenance
    )


class FakeState:
    """A `MorphState` as far as the scorer is concerned: it looks forms up."""

    def __init__(self, answers):
        self.answers = answers

    def lookup(self, form):
        return self.answers.get(form)


def result(form, lemmas, via=MATCHED_EXACT, upos="NOUN"):
    return LookupResult(
        form=form,
        matched_via=via,
        analyses=tuple(analysis(lemma, upos) for lemma in lemmas),
    )


@pytest.fixture
def state():
    return FakeState(
        {
            "tržby": result("tržby", ["tržba"]),
            "tržbě": result("tržbě", ["tržbot", "tržba"]),
            "trzby": result("trzby", ["tržba"], via=MATCHED_FOLDED),
            "nás": result("nás", ["my"], upos="PRON"),
        }
    )


TOKENS = [
    GoldToken("tržby", "tržba", "NOUN"),
    GoldToken("tržbě", "tržba", "NOUN"),
    GoldToken("trzby", "tržba", "NOUN"),
    GoldToken("kaufland", "Kaufland", "PROPN"),
    GoldToken("nás", "já", "PRON"),
    GoldToken("xyzzy", "xyzzy", "NOUN"),
]


# ── the four numbers ─────────────────────────────────────────────────────────


def test_coverage_is_over_every_token(state):
    metrics = score(TOKENS, state)
    assert metrics.total.tokens == 6
    assert metrics.total.answered == 4
    assert metrics.total.coverage == pytest.approx(4 / 6)


def test_lemma_in_set_is_over_answered_tokens(state):
    """All four answered tokens carry the gold lemma somewhere in the set."""
    metrics = score(TOKENS, state)
    assert metrics.total.in_set == 4
    assert metrics.total.lemma_in_set == pytest.approx(1.0)
    # ...and the all-token denominator is printed too, and is NOT 1.0.
    assert metrics.total.lemma_in_set_all == pytest.approx(4 / 6)


def test_head_of_list_is_stricter_than_lemma_in_set(state):
    """`tržbě` answers with the gold lemma SECOND, which is a head miss."""
    metrics = score(TOKENS, state)
    assert metrics.total.head == 3
    assert metrics.total.head_of_list == pytest.approx(3 / 4)


def test_fold_collision_rate_counts_only_ambiguous_folds(state):
    """`trzby` came through the fold index and got exactly one lemma, so the
    fold created no ambiguity and the rate is zero — the metric is about
    collisions, not about folding."""
    metrics = score(TOKENS, state)
    assert metrics.total.folded == 1
    assert metrics.total.fold_collision_rate == pytest.approx(0.0)


def test_a_fold_that_returns_two_lemmas_is_a_collision():
    state = FakeState({"byt": result("byt", ["byt", "být"], via=MATCHED_FOLDED)})
    metrics = score([GoldToken("byt", "byt", "NOUN")], state)
    assert metrics.total.fold_ambiguous == 1
    assert metrics.total.fold_collision_rate == pytest.approx(1.0)


# ── weighting, breakdown, samples ────────────────────────────────────────────


def test_tokens_are_weighted_by_count(state):
    metrics = score([GoldToken("tržby", "tržba", "NOUN", count=10)], state)
    assert metrics.total.tokens == 10
    assert metrics.total.head == 10


def test_the_breakdown_is_per_upos(state):
    metrics = score(TOKENS, state)
    assert metrics.per_upos["NOUN"].tokens == 4
    assert metrics.per_upos["NOUN"].answered == 3
    assert metrics.per_upos["PROPN"].coverage == pytest.approx(0.0)


def test_the_uncovered_sample_is_the_wave_c_brief(state):
    metrics = score(TOKENS, state)
    assert ("kaufland", "Kaufland", 1) in metrics.uncovered
    assert ("xyzzy", "xyzzy", 1) in metrics.uncovered


def test_head_misses_name_both_lemmas(state):
    metrics = score(TOKENS, state)
    assert metrics.head_misses == [("tržbě", "tržba", "tržbot", 1)]


# ── the UD convention ────────────────────────────────────────────────────────


def test_the_convention_accepts_our_citation_form(state):
    """*nás* is gold `já` and ours is `my`; the same person, a collapsed number."""
    metrics = score(TOKENS, state)
    assert metrics.total.by_convention == 1
    assert metrics.per_upos["PRON"].head == 1


def test_the_convention_does_not_cross_persons():
    """The loose reading of this rule would accept `ty` for a gold `já`. It is
    a convention about NUMBER, and a scorer that forgets that is a scorer with
    its accuracy filed off."""
    assert "ty" not in accepted_lemmas(GoldToken("nás", "já", "PRON"))
    assert accepted_lemmas(GoldToken("nás", "já", "PRON")) == {"já", "my"}


def test_the_convention_needs_the_right_part_of_speech():
    assert accepted_lemmas(GoldToken("já", "já", "NOUN")) == {"já"}


def test_every_convention_class_maps_to_itself():
    """A gold lemma that did not accept ITSELF would score every token of its
    own class wrong — the failure mode a table like this has."""
    for gold, accepted in UD_CONVENTIONS.items():
        assert gold in accepted


# ── what counts as an answer ─────────────────────────────────────────────────


def test_provisional_rows_are_not_coverage():
    """A world overlay's unconfirmed guess (Q-7) is a real answer from a real
    artifact and it is not the curated lexicon. Counting it would report the
    scope as larger than it is — and that number sizes Wave C."""
    assert lexicon_analyses(
        (analysis("Kaufland", provenance=PROVENANCE_PROVISIONAL),)
    ) == ()
    state = FakeState(
        {
            "Kaufland": LookupResult(
                form="Kaufland",
                matched_via=MATCHED_EXACT,
                analyses=(analysis("Kaufland", provenance=PROVENANCE_PROVISIONAL),),
            )
        }
    )
    metrics = score([GoldToken("Kaufland", "Kaufland", "PROPN")], state)
    assert metrics.total.answered == 0


def test_an_empty_run_divides_by_nothing():
    metrics = score([], FakeState({}))
    assert metrics.total.coverage == 0.0
    assert metrics.total.head_of_list == 0.0
