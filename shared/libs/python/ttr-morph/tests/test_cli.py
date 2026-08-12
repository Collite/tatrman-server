# SPDX-License-Identifier: Apache-2.0
"""The `ttr-morph` command line.

The exit-code split is the thing worth testing: "no pattern fits" is an
*answer*, not a failure, and an importer shelling out to this command has to be
able to tell it apart from a command it got wrong.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml

from ttrmorph.cli import EXIT_NO_ANSWER, EXIT_OK, EXIT_USAGE, main
from ttrmorph.engine import generate


def test_generate_prints_a_sorted_paradigm(capsys):
    assert main(["generate", "tržba", "žena"]) == EXIT_OK
    lines = capsys.readouterr().out.strip().splitlines()
    assert len(lines) == 14
    assert lines == sorted(lines)


def test_generate_json_carries_form_and_feats(capsys):
    assert main(["generate", "Kaufland", "hrad-proper", "--json"]) == EXIT_OK
    rows = json.loads(capsys.readouterr().out)
    assert {row["form"] for row in rows} >= {"Kaufland", "Kauflandu"}
    assert all(set(row) == {"form", "feats"} for row in rows)


def test_generate_takes_repeated_flags(capsys):
    args = ["generate", "matka", "žena", "--flag", "palatal", "--flag", "fleeting-e"]
    assert main(args) == EXIT_OK
    assert "matce" in capsys.readouterr().out


def test_an_unknown_vzor_is_a_usage_failure(capsys):
    assert main(["generate", "tržba", "nope"]) == EXIT_USAGE
    assert "error:" in capsys.readouterr().err


def test_classify_answers_with_the_pattern(tmp_path, capsys):
    table: dict[str, list[str]] = {}
    for form, feats in generate("tržba", "žena"):
        table.setdefault(feats, []).append(form)
    path = tmp_path / "table.yaml"
    path.write_text(yaml.safe_dump(table, allow_unicode=True), encoding="utf-8")

    assert main(["classify", str(path)]) == EXIT_OK
    assert json.loads(capsys.readouterr().out) == {"vzor": "žena", "flags": []}


def test_no_pattern_fitting_is_an_answer_not_a_failure(tmp_path, capsys):
    """Exit 1, not 2 — the question was fine, the answer is no.

    This is the code the importer branches on to write a full-form entry.
    """
    path = tmp_path / "table.yaml"
    path.write_text(
        yaml.safe_dump({"Case=Nom|Number=Sing": "qqq"}, allow_unicode=True),
        encoding="utf-8",
    )
    assert main(["classify", str(path)]) == EXIT_NO_ANSWER


def test_a_missing_table_file_is_a_usage_failure(tmp_path):
    assert main(["classify", str(tmp_path / "gone.yaml")]) == EXIT_USAGE


def test_a_table_that_is_not_a_mapping_is_a_usage_failure(tmp_path):
    path = tmp_path / "table.yaml"
    path.write_text("- just\n- a list\n", encoding="utf-8")
    assert main(["classify", str(path)]) == EXIT_USAGE


def test_vzory_lists_every_pattern(capsys):
    assert main(["vzory", "--json"]) == EXIT_OK
    rows = json.loads(capsys.readouterr().out)
    names = {row["vzor"] for row in rows}
    assert {"žena", "hrad", "dělat", "hrad-proper"} <= names
    assert all(row["slots"] > 0 for row in rows)


def test_a_command_is_required():
    with pytest.raises(SystemExit):
        main([])


# ── the layer-file lane (P8.2) ───────────────────────────────────────────────


FIXTURES = Path(__file__).resolve().parent / "fixtures" / "layers"
HAND = str(FIXTURES / "core-hand.morph.yaml")
KAIKKI = str(FIXTURES / "core-kaikki.morph.yaml")
WORLD = str(FIXTURES / "world" / "world-dfp.morph.yaml")


def test_validate_accepts_the_fixture_layers(capsys):
    assert main(["validate", HAND, KAIKKI]) == EXIT_OK
    assert capsys.readouterr().out.startswith("OK —")


def test_validate_reports_errors_and_exits_one(tmp_path, capsys):
    bad = tmp_path / "bad.morph.yaml"
    bad.write_text(
        "layer: core-hand\nversion: 1\nlanguage: cs\nlicense: MIT\nentries: []\n",
        encoding="utf-8",
    )
    assert main(["validate", str(bad)]) == EXIT_NO_ANSWER
    out = capsys.readouterr().out
    assert "ERROR LM-MORPH-001" in out
    assert "nothing was written" in out


def test_validate_json_has_the_ttr_nlp_field_shape(tmp_path, capsys):
    bad = tmp_path / "bad.morph.yaml"
    bad.write_text("layer: BAD\nversion: 1\nlanguage: cs\nentries: []\n", "utf-8")
    assert main(["validate", str(bad), "--json"]) == EXIT_NO_ANSWER
    rows = json.loads(capsys.readouterr().out)
    assert all(
        set(row) == {"source", "pack", "severity", "code", "message"} for row in rows
    )


def test_validate_of_a_missing_file_is_a_usage_failure(tmp_path, capsys):
    assert main(["validate", str(tmp_path / "nope.yaml")]) == EXIT_USAGE
    assert "no such file" in capsys.readouterr().err


def test_compile_writes_the_body_the_part_and_the_notice(tmp_path, capsys):
    out = tmp_path / "dist" / "cs.morph.snap"
    code = main(
        ["compile", HAND, KAIKKI, "-o", str(out), "--snapshot-version", "0.1.0"]
    )
    assert code == EXIT_OK
    written = {path.name for path in out.parent.iterdir()}
    assert written == {"cs.morph.snap", "core-kaikki.morph.part", "NOTICE-morph.md"}
    assert out.read_text(encoding="utf-8").startswith("#morph-snapshot v1\n")
    assert "#version: 0.1.0" in out.read_text(encoding="utf-8")


def test_compile_writes_nothing_when_it_fails(tmp_path, capsys):
    """A half-written snapshot is the one artifact a later job picks up and
    hashes without asking.

    The failure used here is a world layer handed to a core compile — one
    world's vocabulary in the published core, which would ship to every
    deployment with no way for that world to retract it."""
    out = tmp_path / "dist" / "cs.morph.snap"
    assert main(["compile", WORLD, "-o", str(out)]) == EXIT_NO_ANSWER
    assert not out.parent.exists()
    assert "LM-MORPH-004" in capsys.readouterr().out


def test_compile_overlay_needs_a_world(tmp_path, capsys):
    out = tmp_path / "dfp.morph.overlay"
    assert main(["compile", WORLD, "-o", str(out), "--overlay"]) == EXIT_USAGE
    assert "--overlay needs --world" in capsys.readouterr().err


def test_compile_overlay_writes_one_file_plus_the_notice(tmp_path):
    out = tmp_path / "dfp.morph.overlay"
    code = main(
        ["compile", WORLD, "-o", str(out), "--overlay", "--world", "dfp"]
    )
    assert code == EXIT_OK
    assert out.read_text(encoding="utf-8").startswith("#morph-overlay v1\n")
    assert {path.name for path in tmp_path.iterdir()} == {
        "dfp.morph.overlay",
        "NOTICE-morph.md",
    }


def test_compile_takes_a_frequency_table(tmp_path):
    freq = tmp_path / "freq.tsv"
    freq.write_text("rok\t900\n", encoding="utf-8")
    out = tmp_path / "cs.morph.snap"
    assert main(["compile", HAND, "-o", str(out), "--freq", str(freq)]) == EXIT_OK
    ranks = {
        line.split("\t")[6]
        for line in out.read_text(encoding="utf-8").splitlines()
        if line.count("\t") == 8 and line.split("\t")[1] == "rok"
    }
    assert ranks == {"1"}
