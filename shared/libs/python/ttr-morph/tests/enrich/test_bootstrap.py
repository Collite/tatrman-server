# SPDX-License-Identifier: Apache-2.0
"""The bulk batch (NLS-P9.3 T6)."""

from __future__ import annotations

import json

import pytest

from ttrmorph.enrich import bootstrap as bs
from ttrmorph.enrich.cascade import STATUS_AUTO_VALIDATED, STATUS_PROPOSED

# ── reading the lists ────────────────────────────────────────────────────────


def test_reads_a_plain_word_list(tmp_path):
    path = tmp_path / "words.txt"
    path.write_text("kvartál\n# a comment\n\nfaktura  # trailing\n", encoding="utf-8")
    assert bs.read_targets(path) == ["kvartál", "faktura"]


def test_reads_the_grouped_target_list(tmp_path):
    path = tmp_path / "targets.yaml"
    path.write_text(
        "language: cs\ngroups:\n  hero:\n    - faktura\n    - zákazník\n"
        "  money:\n    - tržba\n    - faktura\n",
        encoding="utf-8",
    )
    # Deduplicated, first occurrence wins the order.
    assert bs.read_targets(path) == ["faktura", "zákazník", "tržba"]


def test_reads_a_world_glossary_and_keeps_only_its_own_language(tmp_path):
    """A `ttr-lexicon` export. A cs glossary routinely carries en terms too."""
    path = tmp_path / "world.lex.yaml"
    path.write_text(
        "schema: ttr-lexicon/v1\n"
        "defaults: { lang: cs }\n"
        "entries:\n"
        "  - terms:\n"
        '      - { text: "pololetí" }\n'
        '      - { text: "half", lang: en }\n'
        '      - { text: "H2", lang: "cs|en", method: EXACT }\n',
        encoding="utf-8",
    )
    assert bs.read_targets(path, lang="cs") == ["pololetí", "H2"]
    assert bs.read_targets(path, lang="en") == ["half", "H2"]


def test_multi_word_terms_are_dropped_not_split(tmp_path):
    """⚑ The cascade takes one lemma at a time.

    Splitting *"Farrow Běžecká bota MX-MRS"* would ask the guesser for a
    paradigm for `MX-MRS`. A phrase belongs to the gazetteer lane.
    """
    path = tmp_path / "glossary.yaml"
    path.write_text(
        "entries:\n  - terms:\n"
        '      - { text: "druhé pololetí" }\n'
        '      - { text: "pololetí" }\n',
        encoding="utf-8",
    )
    assert bs.read_targets(path) == ["pololetí"]


def test_an_unreadable_shape_says_which_shapes_it_knows(tmp_path):
    path = tmp_path / "wrong.yaml"
    path.write_text("language: cs\nwords: {}\n", encoding="utf-8")
    with pytest.raises(ValueError, match="ttr-lexicon glossary"):
        bs.read_targets(path)


# ── the run ──────────────────────────────────────────────────────────────────


def test_runs_the_cascade_and_buckets_the_outcomes():
    batch = bs.run([("Kaufland", "list"), ("pololetích", "list")], world="dfp")

    assert len(batch.rows) == 2
    assert batch.sources == {"list": 2}
    assert {row.token for row in batch.auto_validated} == {"Kaufland"}
    assert {row.token for row in batch.proposed} == {"pololetích"}


def test_a_row_carries_its_cascade_into_the_ingest_shape():
    """⚑ The studio stores this; it does not run the cascade a second time.

    Otherwise the report a person reviews and the queue they then act on come
    from two different runs — and the LLM leg is not a pure function.
    """
    batch = bs.run([("Kaufland", "core")], world="dfp")
    row = batch.rows[0].as_report("dfp")

    assert row["world"] == "dfp"
    assert row["token"] == "Kaufland"
    assert row["cascade"]["status"] == STATUS_AUTO_VALIDATED
    assert row["cascade"]["layer"] in ("core", "world")
    assert row["cascade"]["proposals"][0]["vzor"] == "hrad-proper"


