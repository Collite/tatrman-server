# SPDX-License-Identifier: Apache-2.0
"""The TEST side has exactly one reader, and that is a test (T2).

UD_Czech-CAC is the sole eval oracle and its test side is shared with the Wave C
training task (LM-16/S-6, contracts §11). If seeding, or a frequency table, or a
QA comparison ever reads it, every number produced afterwards is a measurement
of memorization — and the failure is silent, because the numbers look *better*.

`importers.cac.sentences` therefore refuses `side="test"` unless the caller
passes `allow_test=True` in its own source. That flag cannot arrive from an
argument parser; it is written in a module, so adding one is a line in a diff.
This file is what makes the line visible: the flag may appear in the eval
harness, and nowhere else.

The same guard shape as `test_qa_guard.py`, for the same reason — the rules that
matter here are about what a *human* may write next, and a test is the only
place to say so that anyone will read.
"""

from __future__ import annotations

from pathlib import Path

import pytest

PACKAGE = Path(__file__).resolve().parents[1]

MARKER = "allow_test=True"

#: Three files: the harness, this guard, and the reader — which names the flag
#: in the error message it raises when the flag is missing. ⚑ That last one is
#: not a loophole, it is the same PROSE trap the simplemma guard fell into: a
#: grep for a marker matches the sentence that explains the marker. The check
#: below is deliberately a plain substring anyway, because anything cleverer
#: (an AST walk, a call-site parser) is a thing that can be worked around by
#: accident, and this rule is about what a human writes next.
ALLOWED = (
    PACKAGE / "src" / "ttrmorph" / "eval" / "harness.py",
    PACKAGE / "src" / "ttrmorph" / "importers" / "cac.py",
    PACKAGE / "tests" / "test_test_side_guard.py",
)

SEARCHED = ("src", "tests", "eval", "spike")


def sources() -> list[Path]:
    found: list[Path] = []
    for directory in SEARCHED:
        found.extend(sorted((PACKAGE / directory).rglob("*.py")))
    return [path for path in found if path not in ALLOWED]


@pytest.mark.parametrize("path", sources(), ids=lambda p: str(p.relative_to(PACKAGE)))
def test_only_the_harness_reads_the_test_side(path: Path):
    text = path.read_text(encoding="utf-8")
    assert MARKER not in text, (
        f"{path.relative_to(PACKAGE)} passes {MARKER!r}. The TEST side of the "
        "frozen split is the eval oracle (LM-16/S-6, contracts §11) and reading "
        "it anywhere but `ttrmorph.eval.harness` makes every number the harness "
        "produces a measurement of memorization. Seeding wants side='train'"
    )


def test_the_harness_still_reads_it_at_all():
    """The other half: a guard whose subject quietly stopped existing passes
    forever, and this one would look identical to a harness that had lost its
    corpus reader."""
    text = (PACKAGE / "src" / "ttrmorph" / "eval" / "harness.py").read_text(
        encoding="utf-8"
    )
    assert MARKER in text


def test_the_reader_refuses_the_test_side_without_the_flag():
    """The guard that runs, as opposed to the guard that greps."""
    from ttrmorph.eval.split import SplitError, SplitManifest
    from ttrmorph.importers.cac import sentences

    manifest = SplitManifest(
        corpus="UD_Czech-CAC",
        release="r2.18",
        sha256="x",
        seed=20260811,
        train=("a",),
        dev=(),
        test=("b",),
    )
    with pytest.raises(SplitError, match="memorization"):
        list(sentences([], side="test", manifest=manifest))
