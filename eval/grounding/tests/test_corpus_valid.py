"""A14.1 — corpus is well-formed, ref-clock-pinned, cs+en, ≥100 bulk / ~20 e2e.

Runs with no deployed stack: it validates the committed corpus shape and that the
consolidated file is not stale relative to the service corpora + supplemental.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import build_corpus

_CORPUS = Path(__file__).resolve().parent.parent / "corpus"
_ISO_OFFSET = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$")

_TOOL_KIND = {"ground_time": "DATE", "ground_geo": "LOCATION", "ground_money": "MONEY"}
_STATUSES = {"OK", "AWAITING_CLARIFICATION", "UNGROUNDABLE"}
_APPLICATIONS = {"FILTER", "JOIN", "VALUES"}


def _bulk() -> list[dict]:
    return json.loads((_CORPUS / "grounding-cases.json").read_text(encoding="utf-8"))


def _e2e() -> list[dict]:
    return json.loads((_CORPUS / "e2e-cases.json").read_text(encoding="utf-8"))


def test_corpus_not_stale() -> None:
    assert build_corpus.main(["--check"]) == 0, "run `just eval-grounding-build`"


def test_bulk_size_and_language_balance() -> None:
    cases = _bulk()
    assert len(cases) >= 100, f"need ≥100 bulk cases, have {len(cases)}"
    langs = {("cs" if c["locale"].lower().startswith("cs") else "en") for c in cases}
    assert langs == {"cs", "en"}, f"corpus must cover cs AND en, got {langs}"
    # neither language a token minority
    cs = sum(1 for c in cases if c["locale"].lower().startswith("cs"))
    assert 0.25 <= cs / len(cases) <= 0.75


def test_bulk_ids_unique() -> None:
    ids = [c["id"] for c in _bulk()]
    dupes = {i for i in ids if ids.count(i) > 1}
    assert not dupes, f"duplicate case ids: {dupes}"


def test_bulk_cases_well_formed() -> None:
    for c in _bulk():
        assert _TOOL_KIND[c["tool"]] == c["kind"], f"{c['id']}: tool/kind mismatch"
        assert _ISO_OFFSET.match(c["reference_datetime"]), f"{c['id']}: reference_datetime not pinned ISO+offset"
        assert c["model"], f"{c['id']}: missing model fixture"
        exp = c["expect"]
        assert exp["status"] in _STATUSES, f"{c['id']}: bad status {exp['status']}"
        if exp["status"] == "OK":
            if "application" in exp:
                assert exp["application"] in _APPLICATIONS
        else:
            # a non-OK case must not assert recipe facets
            assert not any(k in exp for k in ("application", "source", "period_code", "sql_contains")), c["id"]


def test_status_coverage() -> None:
    statuses = {c["expect"]["status"] for c in _bulk()}
    assert statuses == _STATUSES, f"corpus should exercise every outcome, got {statuses}"


def test_e2e_size_and_shape() -> None:
    cases = _e2e()
    assert 18 <= len(cases) <= 30, f"~20 e2e cases expected, have {len(cases)}"
    ids = [c["id"] for c in cases]
    assert len(set(ids)) == len(ids)
    for c in cases:
        assert c["tier"] == "e2e"
        assert _ISO_OFFSET.match(c["reference_datetime"]), c["id"]
        assert c["turns"], f"{c['id']}: no turns"
        for t in c["turns"]:
            assert "user_text" in t or "resume_selects" in t, c["id"]
