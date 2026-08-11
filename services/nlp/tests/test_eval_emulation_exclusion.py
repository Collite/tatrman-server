# SPDX-License-Identifier: Apache-2.0
"""RV-P8.3 T3/T4 — the eval harness's exclusion and quality lanes, hermetically.

The harness itself needs a live front; its *decisions* do not, and those are the
part that can be quietly wrong. What is pinned here:

* which ops a corpus case asserts (derived from the case, so a fixture that gains
  entities gains the exclusion without anyone updating a second field);
* that emulation off changes nothing — zero exclusions, and a report identical in
  shape to the one this lane produced before RV-P8 existed;
* that emulation on names the excluded cases **in the report**, above the metrics,
  because a reduced run's numbers mean something different from a full run's.

⚠ The gating conformance tier is not touched by any of this and does not need to
be: `just conformance-service-level` is Kotlin and hermetic — `MatchQualityCorpusTest`
runs against a deterministic fixture `Lemmatizer` with no nlp dependency, and the
resolver tiers fake nlp outright. "Conformance unaffected when emulation is on" is
true there by construction; wiring a predicate into that runner would be dead code
claiming to guard something. This lane is where live engine output is scored.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

# `eval/` is a script directory, not a package (it is run with `python
# eval/run_eval.py`), so it is loaded by path. It must be registered in
# `sys.modules` BEFORE execution: `@dataclass` resolves its own module through
# `sys.modules[cls.__module__]` and raises on a module that is not there yet.
EVAL = Path(__file__).resolve().parents[1] / "eval" / "run_eval.py"
_spec = importlib.util.spec_from_file_location("run_eval", EVAL)
assert _spec is not None and _spec.loader is not None
run_eval = importlib.util.module_from_spec(_spec)
sys.modules["run_eval"] = run_eval
_spec.loader.exec_module(run_eval)


def a_case(case_id: str, lang: str, expected: dict):
    return run_eval.EvalEntry(id=case_id, question="?", lang=lang, expected=expected)


MATRIX_WITH_EMULATION = [
    {"language": "cs", "op": "LEMMATIZE", "engine": "morphodita", "tier": "SELF_HOSTED_PINNED"},
    {"language": "cs", "op": "NER", "engine": "llm_emulated", "tier": "REMOTE_UNPINNED"},
    {"language": "en", "op": "NER", "engine": "spacy", "tier": "SELF_HOSTED_PINNED"},
]

MATRIX_WITHOUT = [
    {"language": "cs", "op": "LEMMATIZE", "engine": "morphodita", "tier": "SELF_HOSTED_PINNED"},
    {"language": "en", "op": "NER", "engine": "spacy", "tier": "SELF_HOSTED_PINNED"},
]


class TestAssertedOps:
    def test_a_case_asserts_what_it_actually_carries(self):
        case = a_case(
            "cs-q-001",
            "cs",
            {
                "tokens": [{"text": "Kdo", "lemma": "kdo", "upos": "PRON"}],
                "entities": [{"text": "Shell", "label": "ORG"}],
            },
        )
        assert run_eval.asserted_ops(case) == {"TOKENIZE", "LEMMATIZE", "POS_TAG", "NER"}

    def test_a_case_without_entities_asserts_no_ner(self):
        case = a_case("cs-q-002", "cs", {"tokens": [{"text": "Kdo", "lemma": "kdo"}]})
        assert "NER" not in run_eval.asserted_ops(case)

    def test_the_lemmas_shortcut_counts_as_a_lemma_assertion(self):
        """Half the seed corpus carries `lemmas: [...]` beside the tokens."""
        case = a_case("cs-q-003", "cs", {"tokens": [{"text": "Kdo"}], "lemmas": ["kdo"]})
        assert "LEMMATIZE" in run_eval.asserted_ops(case)


class TestEmulatedPairs:
    def test_it_reads_the_engine_column(self):
        assert run_eval.emulated_pairs(MATRIX_WITH_EMULATION) == {("cs", "NER")}

    def test_no_emulated_row_means_no_pairs(self):
        assert run_eval.emulated_pairs(MATRIX_WITHOUT) == set()

    def test_an_empty_matrix_means_no_pairs_and_the_caller_must_not_treat_that_as_fine(self):
        """`fetch_capabilities` raises on an unreachable front rather than
        returning `[]`, precisely so this can never be reached by accident: a
        silent empty matrix switches every exclusion off at the moment the run is
        least trustworthy."""
        assert run_eval.emulated_pairs([]) == set()


class TestTheReport:
    def test_emulation_off_prints_no_exclusion_section(self):
        summary = {"corpus_size": 50, "excluded": [], "scored": 50, "engines": {}}
        report = run_eval.generate_markdown_report(summary)
        assert "Excluded cases" not in report
        assert "**Corpus size:** 50 questions" in report

    def test_emulation_on_names_every_excluded_case_above_the_metrics(self):
        summary = {
            "corpus_size": 50,
            "scored": 48,
            "excluded": [
                {"id": "cs-q-001", "reason": "emulated: NER/cs"},
                {"id": "cs-q-007", "reason": "emulated: NER/cs"},
            ],
            "engines": {},
        }
        report = run_eval.generate_markdown_report(summary)

        assert "cs-q-001" in report and "cs-q-007" in report
        assert "emulated: NER/cs" in report
        assert "2 excluded" in report and "48 scored" in report
        # Above the metrics, not after them: a reduced run's numbers mean
        # something different, and the reader has to know before reading them.
        assert report.index("Excluded cases") < report.index("Per-Engine Metrics")


class TestTheQualityReport:
    def test_it_separates_agreement_from_correctness(self):
        """The distinction the snapshot exists to hold: two engines wrong in the
        same way agree perfectly, so the report must never show agreement alone."""
        summary = {
            "corpus_size": 50,
            "cases_without_emulated_output": 0,
            "engines": {
                "llm_emulated": {
                    "token_f1": 0.9, "lemma_accuracy": 0.8, "pos_f1": 0.7,
                    "ner_f1": 0.6, "errors": 0, "total": 50,
                },
            },
            "agreement_with_incumbent": {"LEMMATIZE": 0.82, "POS_TAG": None, "NER": 0.61},
            "latency_ms": {"n": 50, "mean": 812.4, "max": 2100},
        }
        report = run_eval.generate_quality_report(summary)

        assert "Against the golden" in report
        assert "Agreement with the incumbent" in report
        assert "agreement is not" in report  # the caution is in the report itself
        assert "0.8200" in report and "—" in report  # a missing op is a dash, not a zero
        assert "mean=812.4 ms" in report
        # Cost is the gateway's to report; a second price table is a second drift.
        assert "does not price calls" in report
