# SPDX-License-Identifier: Apache-2.0
"""NLS-P1.2.T7 — hero acceptance, end to end within the wheel.

The conformance matrix proves the semantics on toy documents. This proves the
whole thing does the job it was built for: real engine output from the P0
fixture corpus, real rule packs, and the `QueryPattern` the design promised.

The `Lookup` annotations are hand-added here from each hero's `expected.yaml`.
That file is the contract between phases: P2's gazetteer has to produce exactly
these from list files, at which point the hand-adding goes away and this test
keeps passing unchanged.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from tests.conftest import load_engines, load_expected
from ttrnlp.doc import build_document
from ttrnlp.rules.compiler import compile_pack
from ttrnlp.rules.dsl import load_pack
from ttrnlp.rules.pipeline import run_phases

PACKS = Path(__file__).parent.parent / "fixtures" / "packs" / "valid"

HEROES = ["hero-cs-invoices", "hero-cs-role"]


def build_hero(name: str, *, lane: str):
    """Build the hero document for a lane, with its expected Lookups added.

    The default lane is modelled by dropping the NER annotations, which is
    exactly what the NL-14 degrade is: `NER.cs` is unrouted, so those
    annotations are never produced and the rules must cope.
    """
    fixture = load_engines(name)
    expected = load_expected(name)

    engines = fixture["engines"]
    if lane == "default":
        engines = [
            {**engine, "entities": []}
            for engine in engines
            if engine["engine"] != "nametag3"
        ]

    doc = build_document(fixture["text"], engines, language=fixture["language"])
    annset = doc.annset("")
    for lookup in expected["lookups"]:
        annset.add(
            lookup["start"], lookup["end"], lookup["type"], dict(lookup["features"])
        )
    return doc, expected


def query_patterns(doc):
    return sorted(doc.annset("").with_type("QueryPattern"), key=lambda a: a.start)


@pytest.mark.parametrize("hero", HEROES)
@pytest.mark.parametrize("lane", ["option", "default"])
def test_hero_produces_the_expected_query_pattern(hero, lane):
    doc, expected = build_hero(hero, lane=lane)
    pack = compile_pack(load_pack(PACKS / f"{hero}.pack.yaml"))

    run_phases(doc, [pack])

    produced = query_patterns(doc)
    wanted = expected["query_patterns"][f"{lane}_lane"]
    assert len(produced) == len(wanted), (
        f"{hero}/{lane}: expected {len(wanted)} QueryPattern(s), got "
        f"{[(a.start, a.end, dict(a.features)) for a in produced]}"
    )
    for actual, want in zip(produced, wanted, strict=True):
        assert (actual.start, actual.end) == (want["start"], want["end"])
        for key, value in want["features"].items():
            assert actual.features.get(key) == value


def test_the_option_lane_uses_the_ner_rule_and_the_default_lane_does_not():
    """The two lanes must reach the same answer by DIFFERENT routes.

    Asserting only the output would pass even if the fallback rule were firing
    in both lanes, which would mean the NER path was dead and nobody noticed.
    """
    from ttrnlp.rules.actions import apply_firings
    from ttrnlp.rules.executor import run_rules
    from ttrnlp.rules.pipeline import run_phase, visible_annotations

    pack = compile_pack(load_pack(PACKS / "hero-cs-invoices.pack.yaml"))
    lift, query = pack.phases

    fired = {}
    for lane in ("option", "default"):
        doc, _ = build_hero("hero-cs-invoices", lane=lane)
        run_phase(doc, lift)  # phase 1 must have run for phase 2 to see candidates
        firings = run_rules(
            doc, visible_annotations(doc, query), query.rules, query.control
        )
        apply_firings(doc, firings)
        fired[lane] = [f.rule.rule for f in firings]

    assert fired["option"] == ["FakturyZakaznikaNer"]
    assert fired["default"] == ["FakturyZakaznikaFallback"]


def test_the_default_lane_really_has_no_ner_annotation():
    doc, _ = build_hero("hero-cs-invoices", lane="default")
    assert not list(doc.annset("").with_type("ORGANIZATION"))

    option, _ = build_hero("hero-cs-invoices", lane="option")
    assert len(list(option.annset("").with_type("ORGANIZATION"))) == 1


def test_the_parameter_value_is_span_text_not_a_normalised_id():
    """contracts §5: the value is the binding's `@string`.

    For the role hero that distinction is visible — the Lookup carries
    `value: obchodni_zastupce`, and what must reach the parameter is the text
    the user actually wrote.
    """
    doc, _ = build_hero("hero-cs-role", lane="option")
    run_phases(doc, [compile_pack(load_pack(PACKS / "hero-cs-role.pack.yaml"))])

    (pattern,) = query_patterns(doc)
    assert pattern.features["nazev_role"] == "obchodní zástupce"
    assert pattern.features["query"] == "role"


def test_hero_packs_pass_the_cross_checks():
    from ttrnlp.rules import prepare_pack

    for hero in HEROES:
        prepare_pack(PACKS / f"{hero}.pack.yaml")
