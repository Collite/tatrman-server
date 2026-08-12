# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.2 T3 — the migrations, against a real Postgres (component tier).

The unit suite runs the whole service on SQLite, which proves the *logic* and
proves nothing about the schema a deployment actually gets: `jsonb`, the unique
constraints as Postgres enforces them, and — the one that would bite silently —
whether `alembic upgrade head` produces the tables the models expect.

Opt-in: `MORPH_STUDIO_PG_URL=postgresql+psycopg://... just test-py
services/morph-studio -m component`. Skipped, loudly, when there is no database,
because a component tier that quietly passed because its subject was absent is
worse than one that does not run.
"""

from __future__ import annotations

import os

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import inspect, text
from sqlalchemy.exc import IntegrityError
from ttrmorph.enrich.cascade import LAYER_CORE

from morph_studio import status as st
from morph_studio import store
from morph_studio.db import make_engine, make_sessionmaker
from morph_studio.models import Entry, QueueItem

pytestmark = pytest.mark.component

ENV_PG = "MORPH_STUDIO_PG_URL"


@pytest.fixture(scope="module")
def pg_url() -> str:
    url = (os.getenv(ENV_PG) or "").strip()
    if not url:
        pytest.skip(
            f"no {ENV_PG} — start a Postgres and point this at it, e.g. "
            "docker run -e POSTGRES_PASSWORD=morph -p 55433:5432 postgres:17 and "
            f"{ENV_PG}=postgresql+psycopg://postgres:morph@127.0.0.1:55433/postgres"
        )
    return url


@pytest.fixture
def migrated(pg_url, monkeypatch):
    """A database at `head`, torn back down to `base` afterwards.

    Both directions on purpose: a migration whose `downgrade` does not work is a
    migration nobody can roll back at three in the morning.
    """
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]
    monkeypatch.setenv("MORPH_STUDIO_DB_URL", pg_url)
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))

    command.upgrade(config, "head")
    engine = make_engine(pg_url)
    try:
        yield engine
    finally:
        engine.dispose()
        command.downgrade(config, "base")


def test_the_migration_builds_the_tables_the_models_expect(migrated):
    tables = set(inspect(migrated).get_table_names())
    assert {"entry", "entry_form", "queue_item", "audit"} <= tables


def test_the_proposal_column_is_jsonb_and_not_text(migrated):
    """The reason contracts §7 says jsonb: it is queryable, and text is not."""
    types = {
        column["name"]: str(column["type"])
        for column in inspect(migrated).get_columns("queue_item")
    }
    assert types["proposal"] == "JSONB"

    with migrated.connect() as connection:
        # If it were TEXT this raises rather than answering.
        connection.execute(text("SELECT '{\"a\": 1}'::jsonb -> 'a'"))


def test_postgres_enforces_the_two_constraints_that_carry_the_design(migrated):
    factory = make_sessionmaker(migrated)
    session = factory()
    try:
        store.create_entry(
            session, lemma="tržba", upos="NOUN", layer=LAYER_CORE, vzor="žena"
        )
        session.commit()

        session.add(
            Entry(
                lemma="tržba",
                upos="NOUN",
                layer=LAYER_CORE,
                vzor="žena",
                flags=[],
                status=st.PROPOSED,
            )
        )
        with pytest.raises(IntegrityError):
            session.commit()
        session.rollback()

        store.ingest(session, world="dfp", token="Kauflandu", run=False)
        session.commit()
        session.add(
            QueueItem(world="dfp", token="Kauflandu", status=st.PROPOSED, proposal={})
        )
        with pytest.raises(IntegrityError):
            session.commit()
        session.rollback()
    finally:
        session.close()


def test_the_whole_cascade_runs_against_postgres(migrated):
    """The unit suite proves the logic; this proves it survives the driver."""
    factory = make_sessionmaker(migrated)
    session = factory()
    try:
        item, created = store.ingest(session, world="dfp", token="Kauflandu")
        session.commit()

        assert created
        assert item.status == st.AUTO_VALIDATED
        assert item.proposal["proposals"][0]["lemma"] == "Kaufland"
        entry = session.get(Entry, item.entry_id)
        assert {row.form for row in entry.forms} >= {"Kaufland", "Kauflandu"}
    finally:
        session.close()
