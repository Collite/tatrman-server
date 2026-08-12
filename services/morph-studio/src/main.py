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
    uvicorn.run(app, host=settings.host, port=settings.port, log_level="info")
    return 0


if __name__ == "__main__":
    sys.exit(main())
