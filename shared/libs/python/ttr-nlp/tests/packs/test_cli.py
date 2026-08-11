# SPDX-License-Identifier: Apache-2.0
"""NLS-P2.2.T4 — ``ttr-nlp validate``: three exit codes and two output shapes.

Driven through ``main([...])`` rather than a subprocess. The exit code and the
stdout *are* the contract — the DFP model-validator wraps this pre-push and CI
branches on the code — so they are asserted directly, and a subprocess would add
a process boundary without adding coverage.

The 1-versus-2 split is the interesting part. Both mean "this did not pass", but
they answer different questions: 1 says the packs are wrong, 2 says the command
was. A wrapper that conflates them retries a typo'd path forever.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ttrnlp.cli import EXIT_OK, EXIT_USAGE, EXIT_VALIDATION_ERRORS, main

from .test_loader import good_pack, three_good, tree

FIXTURES = Path(__file__).parent.parent / "fixtures"
VALID_PACKS = FIXTURES / "packs" / "valid"
INVALID_PACKS = FIXTURES / "packs" / "invalid"
VALID_LISTS = FIXTURES / "lists" / "valid"
INVALID_LISTS = FIXTURES / "lists" / "invalid"
MODEL = FIXTURES / "model"


def run(capsys, *argv: str) -> tuple[int, str, str]:
    code = main(["validate", *argv])
    captured = capsys.readouterr()
    return code, captured.out, captured.err


# ── exit 0 ───────────────────────────────────────────────────────────────────


def test_valid_sources_exit_zero(capsys):
    code, out, _ = run(capsys, str(VALID_PACKS), str(VALID_LISTS))
    assert code == EXIT_OK
    assert out.startswith("OK — ")
    # The paths are echoed: with several sources, "OK" alone leaves the reader
    # unsure which of them was actually looked at.
    assert str(VALID_PACKS) in out


def test_a_single_pack_file_is_a_valid_argument(capsys):
    code, _, _ = run(capsys, str(VALID_PACKS / "hero-cs-role.pack.yaml"))
    assert code == EXIT_OK


def test_an_empty_directory_exits_zero(capsys, tmp_path):
    code, out, _ = run(capsys, str(tmp_path))
    assert code == EXIT_OK
    assert "would load" in out


# ── exit 1 ───────────────────────────────────────────────────────────────────


def test_invalid_packs_exit_one_and_print_every_diagnostic(capsys):
    code, out, _ = run(capsys, str(INVALID_PACKS))

    assert code == EXIT_VALIDATION_ERRORS
    lines = [line for line in out.splitlines() if line.startswith("ERROR")]
    assert len(lines) >= len(list(INVALID_PACKS.glob("*.pack.yaml")))


def test_the_human_line_carries_severity_code_where_and_message(capsys):
    """contracts §9: `SEVERITY code source:pack — message`."""
    _, out, _ = run(capsys, str(INVALID_LISTS / "bad-matching.list.yaml"))

    (line,) = [ln for ln in out.splitlines() if ln.startswith("ERROR")]
    severity, code, rest = line.split(" ", 2)
    where, message = rest.split(" — ", 1)

    assert (severity, code) == ("ERROR", "NLS-PACK-003")
    assert where.endswith("bad-matching.list.yaml:bad-mode")
    assert "$.matching" in message


def test_the_summary_says_nothing_would_load(capsys):
    """A pack author reading a per-file error list has to be told the verdict is
    all-or-nothing — otherwise "8 of 9 are fine" is the natural reading."""
    _, out, _ = run(capsys, str(INVALID_PACKS))
    assert "nothing would load (fail-all)" in out


def test_a_broken_pack_beside_good_ones_still_exits_one(capsys, tmp_path):
    three_good(tmp_path)
    (tmp_path / "bad.pack.yaml").write_text(
        good_pack("bad").replace("appelt", "appelts"), encoding="utf-8"
    )
    code, _, _ = run(capsys, str(tmp_path))
    assert code == EXIT_VALIDATION_ERRORS


def test_model_cross_check_errors_exit_one(capsys, tmp_path):
    tree(
        tmp_path,
        {
            "q.pack.yaml": (
                "pack: qp\nversion: 1\nphases:\n"
                "  - phase: p\n    input: [Token]\n    control: appelt\n"
                "    rules:\n      - rule: R\n"
                "        lhs: [ { ann: Token } ]\n"
                "        rhs: [ { add: { type: QueryPattern, features: "
                "{ query: not_in_the_model } } } ]\n"
            )
        },
    )
    code, out, _ = run(capsys, str(tmp_path), "--model", str(MODEL))
    assert code == EXIT_VALIDATION_ERRORS
    assert "NLS-PACK-005" in out


# ── exit 2 ───────────────────────────────────────────────────────────────────


def test_a_path_that_does_not_exist_exits_two(capsys):
    """The command's argument is wrong, not the packs. Exit 1 here would have a
    wrapper retrying a typo forever."""
    code, out, err = run(capsys, "/definitely/not/here")
    assert code == EXIT_USAGE
    assert "no such file or directory" in err
    assert out == ""


def test_every_missing_path_is_named(capsys):
    code, _, err = run(capsys, "/nope/one", "/nope/two")
    assert code == EXIT_USAGE
    assert "/nope/one" in err
    assert "/nope/two" in err


def test_a_missing_model_directory_exits_two(capsys):
    code, _, err = run(capsys, str(VALID_PACKS), "--model", "/nope/model")
    assert code == EXIT_USAGE
    assert "--model" in err


def test_no_subcommand_is_a_usage_error():
    with pytest.raises(SystemExit) as raised:
        main([])
    assert raised.value.code == EXIT_USAGE


def test_an_unknown_subcommand_is_a_usage_error():
    with pytest.raises(SystemExit) as raised:
        main(["lint", "somewhere"])
    assert raised.value.code == EXIT_USAGE


# ── --json ───────────────────────────────────────────────────────────────────


def test_json_output_is_an_array_of_pack_diagnostics(capsys):
    """Field for field the proto's `PackDiagnostic` (contracts §2.3), so a
    wrapper reading CLI output and one reading a `ReloadPacks` response need one
    parser between them."""
    code, out, _ = run(capsys, str(INVALID_LISTS / "bad-matching.list.yaml"), "--json")

    assert code == EXIT_VALIDATION_ERRORS
    (entry,) = json.loads(out)
    assert set(entry) == {"source", "pack", "severity", "code", "message"}
    assert entry["severity"] == "ERROR"
    assert entry["code"] == "NLS-PACK-003"
    assert entry["pack"] == "bad-mode"


def test_json_output_on_success_is_an_empty_array(capsys):
    code, out, _ = run(capsys, str(VALID_PACKS), "--json")
    assert code == EXIT_OK
    assert json.loads(out) == []


def test_json_output_keeps_czech_readable(capsys, tmp_path):
    """`ensure_ascii=False`: a diagnostic about `zákazníka` rendered as
    `z\\u00e1kazn\\u00edka` is unreadable in exactly the place a Czech pack author
    needs to read it."""
    (tmp_path / "bad.list.yaml").write_text(
        "list: zákazníci\nversion: 1\nmatching: ci\n"
        "source: {world: hand, origin: test}\nentries: [{term: x}]\n",
        encoding="utf-8",
    )
    _, out, _ = run(capsys, str(tmp_path), "--json")

    (entry,) = json.loads(out)
    assert "zákazníci" in entry["message"]  # the id it complained about
    assert "zákazníci" in out  # and it is legible in the raw output
    assert "\\u00e1" not in out


def test_json_and_human_output_agree_on_the_verdict(capsys):
    human_code, human_out, _ = run(capsys, str(INVALID_PACKS))
    json_code, json_out, _ = run(capsys, str(INVALID_PACKS), "--json")

    assert human_code == json_code
    human_lines = [ln for ln in human_out.splitlines() if ln.startswith("ERROR")]
    assert len(json.loads(json_out)) == len(human_lines)


# ── the parity promise, from the CLI's side ───────────────────────────────────


def test_the_cli_runs_the_shared_path_and_nothing_of_its_own(monkeypatch, capsys):
    """The one thing that must not drift. If the CLI ever grows its own reading or
    its own checks, this fails — and the promise that "validates here, validates
    in the cluster" quietly stops being true otherwise."""
    calls = []

    def spy(sources, *, model=None, pipelines=None):
        calls.append((list(sources), model, pipelines))
        return []

    monkeypatch.setattr("ttrnlp.cli.validate_sources", spy)
    code, _, _ = run(capsys, str(VALID_PACKS), "--model", str(MODEL))

    assert code == EXIT_OK
    assert calls == [([str(VALID_PACKS)], str(MODEL), None)]
