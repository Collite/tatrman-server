# SPDX-License-Identifier: Apache-2.0
"""NLS-P4.T3 — the rule-pack scorer, without a service.

The `--rules` lane needs a running front, so the harness itself is exercised at
NLS-P4.T4's compose run. What is tested here is the part that decides pass or
fail, because that is where a scorer is wrong in the way that matters: too
lenient, and the eval reports green on a pack that answers the wrong question.

The decoy half carries most of the weight. A rules corpus of positive cases only
is passed by a pack that fires on *everything* — which is the most likely way for
a pack to be wrong, since an over-broad LHS matches the hero and every sentence
near it, and a hero-only suite stays green throughout.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

EVAL = Path(__file__).resolve().parent.parent / "eval"
sys.path.insert(0, str(EVAL))

from run_eval import (  # noqa: E402 — the harness is a script, not a package
    RuleCase,
    generate_rules_report,
    load_rule_corpus,
    main,
    score_rule_case,
    summarize_rules,
)

CORPUS = EVAL / "corpus" / "rules.jsonl"


def hero(**overrides) -> RuleCase:
    base = dict(
        id="h",
        kind="hero",
        text="Zobraz všechny faktury od zákazníka Microsoft",
        lang="cs",
        pipeline="query-patterns",
        expected_query="faktury_zakaznika",
        expected_params={"nazev_zakaznika": "Microsoft"},
    )
    return RuleCase(**{**base, **overrides})


def decoy(**overrides) -> RuleCase:
    base = dict(
        id="d",
        kind="decoy",
        text="Jaké je počasí v Praze?",
        lang="cs",
        pipeline="query-patterns",
        expected_query=None,
        expected_params={},
    )
    return RuleCase(**{**base, **overrides})


MATCH = {"query": "faktury_zakaznika", "nazev_zakaznika": "Microsoft"}


# ── the corpus itself ────────────────────────────────────────────────────────


def test_the_corpus_loads_and_holds_both_heroes():
    cases = load_rule_corpus(CORPUS)
    ids = {c.id for c in cases}
    assert {"hero-cs-invoices", "hero-cs-role"} <= ids


def test_the_corpus_has_at_least_eight_paraphrases_and_decoys():
    """T3's floor. Fewer than that and the corpus is a restatement of the hero
    test rather than an independent check on it."""
    cases = load_rule_corpus(CORPUS)
    extra = [c for c in cases if c.kind in ("paraphrase", "decoy")]
    assert len(extra) >= 8
    # And both kinds are actually represented — eight paraphrases and no decoys
    # would satisfy the count while testing only one direction.
    assert {c.kind for c in extra} == {"paraphrase", "decoy"}


def test_every_decoy_expects_no_query():
    for case in load_rule_corpus(CORPUS):
        if case.kind == "decoy":
            assert case.is_decoy, case.id
            assert case.expected_params == {}


def test_every_non_decoy_names_a_query_and_its_params():
    for case in load_rule_corpus(CORPUS):
        if case.kind != "decoy":
            assert case.expected_query, case.id
            assert case.expected_params, case.id


def test_every_case_carries_a_note():
    """A corpus line is a claim about what the pack should do. Six months on, the
    note is the only thing that says why the claim was made."""
    for case in load_rule_corpus(CORPUS):
        assert case.note, case.id


# ── scoring: the positive direction ──────────────────────────────────────────


def test_an_exact_match_passes():
    assert score_rule_case(hero(), [MATCH], []).passed


def test_a_wrong_query_id_fails():
    outcome = score_rule_case(hero(), [{**MATCH, "query": "faktury"}], [])
    assert not outcome.passed
    assert "query" in outcome.reason


def test_a_wrong_parameter_value_fails():
    """No partial credit, and none is available downstream either: a span that is
    almost right queries for the wrong customer."""
    outcome = score_rule_case(hero(), [{**MATCH, "nazev_zakaznika": "Microsof"}], [])
    assert not outcome.passed
    assert "nazev_zakaznika" in outcome.reason


def test_a_missing_parameter_fails():
    outcome = score_rule_case(hero(), [{"query": "faktury_zakaznika"}], [])
    assert not outcome.passed
    assert "None" in outcome.reason


def test_producing_nothing_fails():
    outcome = score_rule_case(hero(), [], [])
    assert not outcome.passed
    assert "no QueryPattern" in outcome.reason


def test_producing_two_patterns_fails_even_if_one_is_right():
    """Two answers to one question means the consumer has to choose, and nothing
    downstream is equipped to. Not a near-miss."""
    outcome = score_rule_case(hero(), [MATCH, {"query": "something_else"}], [])
    assert not outcome.passed
    assert "2 QueryPatterns" in outcome.reason


def test_an_extra_unexpected_feature_does_not_fail_a_case():
    """Only the DECLARED parameters are scored. A pack may stamp its own
    bookkeeping onto a QueryPattern, and the model cross-check (NLS-PACK-005) is
    where that gets an opinion — not here."""
    assert score_rule_case(hero(), [{**MATCH, "matched_by": "ner"}], []).passed


# ── scoring: decoys ──────────────────────────────────────────────────────────


def test_a_decoy_that_matches_nothing_passes():
    assert score_rule_case(decoy(), [], []).passed


def test_a_decoy_that_fires_fails_and_says_what_it_produced():
    outcome = score_rule_case(decoy(), [MATCH], [])
    assert not outcome.passed
    assert "decoy matched" in outcome.reason
    assert "faktury_zakaznika" in outcome.reason


# ── the summary and the report ───────────────────────────────────────────────


def test_the_summary_counts_by_kind():
    outcomes = [
        score_rule_case(hero(), [MATCH], []),
        score_rule_case(hero(id="h2"), [], []),
        score_rule_case(decoy(), [], []),
        score_rule_case(decoy(id="d2"), [MATCH], []),
    ]
    summary = summarize_rules(outcomes, lane="default")

    assert (summary["total"], summary["passed"], summary["failed"]) == (4, 2, 2)
    assert summary["by_kind"] == {
        "hero": {"passed": 1, "total": 2},
        "decoy": {"passed": 1, "total": 2},
    }
    assert summary["lane"] == "default"
    assert summary["mode"] == "rules"


def test_the_summary_is_json_serialisable():
    """It is written to `eval/reports/metrics.json` — a dataclass leaking into it
    would fail at the very end of a long compose run."""
    summary = summarize_rules([score_rule_case(hero(), [MATCH], [])])
    json.loads(json.dumps(summary))


def test_the_report_names_every_case_and_its_reason():
    summary = summarize_rules(
        [
            score_rule_case(hero(), [MATCH], []),
            score_rule_case(decoy(), [MATCH], []),
        ]
    )
    report = generate_rules_report(summary)

    assert "1/2 passed" in report
    assert "decoy matched" in report
    assert "✅" in report and "❌" in report


def test_the_report_lists_the_diagnostics_that_were_observed():
    """`NLS-NLP-011` on a default-lane run is the evidence for arc gate 2's
    second half — the degrade happened and the answer survived it."""
    outcome = score_rule_case(
        hero(), [MATCH], [{"code": "NLS-NLP-011", "severity": "WARNING", "message": "x"}]
    )
    report = generate_rules_report(summarize_rules([outcome]))
    assert "NLS-NLP-011" in report


def test_a_report_can_be_written_to_a_missing_directory(tmp_path):
    """`eval/reports/` is gitignored-empty, so the first run creates it."""
    target = tmp_path / "reports" / "report.md"
    generate_rules_report(summarize_rules([score_rule_case(hero(), [MATCH], [])]), target)
    assert target.exists()


@pytest.mark.parametrize("kind", ["hero", "paraphrase", "decoy"])
def test_all_three_kinds_are_scoreable(kind):
    case = decoy(kind=kind) if kind == "decoy" else hero(kind=kind)
    assert score_rule_case(case, [] if kind == "decoy" else [MATCH], []).passed


# ── the offline recipe's config (NLS-P4.T4's prerequisite) ───────────────────


def test_the_offline_config_pipelines_resolve_against_the_mounted_fixtures():
    """T4 is a compose run, and the way it fails is at boot, minutes in, with a
    pipeline referring to a pack that is not mounted. That is checkable here in
    milliseconds — the same `validate_sources` the boot load calls, over the same
    fixture directories the compose file mounts.

    It also keeps three files honest with each other: the compose mounts, the
    config's pipeline table, and the wheel's fixture pack ids.
    """
    import yaml
    from ttrnlp.packs.validate import validate_sources

    from nlp_service.config import AppConfig

    service_dir = Path(__file__).resolve().parent.parent
    config = AppConfig(
        **yaml.safe_load(
            (service_dir / "eval" / "offline-config.yaml").read_text(encoding="utf-8")
        )
    )
    fixtures = service_dir.parent.parent / "shared" / "libs" / "python" / "ttr-nlp" / "tests" / "fixtures"

    diagnostics = validate_sources(
        [str(fixtures / "packs" / "valid"), str(fixtures / "lists" / "valid")],
        pipelines={name: spec.model_dump() for name, spec in config.pipelines.items()},
    )
    assert diagnostics == [], [str(d) for d in diagnostics]


def test_the_offline_config_ships_the_default_lane():
    """The file is the DEFAULT lane and the compose file overlays `NLP_LANE=option`.
    That is what makes the degrade drill one env var rather than a second config —
    and T4's second run depends on it."""
    import yaml

    from nlp_service.config import AppConfig

    path = Path(__file__).resolve().parent.parent / "eval" / "offline-config.yaml"
    config = AppConfig(**yaml.safe_load(path.read_text(encoding="utf-8")))

    assert config.lane == "default"
    assert "NER.cs" not in config.op_routing
    assert config.lane_overrides["option"]["NER.cs"] == "nametag3"


