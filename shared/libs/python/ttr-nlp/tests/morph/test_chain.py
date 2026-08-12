# SPDX-License-Identifier: Apache-2.0
"""NLS-P7.3 T1/T3 — the fallback chain, and the seam that stays unwired."""

from __future__ import annotations

from pathlib import Path

import pytest

from ttrnlp.morph.chain import GUESS_UPOS, VERDICT_MISS, resolve
from ttrnlp.morph.diag import LM_MORPH_006
from ttrnlp.morph.records import (
    PROVENANCE_LEXICON,
    PROVENANCE_PROVISIONAL,
    PROVENANCE_STATISTICAL,
    Analysis,
)
from ttrnlp.morph.snapshot import load_morph

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "morph"
SNAPSHOT = FIXTURES / "cs-test.morph.snap"
OVERLAY = FIXTURES / "world-test.morph.overlay"


@pytest.fixture
def state():
    return load_morph([SNAPSHOT])


@pytest.fixture
def state_with_overlay():
    return load_morph([SNAPSHOT, OVERLAY])


class Sink:
    """A miss sink that remembers, so a test can assert what was reported."""

    def __init__(self):
        self.events: list[tuple[str, str, str]] = []

    def __call__(self, world: str, token: str, verdict: str) -> None:
        self.events.append((world, token, verdict))


# ── leg 1: the lexicon wins, and says nothing ────────────────────────────────


def test_a_lexicon_hit_wins_and_emits_no_miss(state):
    sink = Sink()
    analyses = resolve("tržby", state, miss_sink=sink, world="w")
    assert [a.lemma for a in analyses] == ["tržba"]
    assert analyses[0].provenance == PROVENANCE_LEXICON
    assert sink.events == []


def test_a_folded_lexicon_hit_is_still_a_lexicon_hit(state):
    sink = Sink()
    analyses = resolve("trzby", state, miss_sink=sink, world="w")
    assert analyses[0].provenance == PROVENANCE_LEXICON
    assert sink.events == []


def test_a_provisional_overlay_answer_is_reported_but_not_overwritten(
    state_with_overlay,
):
    """Q-7: a real artifact answered, and it is still not curated vocabulary.

    So the analysis stands as the overlay wrote it — no stem guessing over the
    top — and the token is reported anyway, because "verify this" is exactly
    what a provisional row means.
    """
    sink = Sink()
    analyses = resolve("Kauflandem", state_with_overlay, miss_sink=sink, world="w")
    assert [a.provenance for a in analyses] == [PROVENANCE_PROVISIONAL]
    assert sink.events == [("w", "Kauflandem", VERDICT_MISS)]


# ── leg 2: the seam ──────────────────────────────────────────────────────────


def test_the_statistical_seam_is_unwired_by_default(state):
    """v1 posture: the parameter exists and defaults to None."""
    sink = Sink()
    analyses = resolve("Kauflandu", state, miss_sink=sink)
    # Nothing consulted a model — the guess answered.
    assert analyses[0].provenance == PROVENANCE_STATISTICAL
    assert analyses[0].upos == GUESS_UPOS


def test_a_seam_that_answers_is_marked_and_reported(state):
    def seam(form: str):
        return [Analysis(lemma="Kaufland", upos="PROPN", feats=frozenset(), rank=7)]

    sink = Sink()
    diagnostics: list = []
    analyses = resolve(
        "Kauflandu",
        state,
        statistical=seam,
        miss_sink=sink,
        world="w",
        diagnostics=diagnostics,
    )
    assert [a.lemma for a in analyses] == ["Kaufland"]
    assert analyses[0].provenance == PROVENANCE_STATISTICAL
    # A model's own ordering is a confidence, and confidences stay world-side.
    assert analyses[0].rank == 0
    assert sink.events == [("w", "Kauflandu", VERDICT_MISS)]
    assert [d.code for d in diagnostics] == [LM_MORPH_006]


def test_a_seam_with_no_opinion_falls_through_to_the_guess(state):
    analyses = resolve("Kauflandu", state, statistical=lambda form: None)
    assert analyses[0].provenance == PROVENANCE_STATISTICAL
    assert analyses[0].upos == GUESS_UPOS


def test_the_seam_is_never_consulted_for_a_lexicon_hit(state):
    consulted = []

    def seam(form: str):
        consulted.append(form)
        return None

    resolve("tržby", state, statistical=seam)
    assert consulted == []


# ── leg 3: the fold/stem guess ───────────────────────────────────────────────


def test_the_guess_stems_an_unknown_word_and_marks_it(state):
    sink = Sink()
    analyses = resolve("Kauflandu", state, miss_sink=sink, world="dfp")
    assert len(analyses) == 1
    assert analyses[0].lemma == "Kaufland"
    assert analyses[0].upos == GUESS_UPOS
    assert analyses[0].provenance == PROVENANCE_STATISTICAL
    assert sink.events == [("dfp", "Kauflandu", VERDICT_MISS)]


def test_the_guess_uses_a_known_base_when_there_is_one(state_with_overlay):
    """*Kauflandem* is in the overlay, but *Kauflandův* is not.

    Stripping ``ův`` is not in the ending list, so the guess falls back to the
    stem — the point of the assertion is the one below it: when a strip DOES
    land on a known base, the base's analyses come back re-marked rather than
    invented.
    """
    analyses = resolve("Kauflandy", state_with_overlay)
    assert analyses[0].lemma == "Kaufland"
    assert analyses[0].provenance == PROVENANCE_STATISTICAL
    # …and it really came from the overlay entry, not from stemming
    assert analyses[0].upos == "PROPN"


def test_the_guess_never_returns_nothing(state):
    """A token with no analysis at all is a silent miss; this one is marked."""
    analyses = resolve("qwertzuiop", state)
    assert analyses and analyses[0].provenance == PROVENANCE_STATISTICAL


def test_a_very_short_unknown_word_is_not_stemmed_into_a_fragment(state):
    analyses = resolve("zx", state)
    assert analyses[0].lemma == "zx"


def test_the_miss_sink_is_optional(state):
    assert resolve("Kauflandu", state)  # no sink, no crash


def test_the_verdict_is_always_a_miss(state):
    """`resolved_wrong` is a human's judgement arriving via ReportToken.

    Nothing in the chain can know it answered wrongly — if it could, it would
    have answered correctly.
    """
    sink = Sink()
    for form in ("Kauflandu", "qwertzuiop", "nekvalitni"):
        resolve(form, state, miss_sink=sink)
    assert {event[2] for event in sink.events} == {VERDICT_MISS}
