# SPDX-License-Identifier: Apache-2.0
"""Alembic environment (NLS-P9.2 T3).

The URL comes from `MORPH_STUDIO_DB_URL` — the same variable the service reads —
so a migration cannot be run against a database the service is not using. There
is no fallback: a missing URL stops the migration rather than quietly creating
tables somewhere convenient.
"""

from __future__ import annotations

import os
from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool

from morph_studio.models import Base

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def _url() -> str:
    url = (os.getenv("MORPH_STUDIO_DB_URL") or "").strip()
    if not url:
        raise SystemExit(
            "MORPH_STUDIO_DB_URL is not set — alembic will not guess where the "
            "morph-studio store lives"
        )
    return url


def run_migrations_offline() -> None:
    context.configure(
        url=_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    section = config.get_section(config.config_ini_section, {})
    section["sqlalchemy.url"] = _url()
    connectable = engine_from_config(
        section, prefix="sqlalchemy.", poolclass=pool.NullPool
    )
    with connectable.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