def test_every_corpus_pipeline_exists_in_the_offline_config():
    """A corpus line naming a pipeline the recipe does not configure fails as
    INVALID_ARGUMENT halfway through the run, after the images are already up."""
    import yaml

    from nlp_service.config import AppConfig

    service_dir = Path(__file__).resolve().parent.parent
    config = AppConfig(
        **yaml.safe_load(
            (service_dir / "eval" / "offline-config.yaml").read_text(encoding="utf-8")
        )
    )
    named = {case.pipeline for case in load_rule_corpus(CORPUS)}
    assert named <= set(config.pipelines), named - set(config.pipelines)


# ── the two lanes address the front differently ──────────────────────────────


def test_the_rules_lane_gets_a_grpc_target_not_a_rest_url(monkeypatch):
    """`--rules` talks gRPC, and `grpc.aio.insecure_channel` takes `host:port`.

    Handing it `--url`'s default, an `http://…` base URL, made every case fail on
    DNS resolution of the literal string before the front was contacted at all —
    a run that looks like twelve scoring failures and is a wrong address. The
    compose recipe only worked because it spelled a bare `host:port` into `--url`.
    """
    seen = {}

    def fake_run(target, corpus_path, *, lane=""):
        seen["target"] = target
        seen["lane"] = lane
        return summarize_rules([], lane=lane)

    monkeypatch.setattr("run_eval.run_rules_evaluation", fake_run)
    monkeypatch.setattr(sys, "argv", ["run_eval.py", "--rules", "--corpus", str(CORPUS)])
    main()

    assert seen["target"] == "localhost:7271"
    assert "://" not in seen["target"]


