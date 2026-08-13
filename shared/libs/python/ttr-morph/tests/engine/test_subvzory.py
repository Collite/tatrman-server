# SPDX-License-Identifier: Apache-2.0
"""NLS-P8.1 T5 — the sub-vzor inventory (B-O5).

A sub-vzor is a parent plus a narrowing, and it exists for two consumers. The
compiler uses it to generate a paradigm that a bare pattern would get slightly
wrong. The NLS-P9 pattern-guesser uses its `hints` to *propose* it from nothing
but a surface form — which is why the inventory is data in the tables rather
than a list in a design document, and why the hints are asserted here.
"""

from __future__ import annotations

import re

import pytest

from ttrmorph.engine import generate, load

#: The B-O5 inventory, by name. Design §7 lists it as a requirement on the
#: guesser; this is that list made checkable.
REQUIRED = (
    "hrad-proper",
    "hrad-foreign",
    "predseda-name",
    "pan-o",
    "indeclinable-m",
    "indeclinable-n",
    "zena-proper",
    "ruze-proper",
    "adj-ova",
    "muzeum-um",
    "acronym-m",
)


@pytest.fixture(scope="module")
def tables():
    return load("cs")


@pytest.mark.parametrize("name", REQUIRED)
def test_the_inventory_is_present(name, tables):
    assert name in tables.vzory
    assert tables.vzory[name].parent is not None


@pytest.mark.parametrize("name", REQUIRED)
def test_every_subvzor_carries_guesser_hints(name, tables):
    """No hints means the guesser can never propose it.

    A narrowing the guesser cannot reach is one an analyst has to know exists,
    which is the failure mode the inventory was written to avoid.
    """
    hints = tables.vzory[name].hints
    assert "lemma_pattern" in hints, f"{name} has no surface-shape hint"
    assert "capitalized" in hints
    re.compile(hints["lemma_pattern"])


@pytest.mark.parametrize("name", REQUIRED)
def test_a_subvzor_narrows_its_parent(name, tables):
    """Something must actually differ, or the entry is noise.

    For most of the inventory the difference is in the forms — an implied
    flag, a different citation ending, an overridden cell. For three of them
    (`predseda-name`, `zena-proper`, `ruze-proper`) it is **only the part of
    speech**, and that is a real narrowing rather than a loophole: upos is what
    LM-10 routes on, so proposing one of these instead of its parent is what
    sends the entry to a world entity layer rather than the core queue.

    Those three are consequently unreachable from `classify` — their parent
    generates the identical paradigm and comes first in table order. They are
    guesser targets, and the guesser works from a surface form, not a table.
    """
    vzor = tables.vzory[name]
    parent = tables.vzory[vzor.parent]
    narrowed = (
        vzor.implied_flags
        or vzor.strip != parent.strip
        or vzor.upos != parent.upos
        or {(s.feats, s.endings) for s in vzor.slots}
        != {(s.feats, s.endings) for s in parent.slots}
    )
    assert narrowed, f"{name} is indistinguishable from {vzor.parent}"


# ── the two the task names ───────────────────────────────────────────────────


def test_a_proper_inanimate_declines_and_keeps_one_locative():
    """Kaufland — the hero's own word.

    The bare pattern offers a free locative doublet that native lexemes take
    and proper nouns do not. Generating the doublet here would put a form
    nobody writes into the world entity list, where it would match text that
    never named the company.
    """
    produced = generate("Kaufland", "hrad-proper")
    forms = {form for form, _ in produced}
    assert {
        "Kaufland",
        "Kauflandu",
        "Kauflandem",
        "Kauflande",
        "Kauflandy",
    } <= forms
    locatives = {
        form
        for form, feats in produced
        if feats == "Animacy=Inan|Case=Loc|Gender=Masc|Number=Sing"
    }
    assert locatives == {"Kauflandu"}


def test_an_adjectival_surname_takes_the_feminine_adjective_table():
    produced = {form for form, _ in generate("Nováková", "adj-ova")}
    assert produced == {
        "Nováková",
        "Novákové",
        "Novákovou",
        "Novákových",
        "Novákovým",
        "Novákovými",
    }


def test_an_adjectival_surname_is_only_feminine(tables):
    """`only_feats` is a restriction of the parent, not a copy of it.

    A copy would drift the first time the adjective table is corrected.
    """
    assert all("Gender=Fem" in slot.feats for slot in tables.vzory["adj-ova"].slots)
    assert len(tables.vzory["adj-ova"].slots) == 14


def test_the_hint_patterns_match_the_words_they_are_for(tables):
    """The guesser's prior, checked against the cases it was written from."""
    for name, lemma in (
        ("hrad-proper", "Kaufland"),
        ("hrad-foreign", "cyklus"),
        ("predseda-name", "Kundera"),
        ("pan-o", "Hugo"),
        ("indeclinable-n", "taxi"),
        ("zena-proper", "Ostrava"),
        ("ruze-proper", "Florencie"),
        ("adj-ova", "Nováková"),
        ("muzeum-um", "muzeum"),
        ("acronym-m", "ČEZ"),
    ):
        hints = tables.vzory[name].hints
        assert re.search(hints["lemma_pattern"], lemma), f"{name} misses {lemma}"
        if hints["capitalized"]:
            assert lemma[0].isupper(), f"{name} claims capitalized, {lemma} is not"
