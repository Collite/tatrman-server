# SPDX-License-Identifier: Apache-2.0
"""The repo's own artifact, compiled once for the acceptance tests.

The named cases are only worth anything against the *real* lexicon, so this
fixture compiles `lexicon/cs/` exactly as the publish lane does — same layers,
same order, same frequency table — and hands back the loaded state. Compiling
rather than reading `dist/` is deliberate: a committed artifact on somebody's
disk is not evidence about the layer files in the commit.

⚑ The layer ORDER is the precedence order (last wins). It is duplicated from
the justfile's `morph_layers` and `test_publish_lane.py` asserts the two agree,
because a test that quietly compiled a different artifact from the one CI cuts
would be a green suite over a broken release.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from ttrnlp.morph import load_morph

from ttrmorph.compile import compile_layers, read_frequencies

PACKAGE = Path(__file__).resolve().parents[2]
LEXICON = PACKAGE / "lexicon" / "cs"

#: Weakest to strongest: the corpus says a form exists, Wiktionary says what its
#: paradigm is, a human overrules both.
LAYER_ORDER = ("core-cac", "core-kaikki", "core-hand")


@pytest.fixture(scope="session")
def repo_state():
    result = compile_layers(
        [str(LEXICON / f"{name}.morph.yaml") for name in LAYER_ORDER],
        snapshot_version="0.0.0-test",
        frequencies=read_frequencies(LEXICON / "cac-freq.tsv"),
    )
    assert result.ok, [d.message for d in result.diagnostics if d.severity == "error"]
    return _load(result.outputs)


def _load(outputs, tmp: Path | None = None):
    """Write the compiler's outputs somewhere and load them as the runtime does.

    Through the real loader, from real files: the compiler's in-memory output is
    not the thing the service reads, and every format ruling this arc has made
    was about the bytes.
    """
    import tempfile

    directory = Path(tmp or tempfile.mkdtemp(prefix="ttrmorph-eval-"))
    for name, text in outputs.items():
        (directory / name).write_text(text, encoding="utf-8")
    body = directory / "cs.morph.snap"
    parts = sorted(directory.glob("*.morph.part"))
    return load_morph([str(body), *(str(part) for part in parts)])


@pytest.fixture
def write_and_load():
    """`(outputs, dir) -> MorphState` — see `_load`."""
    return _load
