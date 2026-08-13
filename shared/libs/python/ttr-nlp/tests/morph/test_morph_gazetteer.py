# SPDX-License-Identifier: Apache-2.0
"""NLS-P7.3 T1/T4 — the match-if-any gazetteer (LM-8)."""

from __future__ import annotations

from pathlib import Path

import pytest

from ttrnlp.doc.importers import TOKEN_TYPE
from ttrnlp.doc.model import Document
from ttrnlp.gazetteer.lists import GazetteerList, load_list
from ttrnlp.morph.gazetteer import MATCHING, build_morph_gazetteer
from ttrnlp.packs.diag import PackError

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "morph"
HERO_LIST = FIXTURES / "lm-hero.list.yaml"


def document(*tokens: tuple[str, list[str] | str | None]) -> Document:
    """A document of space-separated tokens, each with the lemmas given."""
    text = " ".join(word for word, _ in tokens)
    doc = Document(text)
    annset = doc.annset("")
    offset = 0
    for word, lemmas in tokens:
        features: dict = {"text": word, "kind": "word"}
        if isinstance(lemmas, list):
            features["lemmas"] = lemmas
            features["lemma"] = lemmas[0]
        elif isinstance(lemmas, str):
            features["lemma"] = lemmas
        annset.add(offset, offset + len(word), TOKEN_TYPE, features)
        offset += len(word) + 1
    return doc


def lookups(doc: Document):
    return sorted(doc.annset("").with_type("Lookup"), key=lambda a: (a.start, a.end))


def a_list(terms: dict[str, dict], *, list_id: str = "l") -> GazetteerList:
    return GazetteerList.model_validate(
        {
            "list": list_id,
            "version": 1,
            "matching": "lemma",
            "annotation": "Lookup",
            "source": {"world": "lm-test", "origin": "fixture@NLS-P7.3"},
            "entries": [
                {"term": term, "features": features} for term, features in terms.items()
            ],
        }
    )


# ── the folded trap ──────────────────────────────────────────────────────────


def test_an_entry_matches_a_non_head_candidate_lemma():
    """The reason this class exists.

    A list written *byt* must match a token the lexicon read as *být* first.
    Otherwise ranking — a best guess with a frequency table behind it — decides
    which world vocabulary matches, and the world always loses.
    """
    doc = document(("byt", ["být", "byt"]))
    build_morph_gazetteer([a_list({"byt": {"entity": "byt"}})]).annotate(doc)
    assert [a.features["entity"] for a in lookups(doc)] == ["byt"]


def test_the_head_lemma_still_matches():
    doc = document(("tržby", ["tržba"]))
    build_morph_gazetteer([a_list({"tržba": {"entity": "trzba"}})]).annotate(doc)
    assert len(lookups(doc)) == 1


def test_a_token_with_only_an_engine_lemma_still_matches():
    """One list serves a morph pipeline and an engine pipeline."""
    doc = document(("faktury", "faktura"))
    build_morph_gazetteer([a_list({"faktura": {"entity": "faktura"}})]).annotate(doc)
    assert len(lookups(doc)) == 1


def test_a_token_with_no_lemma_falls_back_to_its_text():
    doc = document(("Kaufland", None))
    build_morph_gazetteer([a_list({"Kaufland": {"entity": "kaufland"}})]).annotate(doc)
    assert len(lookups(doc)) == 1


# ── longest match, single pass, dedup ────────────────────────────────────────


def test_longest_match_wins_over_its_own_tail():
    doc = document(("obchodní", ["obchodní"]), ("zástupce", ["zástupce"]))
    gazetteer = build_morph_gazetteer(
        [
            a_list(
                {
                    "obchodní zástupce": {"entity": "role"},
                    "zástupce": {"entity": "osoba"},
                }
            )
        ]
    )
    gazetteer.annotate(doc)
    assert [(a.start, a.end, a.features["entity"]) for a in lookups(doc)] == [
        (0, 17, "role")
    ]


def test_a_multi_token_term_matches_through_a_non_head_lemma():
    """Single pass: the whole candidate set advances one walk.

    Both tokens are ambiguous, and the term is only reachable by taking the
    second candidate of each. A candidate-per-pass loop would need to try four
    combinations and would annotate some spans twice.
    """
    doc = document(("má", ["mít", "můj"]), ("byt", ["být", "byt"]))
    build_morph_gazetteer([a_list({"můj byt": {"entity": "bydleni"}})]).annotate(doc)
    assert [(a.start, a.end) for a in lookups(doc)] == [(0, 6)]


def test_two_candidate_lemmas_reaching_one_list_produce_one_lookup():
    """Dedup: one `Lookup` per span per list."""
    doc = document(("má", ["mít", "můj"]))
    gazetteer = build_morph_gazetteer(
        [a_list({"mít": {"entity": "vlastnit"}, "můj": {"entity": "vlastnit"}})]
    )
    gazetteer.annotate(doc)
    assert len(lookups(doc)) == 1


def test_two_lists_both_fire_on_the_same_span():
    """Two lists are two knowledge sources; neither silently loses."""
    doc = document(("tržby", ["tržba"]))
    gazetteer = build_morph_gazetteer(
        [
            a_list({"tržba": {"entity": "a"}}, list_id="first"),
            a_list({"tržba": {"entity": "b"}}, list_id="second"),
        ]
    )
    gazetteer.annotate(doc)
    assert sorted(a.features["source"] for a in lookups(doc)) == ["first", "second"]


def test_nothing_matches_when_no_candidate_is_in_the_list():
    doc = document(("tržby", ["tržba"]))
    build_morph_gazetteer([a_list({"faktura": {"entity": "f"}})]).annotate(doc)
    assert lookups(doc) == []


# ── provenance and guards ────────────────────────────────────────────────────


def test_every_lookup_records_how_it_matched():
    doc = document(("byt", ["být", "byt"]))
    build_morph_gazetteer([load_list(HERO_LIST)]).annotate(doc)
    lookup = lookups(doc)[0]
    assert lookup.features["matching"] == MATCHING
    assert lookup.features["source"] == "lm-hero"


def test_no_lookup_carries_a_score():
    """NL-17, at the annotation level."""
    doc = document(("tržby", ["tržba"]))
    build_morph_gazetteer([load_list(HERO_LIST)]).annotate(doc)
    for lookup in lookups(doc):
        assert not any(
            name in key.lower()
            for key in lookup.features.keys()
            for name in ("score", "confidence", "fuzzy", "similarity")
        )


def test_a_duplicate_list_id_is_refused():
    with pytest.raises(PackError):
        build_morph_gazetteer([a_list({"a": {}}), a_list({"b": {}})])


def test_the_gazetteer_is_reusable_across_documents():
    """One instance serves concurrent requests: the tries are the expensive
    part and the service holds one per loaded state."""
    gazetteer = build_morph_gazetteer([a_list({"tržba": {"entity": "trzba"}})])
    for _ in range(3):
        doc = document(("tržby", ["tržba"]))
        gazetteer.annotate(doc)
        assert len(lookups(doc)) == 1
