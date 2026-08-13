# SPDX-License-Identifier: Apache-2.0
"""The named acceptance cases and their runner (T1/T3).

Two things are under test and they are different in kind.

The **runner** is ordinary code: it must fail a case that is wrong, pass one
that is right, and — the part worth writing tests for — fail them *distinctly*,
naming the token and the mismatch. A gate that reports "case failed" and makes
the reader re-run it with more flags to find out why is a gate people learn to
skip.

The **cases** are the acceptance criteria themselves (S-7 → arc gate 7), and
the test over them asserts that all three exist, parse, and pass against the
artifact this repo compiles. That test is the one that fails the day the
lexicon regresses; it is deliberately not clever.
"""

from __future__ import annotations

import pytest
from ttrnlp.morph import load_morph

from ttrmorph.compile.snapshot import compile_layers
from ttrmorph.eval.cases import (
    CASES_DIR,
    CaseError,
    load_cases,
    read_case,
    run_case,
    run_cases,
)

CASE_NAMES = {"hero-compare", "hero-folded", "nl-hero-lemma-path"}


@pytest.fixture(scope="module")
def state(tmp_path_factory):
    """A snapshot with the hero vocabulary and one deliberate fold collision."""
    directory = tmp_path_factory.mktemp("cases")
    layer = directory / "core-hand.morph.yaml"
    layer.write_text(
        "layer: core-hand\nversion: 1\nlanguage: cs\nlicense: suite\n"
        "attribution: null\nentries:\n"
        "  - { lemma: tržba, upos: NOUN, vzor: žena, flags: [fleeting-e],"
        " provenance: manual }\n"
        "  - { lemma: byt, upos: NOUN, vzor: hrad, provenance: manual }\n"
        "  - { lemma: být, upos: AUX, provenance: manual,"
        ' forms: [{form: být, feats: "VerbForm=Inf"}] }\n',
        encoding="utf-8",
    )
    result = compile_layers([str(layer)], snapshot_version="0.0.0")
    assert result.ok, [d.message for d in result.diagnostics]
    for name, text in result.outputs.items():
        (directory / name).write_text(text, encoding="utf-8")
    return load_morph([str(directory / "cs.morph.snap")])


def case_file(tmp_path, body: str):
    path = tmp_path / "x.case.yaml"
    path.write_text(body, encoding="utf-8")
    return read_case(path)


# ── the shipped cases ────────────────────────────────────────────────────────


def test_all_three_named_cases_exist():
    """S-7 names exactly three. A missing one is a gate that stopped gating."""
    assert {case.case for case in load_cases()} == CASE_NAMES


def test_every_case_carries_its_reasoning():
    """These files are read by whoever is deciding whether to ship. A case with
    no note is a line of YAML nobody can weigh."""
    for case in load_cases():
        assert case.note.strip(), f"{case.case} has no note"


def test_the_shipped_cases_pass_against_the_shipped_artifact(repo_state):
    results = run_cases(load_cases(), repo_state)
    failures = {r.case.case: r.failures for r in results if not r.passed}
    assert not failures, failures


def test_the_cases_directory_is_where_contracts_puts_it():
    assert CASES_DIR.name == "cases"
    assert CASES_DIR.parent.name == "eval"


# ── the runner ───────────────────────────────────────────────────────────────


def test_a_correct_case_passes(state, tmp_path):
    case = case_file(
        tmp_path,
        "case: ok\ntext: tržby\ntokens:\n"
        "  - {form: tržby, lemma: tržba, matched_via: exact, provenance: lexicon}\n",
    )
    assert run_case(case, state).passed


