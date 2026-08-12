# SPDX-License-Identifier: Apache-2.0
"""NLS-P7.3 T5/T6 — the LM hero at the wheel tier, both variants.

    "Porovnej tržby Kauflandu za loňský rok s letošním"

Snapshot -> tokenize -> annotate -> match-if-any gazetteer -> P1 rule phases ->
`QueryPattern`. No engine, no model, no network: everything the hero needs is a
28-row fixture snapshot and the wheel.

Both variants must reach the same pattern. The diacritics-less twin resolves
through the compiled fold index, which is the claim the whole B-F4-α design
rests on, and *Kauflandu* — the one word the curated lexicon does not have — is
both the miss that goes to the enrichment queue (the NLS-P9 loop's entry point)
and the name candidate that makes the query match today.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from ttrnlp.gazetteer import load_list
from ttrnlp.morph.annotate import FEATURE_PROVENANCE, annotate_morph, build_document
from ttrnlp.morph.gazetteer import build_morph_gazetteer
from ttrnlp.morph.helpers import feats_has, lemma_any, upos_any
from ttrnlp.morph.records import (
    MATCHED_EXACT,
    PROVENANCE_LEXICON,
    PROVENANCE_STATISTICAL,
)
from ttrnlp.morph.snapshot import load_morph
from ttrnlp.rules import build_pack
from ttrnlp.rules.pipeline import run_phases

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "morph"
SNAPSHOT = FIXTURES / "cs-test.morph.snap"

HERO = "Porovnej tržby Kauflandu za loňský rok s letošním"
HERO_FOLDED = "porovnej trzby kauflandu za lonsky rok s letosnim"


@pytest.fixture(scope="module")
def state():
    return load_morph([SNAPSHOT])


@pytest.fixture(scope="module")
def pack():
    return build_pack(FIXTURES / "lm-hero.pack.yaml")


@pytest.fixture(scope="module")
def gazetteer():
    return build_morph_gazetteer([load_list(FIXTURES / "lm-hero.list.yaml")])


def run(text: str, state, pack, gazetteer):
    events: list[tuple[str, str, str]] = []
    doc = build_document(text)
    annotate_morph(doc, state, miss_sink=lambda *e: events.append(e), world="lm-test")
    gazetteer.annotate(doc)
    run_phases(doc, [pack])
    return doc, events


def patterns(doc):
    return sorted(doc.annset("").with_type("QueryPattern"), key=lambda a: a.start)


@pytest.mark.parametrize("text", [HERO, HERO_FOLDED])
def test_the_hero_reaches_the_query_pattern(text, state, pack, gazetteer):
    doc, _ = run(text, state, pack, gazetteer)
    found = patterns(doc)
    assert len(found) == 1
    assert found[0].features["query"] == "porovnani_trzeb"
    assert found[0].features["nazev_subjektu"] in ("Kauflandu", "kauflandu")


@pytest.mark.parametrize("text", [HERO, HERO_FOLDED])
def test_the_unknown_entity_is_reported_and_marked(text, state, pack, gazetteer):
    """One miss, and it is the word the query is about."""
    doc, events = run(text, state, pack, gazetteer)
    assert [e[1] for e in events] == [text.split()[2]]
    assert events[0][0] == "lm-test"
    token = next(
        t
        for t in doc.annset("").with_type("Token")
        if t.features["text"].lower().startswith("kaufland")
    )
    assert token.features[FEATURE_PROVENANCE] == PROVENANCE_STATISTICAL
    assert token.features["lemma"] in ("Kaufland", "kaufland")


def test_the_folded_variant_resolves_through_the_index(state):
    """…and every other word comes back as curated vocabulary."""
    doc = build_document(HERO_FOLDED)
    annotate_morph(doc, state)
    provenances = {
        t.features["text"]: t.features[FEATURE_PROVENANCE]
        for t in doc.annset("").with_type("Token")
    }
    assert provenances["trzby"] == PROVENANCE_LEXICON
    assert provenances["lonsky"] == PROVENANCE_LEXICON
    assert provenances["letosnim"] == PROVENANCE_LEXICON
    assert provenances["kauflandu"] == PROVENANCE_STATISTICAL


def test_exact_beats_folded_in_the_ranking(state):
    """B-F3, on the pair the fixture was built around.

    *byt* is a flat and *být* is "to be". Asking for the unaccented form gets
    both, and the one that matched AS WRITTEN leads — this is the ordering the
    hero's folded twin depends on for every one of its words.
    """
    result = state.lookup("byt")
    assert result.matched_via == MATCHED_EXACT
    assert [a.lemma for a in result.analyses] == ["byt", "být"]


# ── T5: the PAMPAC helpers, on the hero document ─────────────────────────────


@pytest.fixture
def hero_doc(state):
    doc = build_document(HERO)
    annotate_morph(doc, state)
    return doc


def _token(doc, text: str):
    tokens = doc.annset("").with_type("Token")
    return next(t for t in tokens if t.features["text"] == text)


def test_lemma_any_fires_on_the_hero(hero_doc):
    matcher = lemma_any("tržba", "obrat")
    assert matcher(_token(hero_doc, "tržby").features["lemmas"])
    assert not matcher(_token(hero_doc, "rok").features["lemmas"])


def test_lemma_any_reads_a_single_engine_lemma_too(hero_doc):
    assert lemma_any("tržba")(_token(hero_doc, "tržby").features["lemma"])


def test_upos_any_reads_the_analyses(hero_doc):
    analyses = _token(hero_doc, "tržby").features["analyses"]
    assert upos_any("NOUN", "PROPN")(analyses)
    assert not upos_any("VERB")(analyses)


def test_feats_has_splits_atoms_rather_than_substring_matching(hero_doc):
    analyses = _token(hero_doc, "tržby").features["analyses"]
    assert feats_has("Case=Gen")(analyses)
    assert not feats_has("Case=Gen2")(analyses)
    assert not feats_has("Case")(analyses)


def test_a_rule_built_in_python_can_use_the_helpers(hero_doc):
    """The helpers are for rules built in code — a pack is data, and a callable
    is not something YAML can carry. A pack author keeps writing
    ``lemma: tržba``, which the head-of-list feature still satisfies."""
    from gatenlp.pam.pampac import AnnAt, Pampac, Rule
    from gatenlp.pam.pampac.actions import AddAnn

    parser = AnnAt(type="Token", features={"lemmas": lemma_any("tržba", "obrat")})
    annset = hero_doc.annset("")
    Pampac(Rule(parser, AddAnn(type="TrzbaHere"))).run(
        hero_doc, list(annset.with_type("Token")), outset=annset
    )
    hits = list(hero_doc.annset("").with_type("TrzbaHere"))
    assert len(hits) == 1
    assert hero_doc.text[hits[0].start : hits[0].end] == "tržby"
