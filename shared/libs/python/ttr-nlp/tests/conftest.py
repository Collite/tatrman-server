# SPDX-License-Identifier: Apache-2.0
"""Shared fixtures — the hero corpus loader."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
import yaml

FIXTURES = Path(__file__).parent / "fixtures"
HERO_DIR = FIXTURES / "hero"


def load_engines(name: str) -> dict[str, Any]:
    """Load a canned `*.engines.json` case from the hero corpus."""
    return json.loads((HERO_DIR / f"{name}.engines.json").read_text(encoding="utf-8"))


def load_expected(name: str) -> dict[str, Any]:
    """Load the `*.expected.yaml` companion for a hero."""
    path = HERO_DIR / f"{name}.expected.yaml"
    return yaml.safe_load(path.read_text(encoding="utf-8"))


@pytest.fixture
def hero_cs_invoices() -> dict[str, Any]:
    return load_engines("hero-cs-invoices")


@pytest.fixture
def hero_cs_role() -> dict[str, Any]:
    return load_engines("hero-cs-role")


@pytest.fixture
def sample_en_invoices() -> dict[str, Any]:
    return load_engines("sample-en-invoices")
