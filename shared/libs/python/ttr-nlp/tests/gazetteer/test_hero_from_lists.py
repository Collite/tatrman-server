# SPDX-License-Identifier: Apache-2.0
"""NLS-P2.1.T5 — the heroes, with the gazetteer where the hand-adding was.

`test_hero.py` builds each hero's `Lookup` annotations from its `expected.yaml`,
and says in its own docstring why: that file is the contract between the phases,
and P2 has to produce exactly those Lookups from a list file instead. This is
that half.

The test is deliberately *not* a rewrite of `test_hero.py`. That suite keeps
running unchanged — it is the proof that the rules still work off the contract —
and this one proves the gazetteer satisfies the contract, feature for feature and
offset for offset. Both together mean the pipeline is real end to end, and either
one failing says which half broke.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from tests.conftest import load_engines, load_expected
from ttrnlp.doc import build_document
from ttrnlp.gazetteer import build_gazetteer, load_list
from ttrnlp.rules import build_pack
from ttrnlp.rules.pipeline import run_phases

LISTS = Path(__file__).parent.parent / "fixtures" / "lists" / "valid"
PACKS = Path(__file__).parent.parent / "fixtures" / "packs" / "valid"

HEROES = ["hero-cs-invoices", "hero-cs-role"]

HERO_LIST = LISTS / "dfp-entity-aliases.list.yaml"


def hero_document(name: str, *, lane: str):
    """The hero document with the gazetteer's Lookups on it — no hand-adding.

    The default lane is modelled the way `test_hero.py` models it: drop the
    NameTag result, because an unrouted `NER.cs` never produced one.
    """
    fixture = load_engines(name)
    engines = fixture["engines"]
    if lane == "default":
        engines = [
            {**engine, "entities": []}
            for engine in engines
            if engine["engine"] != "nametag3"
        ]

    doc = build_document(fixture["text"], engines, language=fixture["language"])
    added = build_gazetteer([load_list(HERO_LIST)]).annotate(doc)
    return doc, load_expected(name), added


def produced_lookups(doc):
    return sorted(doc.annset("").with_type("Lookup"), key=lambda a: a.start)


@pytest.mark.parametrize("hero", HEROES)
@pytest.mark.parametrize("lane", ["option", "default"])
def test_the_gazetteer_produces_exactly_the_expected_lookups(hero, lane):
    """Offsets, type, span text and every feature — including the provenance the
    expected files spell out (`source: dfp-entity-aliases`, `matching: lemma`)."""
    doc, expected, added = hero_document(hero, lane=lane)

    produced = produced_lookups(doc)
    assert added == len(expected["lookups"])
    assert len(produced) == len(expected["lookups"]), [
        (a.start, a.end, dict(a.features)) for a in produced
    ]

    for actual, want in zip(produced, expected["lookups"], strict=True):
        assert (actual.type, actual.start, actual.end) == (
            want["type"],
            want["start"],
            want["end"],
        )
        assert doc.text[actual.start : actual.end] == want["text"]
        assert dict(actual.features) == want["features"]


@pytest.mark.parametrize("hero", HEROES)
@pytest.mark.parametrize("lane", ["option", "default"])
def test_the_hero_answer_survives_the_swap(hero, lane):
    """The point of the whole phase: gazetteer-produced Lookups drive the same
    rules to the same `QueryPattern` the hand-added ones did."""
    doc, expected, _ = hero_document(hero, lane=lane)
    run_phases(doc, [build_pack(PACKS / f"{hero}.pack.yaml")])

    produced = sorted(doc.annset("").with_type("QueryPattern"), key=lambda a: a.start)
    wanted = expected["query_patterns"][f"{lane}_lane"]

    assert len(produced) == len(wanted), [
        (a.start, a.end, dict(a.features)) for a in produced
    ]
    for actual, want in zip(produced, wanted, strict=True):
        assert (actual.start, actual.end) == (want["start"], want["end"])
        for key, value in want["features"].items():
            assert actual.features.get(key) == value


def test_lemma_mode_is_what_carries_the_invoices_hero():
    """Both matched words are inflected — `faktury` and `zákazníka`. Neither is
    byte-equal to its list term, so a list in any other mode would produce
    nothing here, and this is the sentence the design leads with."""
    doc, _, _ = hero_document("hero-cs-invoices", lane="option")
    spans = [doc.text[a.start : a.end] for a in produced_lookups(doc)]

    assert spans == ["faktury", "zákazníka"]
    assert all(
        span != feature["entity"]
        for span, feature in zip(
            spans, [dict(a.features) for a in produced_lookups(doc)], strict=True
        )
    )
