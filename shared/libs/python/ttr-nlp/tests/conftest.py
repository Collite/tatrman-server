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
    import importlib
    import runpy

    try:
        runpy.run_path(
            str(_WHEEL_DIR / "scripts" / "gen_proto.py"), run_name="__main__"
        )
    except SystemExit:
        # The script ends in `raise SystemExit(main())`; under `runpy` that
        # reaches here rather than exiting the interpreter.
        pass
    finally:
        # MANDATORY, and in a `finally` so a partial generation cannot skip it.
        # `pythonpath = ["src", "generated"]` puts `generated/` on `sys.path`
        # before this file runs. When the directory does not exist yet, the path
        # finder caches that absence — and creating it a moment later does not
        # invalidate the cache, so every `import org.tatrman…` fails with
        # `No module named 'org'` while the stubs sit on disk in plain sight.
        #
        # It stayed hidden while the wheel's own editable build generated the
        # tree as a side effect, which put it there before pytest started. That
        # build no longer generates anything (an editable install must not need
        # `shared/proto` — see hatch_build.py), so this bootstrap is now the only
        # thing creating the directory, and the cache is always stale without it.
        importlib.invalidate_caches()

FIXTURES = Path(__file__).parent / "fixtures"
HERO_DIR = FIXTURES / "hero"


def pytest_addoption(parser: pytest.Parser) -> None:
    """`--update-golden` — rewrite golden expectations instead of asserting.

    The morph tokenizer's matrix (`tests/morph/golden/`) is checked-in expected
    output, and hand-editing a dozen JSON offset tables after a deliberate
    profile change is the kind of chore that ends with the matrix being deleted.
    The runner that honours this flag also *fails* the run afterwards, so
    regenerating can never be mistaken for passing.
    """
    parser.addoption(
        "--update-golden",
        action="store_true",
        default=False,
        help="rewrite golden expectation files from current output, then fail",
    )


@pytest.fixture
def update_golden(request: pytest.FixtureRequest) -> bool:
    return bool(request.config.getoption("--update-golden"))


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
