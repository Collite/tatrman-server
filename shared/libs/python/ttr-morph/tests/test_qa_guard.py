# SPDX-License-Identifier: Apache-2.0
"""simplemma is read-only, and that is a test rather than a promise (T7).

simplemma is MIT-licensed, so nothing legal stops us from taking its lemma list
and shipping it. What stops us is that the lexicon would then be somebody
else's answer wearing our name — and the moment one import of it appears
outside `eval/`, no one can say which of our lemmas came from where.

So the guard is mechanical: the string may appear in the QA directory and in
this file, and nowhere else.
"""

from __future__ import annotations

from pathlib import Path

import pytest

PACKAGE = Path(__file__).resolve().parents[1]
FORBIDDEN = "simplemma"

#: Where a second opinion is allowed to be consulted.
ALLOWED = (PACKAGE / "eval", PACKAGE / "tests" / "test_qa_guard.py")

#: What is actually forbidden is USING it — importing it or calling it. Naming
#: it in prose is how a decision gets recorded, and the first version of this
#: guard failed on two comments explaining why simplemma is not a source, which
#: is precisely the writing that should exist.
USES = ("import simplemma", "from simplemma", "simplemma.", "simplemma(")

SEARCHED = ("src", "tests", "spike", "lexicon")


def sources() -> list[Path]:
    found: list[Path] = []
    for directory in SEARCHED:
        found.extend(sorted((PACKAGE / directory).rglob("*.py")))
        found.extend(sorted((PACKAGE / directory).rglob("*.yaml")))
    return [
        path
        for path in found
        if not any(path == allowed or allowed in path.parents for allowed in ALLOWED)
    ]


@pytest.mark.parametrize("path", sources(), ids=lambda p: str(p.relative_to(PACKAGE)))
def test_no_source_outside_the_qa_lane_uses_simplemma(path: Path):
    text = path.read_text(encoding="utf-8")
    used = [marker for marker in USES if marker in text]
    assert not used, (
        f"{path.relative_to(PACKAGE)} uses {FORBIDDEN!r} ({used}). It is a "
        "read-only QA oracle (β(QA), T7): it may be compared against in "
        "`eval/`, and it may never be imported by anything that writes a layer"
    )


def test_the_qa_script_exists_and_says_so():
    script = PACKAGE / "eval" / "qa_simplemma.py"
    assert script.exists()
    text = script.read_text(encoding="utf-8")
    assert "never writes into a layer" in text


def test_simplemma_is_a_dev_dependency_only():
    """It must not be in `dependencies` — a runtime dep would ship it."""
    pyproject = (PACKAGE / "pyproject.toml").read_text(encoding="utf-8")
    runtime = pyproject.split("dependencies = [", 1)[1].split("]", 1)[0]
    assert FORBIDDEN not in runtime
    assert FORBIDDEN in pyproject  # ...but it IS declared, in the dev group
