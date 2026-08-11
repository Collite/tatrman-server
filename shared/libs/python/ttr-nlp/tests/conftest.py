# SPDX-License-Identifier: Apache-2.0
"""Shared fixtures — the hero corpus loader, and the proto-stub bootstrap.

The `org.tatrman.{nlp,common}.v1` stubs the serializer and the client need are
generated from `shared/proto` and gitignored, exactly as `services/nlp` does it.
Generating them at collection keeps `uv run pytest` self-contained instead of
requiring a remembered manual step whose absence looks like a missing feature.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
import yaml

_WHEEL_DIR = Path(__file__).resolve().parent.parent
_STUB_MARKER = (
    _WHEEL_DIR / "generated" / "org" / "tatrman" / "nlp" / "v1" / "nlp_pb2_grpc.py"
)

if not _STUB_MARKER.exists():  # pragma: no cover - one-time bootstrap
    import runpy

    try:
        runpy.run_path(
            str(_WHEEL_DIR / "scripts" / "gen_proto.py"), run_name="__main__"
        )
    except SystemExit:
        pass

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
