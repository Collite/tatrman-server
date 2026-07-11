"""NLP service package.

Root-level logging is configured *here* (not in main.py) because the
Dockerfile launches the app via `uvicorn nlp_service.api.routes:app`
which bypasses main.py entirely — any basicConfig there would never
run in production. Importing any submodule (`nlp_service.api.routes`,
`nlp_service.engines.stanza_engine`, …) walks through this package
init first, so configuring logging on top of this file guarantees the
root logger is set up before any engine grabs its own logger.

`force=True` overrides any prior handlers (uvicorn installs its own
on the root logger by default; without force=True they'd swallow our
basicConfig).
"""

import logging
import os
import sys

_log_level_name = os.getenv("LOG_LEVEL", "INFO").upper()
_log_level = getattr(logging, _log_level_name, logging.INFO)
logging.basicConfig(
    level=_log_level,
    stream=sys.stdout,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    force=True,
)

# Keep the always-noisy underlayers at WARN even when the service-level
# is DEBUG. uvicorn.access spams every healthz hit; httpx/urllib3 spam
# every internal HTTP call.
for _noisy in ("httpx", "httpcore", "urllib3", "uvicorn.access"):
    logging.getLogger(_noisy).setLevel(logging.WARNING)
logging.getLogger("nlp-service").setLevel(_log_level)
logging.getLogger("nlp_service").setLevel(_log_level)
logging.getLogger(__name__).info(
    "NLP service log level set to %s (override via LOG_LEVEL env)",
    _log_level_name,
)

from nlp_service.api.routes import create_app  # noqa: E402

__all__ = ["create_app"]
