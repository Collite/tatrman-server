# SPDX-License-Identifier: Apache-2.0
"""The `ttr-morph` command line.

The exit-code split is the thing worth testing: "no pattern fits" is an
*answer*, not a failure, and an importer shelling out to this command has to be
able to tell it apart from a command it got wrong.
"""

from __future__ import annotations

import json

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
