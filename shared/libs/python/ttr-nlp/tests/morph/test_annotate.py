# SPDX-License-Identifier: Apache-2.0
"""NLS-P7.3 T1/T2 — the annotator's token features (contracts §5)."""

from __future__ import annotations

from pathlib import Path

import pytest

from ttrnlp.doc.importers import TOKEN_TYPE
from ttrnlp.doc.model import Document
from ttrnlp.morph.annotate import (
    FEATURE_ANALYSES,
    FEATURE_LEMMA,
    FEATURE_LEMMAS,
    FEATURE_PROVENANCE,
    annotate_morph,
    build_document,
)
from ttrnlp.morph.records import PROVENANCE_LEXICON, PROVENANCE_STATISTICAL
from ttrnlp.morph.snapshot import load_morph

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "morph"
HERO = "Porovnej tržby Kauflandu za loňský rok s letošním"


@pytest.fixture
def state():
    return load_morph([FIXTURES / "cs-test.morph.snap"])


def tokens(doc: Document):
    return sorted(doc.annset("").with_type(TOKEN_TYPE), key=lambda a: a.start)


def by_text(doc: Document, text: str):
    return next(t for t in tokens(doc) if t.features.get("text") == text)


# ── the document the tokenizer builds ────────────────────────────────────────


def test_build_document_carries_our_own_spans():
    doc = build_document(HERO)
    assert [t.features["text"] for t in tokens(doc)] == HERO.split()
    assert doc.features["language"] == "cs"
    assert doc.features["tokenizer"] == "ttrnlp.morph:cs"
    for token in tokens(doc):
        assert doc.text[token.start : token.end] == token.features["text"]


# ── T2: what lands on a token ────────────────────────────────────────────────


def test_every_word_gets_the_four_features(state):
    doc = build_document(HERO)
    assert annotate_morph(doc, state) == 8
    token = by_text(doc, "tržby")
    assert token.features[FEATURE_LEMMA] == "tržba"
    assert token.features[FEATURE_LEMMAS] == ["tržba"]
    assert token.features[FEATURE_PROVENANCE] == PROVENANCE_LEXICON
    analysis = token.features[FEATURE_ANALYSES][0]
    assert analysis["lemma"] == "tržba"
    assert analysis["vzor"] == "žena"
    assert analysis["feats"] == [
        "Case=Acc|Number=Plur",
        "Case=Gen|Number=Sing",
        "Case=Nom|Number=Plur",
    ]


def test_analyses_are_json_shaped(state):
    """Sets become sorted lists — a frozenset has no order, and a feature that
    serialises differently on two runs makes every downstream golden flap."""
    doc = build_document("má")
    annotate_morph(doc, state)
    analyses = by_text(doc, "má").features[FEATURE_ANALYSES]
    assert [a["lemma"] for a in analyses] == ["mít", "můj"]
    assert all(isinstance(a["feats"], list) for a in analyses)


def test_the_head_of_the_list_is_the_lemma_feature(state):
    doc = build_document("má")
    annotate_morph(doc, state)
    token = by_text(doc, "má")
    assert token.features[FEATURE_LEMMA] == "mít"
    assert token.features[FEATURE_LEMMAS] == ["mít", "můj"]


def test_a_decomposition_survives_onto_the_token(state):
    doc = build_document("abych")
    annotate_morph(doc, state)
    assert by_text(doc, "abych").features[FEATURE_ANALYSES][0]["parts"] == [
        "aby",
        "bych",
    ]


def test_non_word_kinds_are_skipped(state):
    doc = build_document("Faktura č. 42 na 1 234,50 Kč.")
    annotated = annotate_morph(doc, state)
    annotated_tokens = [t for t in tokens(doc) if FEATURE_PROVENANCE in t.features]
    kinds = {t.features["kind"] for t in annotated_tokens}
    assert kinds == {"word"}
    assert annotated == len([t for t in tokens(doc) if t.features["kind"] == "word"])
    # …and the numbers were not reported as unknown words
    assert FEATURE_LEMMA not in by_text(doc, "42").features


def test_a_folded_document_resolves_the_same_lemmas(state):
    folded = "porovnej trzby kauflandu za lonsky rok s letosnim"
    doc = build_document(folded)
    annotate_morph(doc, state)
    assert by_text(doc, "trzby").features[FEATURE_LEMMA] == "tržba"
    assert by_text(doc, "lonsky").features[FEATURE_LEMMA] == "loňský"
    assert by_text(doc, "letosnim").features[FEATURE_LEMMA] == "letošní"


# ── T2: who wins over an engine's lemma ──────────────────────────────────────


def _engine_document(text: str, lemma: str) -> Document:
    doc = Document(text)
    doc.annset("").add(
        0, len(text), TOKEN_TYPE, {"text": text, "lemma": lemma, "engine": "stanza"}
    )
    return doc


def test_a_lexicon_hit_overwrites_an_engine_lemma(state):
    doc = _engine_document("tržby", "trzba-wrong")
    annotate_morph(doc, state)
    token = tokens(doc)[0]
    assert token.features[FEATURE_LEMMA] == "tržba"
    assert token.features[FEATURE_PROVENANCE] == PROVENANCE_LEXICON


def test_an_engine_lemma_stands_where_the_lexicon_is_silent(state):
    """Today's engine lemma IS the statistical leg (LM-6).

    Replacing a MorphoDiTa answer with our own stem guess would be a regression
    sold as an upgrade, so the engine value stays and the provenance says what
    produced it.
    """
    doc = _engine_document("Kauflandu", "Kaufland")
    annotate_morph(doc, state)
    token = tokens(doc)[0]
    assert token.features[FEATURE_LEMMA] == "Kaufland"
    assert token.features[FEATURE_PROVENANCE] == PROVENANCE_STATISTICAL
    assert token.features[FEATURE_LEMMAS] == ["Kaufland"]


def test_a_token_with_no_kind_is_treated_as_a_word(state):
    """Engine-built tokens carry no `kind` — their layer is words."""
    doc = _engine_document("tržby", "")
    assert annotate_morph(doc, state) == 1


# ── T1: the miss sink ────────────────────────────────────────────────────────


def test_the_miss_sink_sees_the_unknown_word_only(state):
    events: list[tuple[str, str, str]] = []
    doc = build_document(HERO)
    annotate_morph(doc, state, miss_sink=lambda *e: events.append(e), world="dfp")
    assert events == [("dfp", "Kauflandu", "miss")]


def test_the_miss_is_marked_on_the_token_too(state):
    doc = build_document(HERO)
    annotate_morph(doc, state)
    token = by_text(doc, "Kauflandu")
    assert token.features[FEATURE_PROVENANCE] == PROVENANCE_STATISTICAL
    assert token.features[FEATURE_LEMMA] == "Kaufland"


def test_the_annotator_has_no_statistical_parameter():
    """T7's guard, said in the language of the API.

    A seam with a caller is not a seam. Wave C wires `chain.resolve` directly;
    until then nothing in the pipeline can reach a model.
    """
    import inspect

    assert "statistical" not in inspect.signature(annotate_morph).parameters
