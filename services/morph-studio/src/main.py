# SPDX-License-Identifier: Apache-2.0
"""morph-studio's entry point.

One server, one world, one port. There is no gRPC half here — this is an
internal editorial tool (⚑LMP-D5), not a runtime service on the estate.
"""

from __future__ import annotations

import logging
import sys

import uvicorn
from ttrmorph.enrich.llm import LlmLeg

from morph_studio import provisional
from morph_studio.api import create_app
from morph_studio.config import ConfigError, Settings

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger("morph_studio")


def main() -> int:
    try:
        settings = Settings.from_env()
    except ConfigError as exc:
        # The same posture the front takes about its own config: a service that
        # cannot serve as written must say so at boot, not at the first request.
        logger.error("%s", exc)
        return 2

    leg = LlmLeg.from_env()
    logger.info(
        "morph-studio world=%s mode=%s llm=%s provisional=%s overlay=%s front=%s",
        settings.world,
        settings.mode,
        "on" if leg else "off (guesser → human)",
        settings.provisional,
        settings.overlay_dir or "-",
        settings.front_target or "-",
    )

    # ⚑ The schema is alembic's — except on SQLite, which is the laptop and
    # test lane and never a deployment. Running migrations against a throwaway
    # file buys nothing, while a local run that greeted an analyst with "no such
    # table: entry" would cost every one of them the same five minutes.
    sqlite = settings.db_url.startswith("sqlite")
    app = create_app(settings, llm=leg, schema=sqlite)
    _seed_overlay(app, settings)
    uvicorn.run(app, host=settings.host, port=settings.port, log_level="info")
    return 0


def _seed_overlay(app, settings: Settings) -> None:
    """Write the world overlay at boot, even when it is empty.

    ⚑⚑ Found by the NLS-P9.3 T7 gate, and it stops the stack from starting at
    all. The front declares its morph sources STATICALLY and loads them
    fail-all (NL-15): one unreadable source and nothing loads, so every
    `morph: true` pipeline answers FAILED_PRECONDITION. A deployment that lists
    the Q-7 overlay among those sources — which it must, or the overlay never
    serves — therefore has no lexicon at all until the first proper noun
    auto-validates and creates the file. The enrichment loop cannot bootstrap
    itself: the miss that would produce the overlay needs the snapshot that the
    missing overlay is holding down.

    So the studio owns that file unconditionally. An overlay with no entries is
    a valid layer file, it compiles, and the front loads it as an empty world —
    which is the truth on a fresh deployment.

    Never fatal: a studio that could not write its overlay is still the place a
    reviewer works the queue, and the failure is already visible on the front as
    `LM-MORPH-001`.
    """
    if not settings.overlay_dir:
        return
    try:
        session = app.state.sessionmaker()
        try:
            result = provisional.emit_overlay(session, settings)
        finally:
            session.close()
    except Exception as exc:  # noqa: BLE001 — a boot convenience, not a gate
        logger.warning("could not seed the world overlay at boot: %s", exc)
        return
    if result.compiled:
        logger.info(
            "seeded %s (%d permanent, %d provisional) — the front's declared "
            "source now exists",
            settings.overlay_dir,
            result.permanent,
            result.provisional,
        )
    else:
        logger.warning("overlay not seeded: %s", "; ".join(result.notes) or "?")


if __name__ == "__main__":
    sys.exit(main())
