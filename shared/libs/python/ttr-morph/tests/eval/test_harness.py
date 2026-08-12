# SPDX-License-Identifier: Apache-2.0
"""The harness: what it measures without a corpus, and what the gate refuses.

The load-bearing test in this file is `test_a_covered_lemma_with_one_form_is_thin`.
Target coverage without that distinction lied once, in the way that matters:
*zobrazit* arrived from the corpus as a bare infinitive, counted as covered, and
the hero sentence starting *Zobraz* had no analysis at all. 147/149 looked fine.
So the number a release is read off now separates "the artifact knows this word"
from "the artifact can recognise this word in a sentence".

The gate's own tests are about what it *refuses*, not what it allows: an
artifact that shrank, a target that went missing, a metric that fell. A gate
tested only on the happy path is a gate nobody has watched fail.
"""

from __future__ import annotations

import json

import pytest
from ttrnlp.morph import load_morph

from ttrmorph.compile.snapshot import compile_layers
from ttrmorph.eval import harness
from ttrmorph.eval.metrics import Counts, Metrics

LAYER = (
    "layer: core-hand\nversion: 1\nlanguage: cs\nlicense: suite\n"
    "attribution: null\nentries:\n"
    "  - {{ lemma: tržba, upos: NOUN, vzor: žena, flags: [fleeting-e],"
    " provenance: manual }}\n"
    "  - {{ lemma: za, upos: ADP, provenance: manual,"
    ' forms: [{{form: za, feats: "Case=Acc"}}] }}\n'
    "{extra}"
)


def build(write_and_load, tmp_path, extra=""):
    path = tmp_path / "core-hand.morph.yaml"
    path.write_text(LAYER.format(extra=extra), encoding="utf-8")
    result = compile_layers([str(path)], snapshot_version="0.1.0")
    assert result.ok, [d.message for d in result.diagnostics]
    return write_and_load(result.outputs, tmp_path)


# ── target coverage ──────────────────────────────────────────────────────────


def test_a_lemma_that_resolves_to_itself_is_covered(write_and_load, tmp_path):
    state = build(write_and_load, tmp_path)
    covered, missing, thin = harness.target_coverage(state, ["tržba"])
    assert (covered, missing, thin) == (1, [], [])


def test_a_lemma_nothing_answers_for_is_missing(write_and_load, tmp_path):
    state = build(write_and_load, tmp_path)
    _, missing, _ = harness.target_coverage(state, ["kvartál"])
    assert missing == ["kvartál"]


def test_a_lemma_that_answers_with_a_DIFFERENT_lemma_is_missing(
    write_and_load, tmp_path
):
    """A fold hit on somebody else's word is a miss wearing a hit's clothes."""
    state = build(write_and_load, tmp_path)
    _, missing, _ = harness.target_coverage(state, ["trzba"])
    assert missing == ["trzba"]


def test_a_covered_lemma_with_one_form_is_thin(write_and_load, tmp_path):
    """⚑ The check that would have caught `zobrazit` before a case did."""
    state = build(
        write_and_load,
        tmp_path,
        "  - { lemma: zobrazit, upos: VERB, provenance: manual,"
        ' forms: [{form: zobrazit, feats: "VerbForm=Inf"}] }\n',
    )
    covered, missing, thin = harness.target_coverage(state, ["zobrazit"])
    assert (covered, missing) == (1, [])
    assert thin == ["zobrazit"]


def test_a_word_that_does_not_inflect_is_never_thin(write_and_load, tmp_path):
    """*za* is a preposition with one form and that is the whole paradigm."""
    state = build(write_and_load, tmp_path)
    _, _, thin = harness.target_coverage(state, ["za"])
    assert thin == []


def test_the_target_list_is_the_authored_one():
    lemmas = harness.target_lemmas()
    assert "tržba" in lemmas and "zobrazit" in lemmas
    assert len(lemmas) == len(set(lemmas))


def test_a_target_file_with_no_groups_is_an_error(tmp_path):
    path = tmp_path / "t.yaml"
    path.write_text("language: cs\nwords: [a]\n", encoding="utf-8")
    with pytest.raises(ValueError, match="groups"):
        harness.target_lemmas(path)


# ── the report ───────────────────────────────────────────────────────────────


def test_a_report_without_a_corpus_says_so(repo_state):
    report = harness.build_report(repo_state)
    assert report.metrics is None
    text = harness.render(report)
    assert "Not run" in text
    assert "baseline.json" in text


