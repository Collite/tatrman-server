# SPDX-License-Identifier: Apache-2.0
"""Morph fixtures for the service suite (LM, NLS-P9.1).

**The snapshot is compiled, not committed.** `ttr-morph` is a dev dependency of
this service already (the exporter's `--morph` lane), and compiling a two-dozen
entry lexicon takes milliseconds — whereas a checked-in `.morph.snap` would be a
binary-ish artifact that drifts from the compiler the day the row format
changes, and would fail with a content-hash error nobody could read. So the
fixture is the *layer file*, which is what a human edits anyway, and the
artifact is built from it once per session.

**There is a world overlay, and it is the point of the whole fixture.** The core
lexicon does not know *Microsoft* or *Kaufland* — proper nouns are not core
vocabulary and LM-10 routes them to a world's own entity layer. Without the
overlay the hero's default-lane fallback rule cannot fire, because that rule
matches `upos: PROPN` and a stem guess answers `X`. With it, it does. That is
not a detail of the fixture; it is what world overlays are *for*, and the
service tests assert both halves.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from ttrnlp.morph import load_morph

FIXTURES = Path(__file__).parent / "fixtures"
MORPH = FIXTURES / "morph"

#: The world every fixture queue and overlay belongs to.
WORLD = "dfp"


def _compile(layer: Path, out: Path, *, version: str, world: str = "") -> Path:
    """Compile one layer file into `out`. Returns the main artifact's path."""
    from ttrmorph.compile.snapshot import compile_layers

    name = f"{world}.morph.overlay" if world else "cs.morph.snap"
    result = compile_layers(
        [str(layer)], snapshot_version=version, output=name, world=world
    )
    assert result.ok, [d.message for d in result.diagnostics]
    out.mkdir(parents=True, exist_ok=True)
    for filename, text in result.outputs.items():
        (out / filename).write_text(text, encoding="utf-8")
    return out / name


@pytest.fixture(scope="session")
def morph_core(tmp_path_factory) -> Path:
    """The core snapshot: everything the hero sentences need but the names."""
    return _compile(
        MORPH / "core.morph.yaml",
        tmp_path_factory.mktemp("morph-core"),
        version="0.1.0",
    )


@pytest.fixture(scope="session")
def morph_overlay(tmp_path_factory) -> Path:
    """The `dfp` world overlay: the proper nouns the core does not carry."""
    return _compile(
        MORPH / "world-dfp.morph.yaml",
        tmp_path_factory.mktemp("morph-overlay"),
        version="0.1.0",
        world=WORLD,
    )


@pytest.fixture(scope="session")
def morph_sources(morph_core, morph_overlay) -> list[str]:
    """Core first, then the overlay — the order `load_morph` requires."""
    return [str(morph_core), str(morph_overlay)]


@pytest.fixture(scope="session")
def morph_state(morph_sources):
    return load_morph(morph_sources)
