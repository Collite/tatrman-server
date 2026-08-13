# SPDX-License-Identifier: Apache-2.0
"""Fixtures for the morph-studio suite.

**SQLite, and the whole service on top of it.** Every endpoint, the status
machine, the cascade and the export run against an in-memory database here. The
schema is the same one alembic writes — `models.JsonB` is a dialect variant, not
a second definition — and a component test (`-m component`) runs the migrations
themselves against a real Postgres when `MORPH_STUDIO_PG_URL` names one. A unit
suite that needed a database daemon is a unit suite that stops being run.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from ttrmorph.enrich.llm import LlmLeg, LlmSpec

from morph_studio.api import create_app
from morph_studio.config import Settings

# ── the proto-stub bootstrap ─────────────────────────────────────────────────
#
# Same shape as `ttr-nlp`'s own conftest and for the same reason: the stubs the
# front's client needs are generated from `shared/proto` and gitignored, so
# generating them at collection keeps `just test-py` self-contained rather than
# depending on a remembered manual step whose absence looks like a missing
# feature. Only the gate-7 component test needs them, and it needs them on a
# developer's machine, which is the one place a wheel build has not run.
_WHEEL = Path(__file__).resolve().parents[3] / "shared" / "libs" / "python" / "ttr-nlp"
_STUBS = _WHEEL / "generated" / "org" / "tatrman" / "nlp" / "v1" / "nlp_pb2_grpc.py"

if not _STUBS.exists():  # pragma: no cover - one-time bootstrap
    import importlib
    import runpy

    try:
        runpy.run_path(str(_WHEEL / "scripts" / "gen_proto.py"), run_name="__main__")
    except SystemExit:
        pass
    finally:
        # MANDATORY, and in a `finally`. `pythonpath` puts `generated/` on
        # `sys.path` before this file runs; when the directory does not exist
        # yet the path finder caches that absence, and creating it a moment
        # later does not invalidate the cache — so `import org.tatrman…` fails
        # with `No module named 'org'` while the stubs sit on disk in plain
        # sight.
        importlib.invalidate_caches()


WORLD = "dfp"

FIXTURES = Path(__file__).parent / "fixtures"

#: The hero of detailed-design §9, and the token every fixture starts from.
KAUFLANDU = "Kauflandu"


@pytest.fixture(scope="session")
def core_snapshot(tmp_path_factory) -> Path:
    """A compiled core snapshot for the Q-7 tests to hang an overlay on.

    Compiled, not committed — the same reasoning as the front's own fixture
    (`services/nlp/tests/conftest.py`): the layer file is what a human edits,
    and an artifact checked in beside it drifts from the compiler that reads it.
    """
    from ttrmorph.compile.snapshot import compile_layers

    result = compile_layers(
        [str(FIXTURES / "core.morph.yaml")],
        snapshot_version="0.1.0",
        output="cs.morph.snap",
    )
    assert result.ok, [d.message for d in result.diagnostics]
    directory = tmp_path_factory.mktemp("morph-core")
    for name, text in result.outputs.items():
        (directory / name).write_text(text, encoding="utf-8")
    return directory / "cs.morph.snap"


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(
        world=WORLD,
        db_url="sqlite+pysqlite:///:memory:",
        export_dir=str(tmp_path / "export"),
        overlay_dir="",  # off unless a test asks for Q-7
        front_target="",
    )


@pytest.fixture
def app(settings):
    return create_app(settings, schema=True)


@pytest.fixture
def client(app):
    with TestClient(app) as client:
        yield client


@pytest.fixture
def session(app):
    """A session on the app's own engine, for asserting on rows."""
    factory = app.state.sessionmaker
    session = factory()
    try:
        yield session
    finally:
        session.close()


def a_leg(answer) -> LlmLeg:
    """An LLM leg over a canned answer — a dict, or an exception to raise."""

    def transport(system: str, user: str) -> str:
        if isinstance(answer, Exception):
            raise answer
        return json.dumps(answer)

    return LlmLeg(LlmSpec(url="http://gateway", model="m"), transport=transport)


def report(token: str, **kwargs) -> dict:
    """One spool row, in the shape `POST /ingest` takes (contracts §6)."""
    return {"world": WORLD, "token": token, "verdict": "miss", "count": 1, **kwargs}
