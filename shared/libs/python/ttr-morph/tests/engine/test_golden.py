# SPDX-License-Identifier: Apache-2.0
"""NLS-P8.1 T3 — the golden paradigm tables.

One assertion, run over every case: what the engine generates and what the
golden file says are the **same set**, no missing form and no extra one. Both
directions matter for different reasons. A missing form is a word a user can
type that the lexicon will not recognise; an extra form is worse, because it
reaches the snapshot, then the generation-expanded gazetteer lists, and matches
text that never contained the entity.
"""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from ttrmorph.engine import generate, load

GOLDEN = Path(__file__).resolve().parent / "golden"
CASES = sorted(GOLDEN.glob("*.yaml"))


def load_case(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def as_pairs(table: dict[str, list[str]]) -> set[tuple[str, str]]:
    return {(form, feats) for feats, forms in table.items() for form in forms}


def test_there_are_golden_tables():
    """A glob that silently matched nothing would make this whole file pass."""
    assert len(CASES) >= 30


@pytest.mark.parametrize("path", CASES, ids=lambda p: p.stem)
def test_generate_reproduces_the_table(path: Path):
    case = load_case(path)
    produced = generate(case["lemma"], case["vzor"], case["flags"])
    expected = as_pairs(case["table"])

    missing = expected - produced
    extra = produced - expected
    assert not missing, f"{case['lemma']}: not generated: {sorted(missing)}"
    assert not extra, f"{case['lemma']}: generated, not in the table: {sorted(extra)}"


@pytest.mark.parametrize("path", CASES, ids=lambda p: p.stem)
def test_the_citation_form_is_in_its_own_paradigm(path: Path):
    """The lemma must be one of the forms.

    Obvious until a strip rule is wrong, and then it is the first thing to
    break: an entry whose own citation form is not in its paradigm cannot be
    looked up by the word the analyst wrote it as.
    """
    case = load_case(path)
    forms = {form for form, _ in generate(case["lemma"], case["vzor"], case["flags"])}
    assert case["lemma"] in forms


@pytest.mark.parametrize("path", CASES, ids=lambda p: p.stem)
def test_the_flags_are_written_in_canonical_order(path: Path):
    """Goldens record the canonical order, so `classify`'s answer compares."""
    case = load_case(path)
    tables = load("cs")
    assert tuple(case["flags"]) == tables.order_flags(case["flags"])


def test_every_pattern_has_a_golden():
    """No pattern ships untested.

    Sub-vzory included: a narrowing that nobody generated is a narrowing that
    may not narrow anything, and the guesser at NLS-P9 will propose it.
    """
    covered = {load_case(path)["vzor"] for path in CASES}
    declared = set(load("cs").vzory)
    assert declared - covered == set()
