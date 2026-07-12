# SPDX-License-Identifier: Apache-2.0
"""Main entry point for the ttr-nlp service (RG-P1.S1).

Runs the **gRPC server (the service contract)** and the **FastAPI REST mirror
(dev/health only)** on one asyncio loop. Logging root config lives in
`nlp_service/__init__.py` so it also fires when uvicorn imports the ASGI app
directly.
"""

from __future__ import annotations

import asyncio
import logging

import uvicorn

from nlp_service.api.grpc_server import serve_grpc
from nlp_service.api.routes import create_app
from nlp_service.config import load_config

logger = logging.getLogger("nlp-service")

# ASGI app for `uvicorn nlp_service.api.routes:app` / the Dockerfile entrypoint.
app = create_app()


async def _run() -> None:
    config = load_config()

    grpc_server = await serve_grpc(config)

    uvicorn_config = uvicorn.Config(
        app,
        host=config.service.host,
        port=config.service.port,
        log_level=config.log_level.lower(),
    )
    rest_server = uvicorn.Server(uvicorn_config)

    logger.info(
        "ttr-nlp up | gRPC :%d (contract) · REST :%d (dev/health mirror)",
        config.service.grpc_port,
        config.service.port,
    )
    try:
        await rest_server.serve()  # blocks until shutdown signal
    finally:
        await grpc_server.stop(grace=5)


if __name__ == "__main__":
    asyncio.run(_run())
