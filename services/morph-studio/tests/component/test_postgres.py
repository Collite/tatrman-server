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
import time

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import inspect, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import IntegrityError, OperationalError
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


#: The database this test builds and destroys. NEVER the one it was pointed at.
SCRATCH_DB = "morph_studio_migrations"


@pytest.fixture(scope="module")
def scratch_url(pg_url) -> str:
    """A database of this test's own, on the server `pg_url` names.

    ⛑ `MORPH_STUDIO_PG_URL` used to be migrated **down to base** directly, which
    made this suite destructive to whatever it was aimed at. Aimed at the
    arc-gate-7 stack — which is exactly what `docker-compose.gate7.yml` invites,
    since it publishes its Postgres so one `-m component` run covers both tiers
    — it dropped the running studio's tables underneath it, and the gate then
    failed with `relation "queue_item" does not exist` in a way that looked like
    a bug in the studio.
    """
    _run(pg_url, f'DROP DATABASE IF EXISTS "{SCRATCH_DB}"', wait=True)
    _run(pg_url, f'CREATE DATABASE "{SCRATCH_DB}"')

    # ⚑ `render_as_string(hide_password=False)`, not `str(...)`. SQLAlchemy's
    # `URL.__str__` MASKS the password as `***` — which is right for a log line
    # and silently produces a URL that fails authentication when you hand it
    # back to `create_engine`. The failure reads "password authentication failed
    # for user", i.e. exactly like a wrong credential rather than a redacted one.
    yield make_url(pg_url).set(database=SCRATCH_DB).render_as_string(
        hide_password=False
    )

    _run(pg_url, f'DROP DATABASE IF EXISTS "{SCRATCH_DB}"')


def _run(url: str, statement: str, *, wait: bool = False) -> None:
    """One autocommit statement — `CREATE DATABASE` cannot run in a transaction.

    ⚑ `wait` retries the FIRST connection. A freshly started Postgres answers
    `pg_isready` while its entrypoint is still applying `POSTGRES_PASSWORD`, so
    a suite that connects the moment a compose stack comes up gets
    "password authentication failed" — a failure that reads like a wrong
    credential and is really a race.
    """
    deadline = time.monotonic() + (60.0 if wait else 0.0)
    while True:
        engine = make_engine(url).execution_options(isolation_level="AUTOCOMMIT")
        try:
            with engine.connect() as connection:
                connection.execute(text(statement))
            return
        except OperationalError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(1.0)
        finally:
            engine.dispose()


@pytest.fixture
def migrated(scratch_url, monkeypatch):
    """A database at `head`, torn back down to `base` afterwards.

    Both directions on purpose: a migration whose `downgrade` does not work is a
    migration nobody can roll back at three in the morning. Safe to do *because*
    the database is this fixture's own — see `scratch_url`.
    """
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]
    monkeypatch.setenv("MORPH_STUDIO_DB_URL", scratch_url)
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))

    command.upgrade(config, "head")
    engine = make_engine(scratch_url)
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
