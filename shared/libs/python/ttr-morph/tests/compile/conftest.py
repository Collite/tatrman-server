# SPDX-License-Identifier: Apache-2.0
"""Paths to the shipped layer fixtures — the compiler's real input."""

from __future__ import annotations

from pathlib import Path

import pytest

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "layers"


@pytest.fixture
def hand_layer_path() -> str:
    return str(FIXTURES / "core-hand.morph.yaml")


@pytest.fixture
def kaikki_layer_path() -> str:
    return str(FIXTURES / "core-kaikki.morph.yaml")


@pytest.fixture
def world_layer_path() -> str:
    """A world's layer, kept in a subdirectory of its own.

    Not beside the core layers, so that `layers/*.morph.yaml` means "the layers
    of the core snapshot" — which is what the compile command in every doc and
    task list globs. A world layer in that glob would refuse to compile
    (`LM-MORPH-004`), correctly and confusingly.
    """
    return str(FIXTURES / "world" / "world-dfp.morph.yaml")


@pytest.fixture
def layer_paths(hand_layer_path, kaikki_layer_path, world_layer_path) -> list[str]:
    return [hand_layer_path, kaikki_layer_path, world_layer_path]