def test_the_report_names_the_wave_c_headroom(repo_state):
    """The template has to say what the coverage gap IS, every time, because
    the number is otherwise read as a defect in this arc."""
    report = harness.build_report(repo_state)
    report.metrics = Metrics(total=Counts(tokens=10, answered=8, in_set=8, head=7))
    report.corpus = "test"
    assert "Wave C headroom" in harness.render(report)


def test_the_json_shape_is_stable(repo_state):
    payload = harness.build_report(repo_state).as_dict()
    assert set(payload) == {"artifact", "targets", "cases"}
    assert payload["cases"]["total"] == 3
    json.dumps(payload)  # it has to be serializable, which is the point of it


# ── the gate ─────────────────────────────────────────────────────────────────


def base(**overrides):
    baseline = {
        "artifact": {"rows": 10, "forms": 8},
        "targets": {"covered": 2, "missing": [], "thin": []},
        "corpus": {"coverage": 0.5, "lemma_in_set": 0.9, "head_of_list": 0.8},
    }
    baseline.update(overrides)
    return baseline


def report(**kwargs):
    defaults = dict(
        version="0.1.0",
        content_hash="x",
        rows=10,
        forms=8,
        layers=(),
        fold_collisions=0,
        targets_total=2,
        targets_covered=2,
    )
    defaults.update(kwargs)
    return harness.Report(**defaults)


def test_an_unchanged_artifact_passes_the_gate():
    assert harness.check(report(), base()) == []


def test_a_shrinking_artifact_fails():
    failures = harness.check(report(rows=9), base())
    assert "a shrinking one is a layer that stopped compiling" in failures[0]


def test_a_growing_artifact_is_fine():
    assert harness.check(report(rows=99, forms=99), base()) == []


def test_a_lost_target_fails_and_names_it():
    failures = harness.check(
        report(targets_covered=1, targets_missing=["tržba"]), base()
    )
    assert "tržba" in failures[0]


def test_a_new_thin_target_fails_and_names_it():
    failures = harness.check(report(targets_thin=["zobrazit"]), base())
    assert "zobrazit" in failures[0]
    assert "THIN" in failures[0]


def test_a_failing_case_is_a_gate_failure():
    class Named:
        case = "hero-compare"

    class FailedCase:
        case = Named()
        failures = ["'Zobraz': no analysis at all"]
        passed = False

    failures = harness.check(report(cases=[FailedCase()]), base())
    assert failures == ["case hero-compare: 'Zobraz': no analysis at all"]


def test_a_metric_that_fell_fails_only_beyond_the_tolerance():
    metrics = Metrics(total=Counts(tokens=100, answered=50, in_set=45, head=39))
    # head-of-list = 39/50 = 0.78, baseline 0.80, tolerance 0.005 -> fails
    failures = harness.check(report(metrics=metrics), base())
    assert any("head_of_list" in failure for failure in failures)


def test_corpus_metrics_are_not_gated_when_no_corpus_ran():
    """The release gate has no oracle in hand and must not pretend otherwise."""
    assert harness.check(report(), base()) == []


def test_a_missing_baseline_says_how_to_make_one(tmp_path):
    with pytest.raises(FileNotFoundError, match="write-baseline"):
        harness.read_baseline(tmp_path / "nope.json")


def test_the_committed_baseline_matches_the_committed_artifact(repo_state):
    """The gate reference and the lexicon are committed together, so the
    reference must describe the lexicon in the same commit — otherwise the
    first tag cut after a merge fails for a reason nobody introduced."""
    baseline = harness.read_baseline()
    assert harness.check(harness.build_report(repo_state), baseline) == []


def test_the_baseline_is_a_subset_of_a_report(repo_state):
    baseline = harness.baseline_from(harness.build_report(repo_state))
    assert set(baseline) >= {"note", "artifact", "targets", "cases"}
    assert "content_hash" not in baseline["artifact"], (
        "the hash changes with every recompile; a gate that pinned it would "
        "fail on a whitespace edit to a layer file"
    )


# ── the loader ───────────────────────────────────────────────────────────────


def test_load_state_takes_the_member_files_too(write_and_load, tmp_path):
    state = build(write_and_load, tmp_path)
    again = load_morph([str(tmp_path / "cs.morph.snap")])
    assert again.stats().forms == state.stats().forms
