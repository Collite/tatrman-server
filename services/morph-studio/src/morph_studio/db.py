# SPDX-License-Identifier: Apache-2.0
"""Engine and session (NLS-P9.2 T1/T3).

Synchronous SQLAlchemy under an async FastAPI, deliberately. The alternative —
`asyncpg` and an async session — buys concurrency this service will never need
(a handful of analysts and one ingest loop) and costs the two things it does
need: alembic's ordinary offline story, and a test suite that can open a
transaction and look inside it without an event loop.

Sessions are per-request and the endpoint owns the transaction boundary: a
verdict that writes an entry, its forms, a queue row and an audit line either
lands whole or not at all. An editorial store where a status moved but the
audit row did not is one whose history is fiction.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker

from morph_studio.models import Base


def make_engine(url: str) -> Engine:
    """The engine for a URL, with the one SQLite accommodation this needs.

    An in-memory SQLite database is per-connection, so the default pool would
    hand the second request a different, empty database. `StaticPool` plus a
    relaxed thread check makes `sqlite://:memory:` behave like one database for
    the whole process, which is what the test suite and a laptop expect. It is
    scoped to SQLite URLs and touches nothing a deployment runs.
    """
    if url.startswith("sqlite"):
        from sqlalchemy.pool import StaticPool

        return create_engine(
            url,
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
            future=True,
        )
    return create_engine(url, pool_pre_ping=True, future=True)


def make_sessionmaker(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, expire_on_commit=False, future=True)


def create_all(engine: Engine) -> None:
    """Create the schema from the models.

    For tests and a laptop. A deployment runs `alembic upgrade head` — the
    migrations are the schema of record, and a service that quietly created
    tables on boot would let a missing migration go unnoticed until the first
    environment that had an older database.
    """
    Base.metadata.create_all(engine)


@contextmanager
def session_scope(factory: sessionmaker[Session]) -> Iterator[Session]:
    """One transaction. Commit on the way out, roll back on the way through."""
    session = factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


__all__ = ["create_all", "make_engine", "make_sessionmaker", "session_scope"]