@pytest.mark.parametrize(
    ("token", "expected"),
    [
        ("{form: tržby, lemma: obrat}", "head lemma is 'tržba'"),
        ("{form: tržby, matched_via: folded}", "matched_via is 'exact'"),
        ("{form: tržby, provenance: statistical}", "provenance is 'lexicon'"),
        ("{form: tržby, absent: true}", "expected NO lexicon answer"),
    ],
)
def test_each_kind_of_mismatch_says_which_one_it_is(state, tmp_path, token, expected):
    case = case_file(tmp_path, f"case: x\ntext: tržby\ntokens:\n  - {token}\n")
    failures = run_case(case, state).failures
    assert len(failures) == 1
    assert expected in failures[0]


def test_every_failing_token_is_reported_not_just_the_first(state, tmp_path):
    case = case_file(
        tmp_path,
        "case: x\ntext: tržby tržbě\ntokens:\n"
        "  - {form: tržby, lemma: nope}\n"
        "  - {form: tržbě, lemma: nope}\n",
    )
    assert len(run_case(case, state).failures) == 2


def test_a_word_with_no_analysis_is_a_failure_not_a_skip(state, tmp_path):
    case = case_file(
        tmp_path, "case: x\ntext: xyzzy\ntokens:\n  - {form: xyzzy, lemma: xyzzy}\n"
    )
    assert "no analysis at all" in run_case(case, state).failures[0]


def test_an_absent_token_may_legitimately_have_no_answer(state, tmp_path):
    case = case_file(
        tmp_path,
        "case: x\ntext: Kaufland\ntokens:\n  - {form: Kaufland, absent: true}\n",
    )
    assert run_case(case, state).passed


def test_the_observed_table_is_recorded_even_on_a_pass(state, tmp_path):
    """The report prints the answer, not only the verdict."""
    case = case_file(tmp_path, "case: x\ntext: tržby\ntokens: []\n")
    assert run_case(case, state).observed == [("tržby", "tržba", "exact", "lexicon")]


def test_a_case_that_expects_a_token_the_tokenizer_never_makes_fails(state, tmp_path):
    """Otherwise a typo in a case file is a token that is silently never
    checked, and the case passes by not testing anything."""
    case = case_file(
        tmp_path, "case: x\ntext: tržby\ntokens:\n  - {form: trzby, lemma: tržba}\n"
    )
    assert "does not produce" in run_case(case, state).failures[0]


# ── the exact-before-folded law ──────────────────────────────────────────────


def test_a_form_in_the_artifact_answers_from_itself(state, tmp_path):
    """*byt* and *být* fold together. A query that typed the accent has already
    chosen, and one that did not gets both — exact-derived first (contracts §5)."""
    case = case_file(
        tmp_path,
        "case: x\ntext: byt\ntokens:\n  - {form: byt, lemmas: [byt, být]}\n",
    )
    assert run_case(case, state).passed


def test_the_ordering_is_asserted_for_every_token_of_every_case(state, tmp_path):
    """Not only where a case remembered to say so: the runner checks that no
    token present in the artifact verbatim was answered via the fold index."""
    case = case_file(tmp_path, "case: x\ntext: byt\ntokens: []\n")
    result = run_case(case, state)
    assert result.passed
    assert result.observed[0][2] == "exact"


def test_a_ranked_expectation_catches_the_wrong_order(state, tmp_path):
    case = case_file(
        tmp_path,
        "case: x\ntext: byt\ntokens:\n  - {form: byt, lemmas: [být, byt]}\n",
    )
    assert "ranked lemmas are" in run_case(case, state).failures[0]


# ── malformed cases are broken gates, not failed cases ───────────────────────


@pytest.mark.parametrize(
    "body",
    [
        "case: x\ntokens: []\n",
        "case: x\ntext: a\ntokens:\n  - {lemma: a}\n",
        "case: x\ntext: a\ntokens:\n  - {form: a, lema: a}\n",
        "- not a mapping\n",
    ],
)
def test_a_malformed_case_raises_rather_than_failing_quietly(tmp_path, body):
    with pytest.raises(CaseError):
        case_file(tmp_path, body)


def test_an_empty_cases_directory_is_an_error(tmp_path):
    with pytest.raises(CaseError):
        load_cases(tmp_path)
