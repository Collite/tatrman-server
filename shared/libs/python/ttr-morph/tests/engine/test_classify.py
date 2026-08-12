# SPDX-License-Identifier: Apache-2.0
"""NLS-P8.1 T6 — `classify`, the engine's inverse (contracts §4).

What this buys, concretely: an importer that reads an inflection table off
Wiktionary has no way to know whether the table is right, complete, or even
about the word it claims to be. Asking which (vzor, flags) *reproduce* it turns
that into a decision the engine can make — a fit becomes a compact entry, a
miss becomes a full-form entry carrying `LM-MORPH-005`, and neither is a guess.

The same question is the enrichment loop's auto-validation rule at NLS-P9.
"""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from ttrmorph.engine import classify, generate

GOLDEN = Path(__file__).resolve().parent / "golden"
CASES = sorted(GOLDEN.glob("*.yaml"))


def load_case(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


@pytest.mark.parametrize("path", CASES, ids=lambda p: p.stem)
def test_every_golden_table_classifies_back(path: Path):
    """The round trip, exactly — not "something equivalent".

    Where the answer is a different pattern from the one the case was
    generated with, the golden records that answer in `classify_as`. Several
    patterns really are interchangeable — a sub-vzor whose only content is an
    implied flag generates precisely what its parent generates with the flag
    written out — and `classify` returns the first fit in table order. Pinning
    which one keeps the assertion exact instead of loosening it to a shrug.
    """
    case = load_case(path)
    expected = case.get("classify_as") or {"vzor": case["vzor"], "flags": case["flags"]}
    answer = classify(case["table"])
    assert answer is not None, f"{case['lemma']}: no pattern fits its own table"
    vzor, flags = answer
    assert (vzor, list(flags)) == (expected["vzor"], expected["flags"])


@pytest.mark.parametrize("path", CASES, ids=lambda p: p.stem)
def test_the_answer_regenerates_the_table(path: Path):
    """Whatever it answers must actually reproduce the input.

    `classify_as` is a record of a decision; this is the check that the
    decision is sound. Together they say: the answer may not be the only fit,
    but it is always a fit.
    """
    case = load_case(path)
    vzor, flags = classify(case["table"])
    produced = generate(case["lemma"], vzor, flags)
    expected = {
        (form, feats) for feats, forms in case["table"].items() for form in forms
    }
    assert produced == expected


def test_a_corrupted_table_classifies_to_nothing():
    """One wrong cell and no pattern fits — which is the point.

    A subset match would accept a pattern that also generates forms nobody has
    written, and those forms would go into the artifact unchallenged. So the
    match is exact, and a table with one bad form is a full-form entry.
    """
    table: dict[str, list[str]] = {}
    for form, feats in generate("tržba", "žena"):
        table.setdefault(feats, []).append(form)
    assert classify(table) is not None
    table["Case=Dat|Gender=Fem|Number=Sing"] = ["tržbe"]
    assert classify(table) is None


def test_a_missing_cell_classifies_to_nothing():
    table: dict[str, list[str]] = {}
    for form, feats in generate("tržba", "žena"):
        table.setdefault(feats, []).append(form)
    table.pop("Case=Ins|Gender=Fem|Number=Plur")
    assert classify(table) is None


def test_an_empty_table_classifies_to_nothing():
    assert classify({}) is None


def test_a_single_form_string_is_accepted():
    """Contracts §4 types the table as feats -> form, one string per cell.

    Lists are the internal shape because a cell can hold a doublet; a caller
    handing over the documented shape must not have to know that.
    """
    table = {feats: form for form, feats in generate("stavení", "stavení")}
    assert classify(table) == ("stavení", ())


def test_the_answer_is_stable_across_calls():
    """Deterministic: the same table, the same answer, always.

    The search is ordered — patterns in file order, flag subsets smallest
    first — so the answer is a function of the input and the tables, not of
    dictionary iteration luck. An importer that classified the same word two
    ways on two runs would produce a snapshot whose content hash moved on its
    own.
    """
    table: dict[str, list[str]] = {}
    for form, feats in generate("matka", "žena", ["fleeting-e", "palatal"]):
        table.setdefault(feats, []).append(form)
    answers = {classify(table) for _ in range(5)}
    assert answers == {("žena", ("fleeting-e", "palatal"))}