def test_the_lane_label_reaches_the_report(monkeypatch):
    """`run_rules_evaluation` and `summarize_rules` both took `lane=` and nothing
    ever passed it, so the report's `· lane` line was unreachable — and that line
    is how a report says which of the two arc-gate-2 runs produced it."""
    seen = {}

    def fake_run(target, corpus_path, *, lane=""):
        seen["lane"] = lane
        return summarize_rules([score_rule_case(hero(), [MATCH], [])], lane=lane)

    monkeypatch.setattr("run_eval.run_rules_evaluation", fake_run)
    monkeypatch.setattr(
        sys,
        "argv",
        ["run_eval.py", "--rules", "--corpus", str(CORPUS), "--lane", "option"],
    )
    main()

    assert seen["lane"] == "option"
    assert "· lane `option`" in generate_rules_report(
        summarize_rules([], lane="option")
    )


def test_the_lane_label_defaults_to_the_env(monkeypatch):
    """The compose drill flips `NLP_LANE` for the second run; reading it here
    means the report cannot disagree with the front it ran against."""
    seen = {}
    monkeypatch.setenv("NLP_LANE", "default")

    def fake_run(target, corpus_path, *, lane=""):
        seen["lane"] = lane
        return summarize_rules([], lane=lane)

    monkeypatch.setattr("run_eval.run_rules_evaluation", fake_run)
    monkeypatch.setattr(sys, "argv", ["run_eval.py", "--rules", "--corpus", str(CORPUS)])
    main()

    assert seen["lane"] == "default"