def test_a_bootstrap_row_is_not_a_miss():
    """Nobody's query missed on these — the queue must be able to tell."""
    batch = bs.run([("Kaufland", "core")])
    row = batch.rows[0].as_report("core")
    assert row["verdict"] == "bootstrap"
    assert row["count"] == 0


def test_an_llm_failure_is_recorded_rather_than_ending_the_batch():
    """⚑ The cascade never raises for a leg — it writes a NOTE.

    So the batch reads the notes. A `try/except LlmError` around `run_cascade`
    would be dead code, and a run against a gateway that was down for an hour
    would report a clean sheet.
    """
    from ttrmorph.enrich.llm import LlmLeg, LlmSpec, LlmUnavailable

    def transport(system: str, user: str) -> str:
        raise LlmUnavailable("gateway is down")

    leg = LlmLeg(LlmSpec(url="http://gateway", model="m"), transport=transport)
    batch = bs.run([("pololetích", "list")], llm=leg)

    assert len(batch.rows) == 1
    assert batch.failed and batch.failed[0][0] == "pololetích"
    assert "gateway is down" in batch.failed[0][1]
    assert batch.rows[0].result.status == STATUS_PROPOSED


def test_rows_round_trip_through_the_jsonl(tmp_path):
    batch = bs.run([("Kaufland", "core"), ("pololetích", "core")], world="dfp")
    path = tmp_path / "bootstrap.jsonl"
    assert bs.write_rows(batch, path) == 2

    rows = list(bs.read_rows(path))
    assert [row["token"] for row in rows] == ["Kaufland", "pololetích"]
    first = json.loads(path.read_text(encoding="utf-8").splitlines()[0])
    assert first["world"] == "dfp"


# ── skipping what the artifact already has ───────────────────────────────────


def test_without_a_snapshot_everything_is_uncovered():
    wanted, covered = bs.uncovered(["faktura", "tržba"], None)
    assert wanted == ["faktura", "tržba"]
    assert covered == []


def test_thin_counts_as_uncovered(monkeypatch):
    """⚑ P8.4's lesson, applied to the batch.

    *zobrazit* held as a bare infinitive counted as covered and left the hero
    sentence with no analysis. A batch that trusted plain coverage would skip
    exactly the words most worth its attention.
    """
    from ttrmorph.eval import harness

    monkeypatch.setattr(
        harness, "target_coverage", lambda state, lemmas: (1, ["chybí"], ["zobrazit"])
    )
    wanted, covered = bs.uncovered(["chybí", "zobrazit", "faktura"], object())
    assert wanted == ["chybí", "zobrazit"]
    assert covered == ["faktura"]


# ── the report ───────────────────────────────────────────────────────────────


def test_the_report_leads_with_what_a_reviewer_has_to_decide():
    batch = bs.run([("Kaufland", "core"), ("pololetích", "core")], world="dfp")
    batch.covered = ["faktura"]
    text = bs.render(batch)

    assert "# Bootstrap batch — world `dfp`" in text
    assert "auto-validated" in text
    assert "| targets worked | 2 |" in text
    assert "| already covered, skipped | 1 |" in text
    # The tier breakdown, the routing breakdown and a pattern census.
    assert "### By tier" in text
    assert "### By layer (LM-10 routing)" in text
    assert "hrad-proper" in text


def test_the_report_says_nothing_is_verified():
    """The one sentence that must survive any edit to this document."""
    text = bs.render(bs.run([("Kaufland", "core")]))
    assert "Nothing here is verified" in text
    assert "`verified` is the human act" in text


def test_the_report_names_llm_failures_rather_than_hiding_them():
    batch = bs.Batch(world="core")
    batch.failed.append(("pololetích", "gateway is down"))
    text = bs.render(batch)
    assert "## LLM failures" in text
    assert "gateway is down" in text
