# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S3.T6 — the front is engine-free.

Importing the whole front (routing + orchestrator + gRPC servicer + adapters)
must NOT pull in torch / stanza / spaCy — those live only in the backend
images. The front is contract + routing + langid. This is the import-level
guarantee; the image-level guarantee (no model files / native deps) is the
Dockerfile, which no longer has a model-download stage.
"""

from __future__ import annotations

import importlib
import sys

_BANNED = ("torch", "stanza", "spacy")


def test_importing_the_front_pulls_no_engine_libraries():
    # Import every front module a running process loads.
    for mod in (
        "nlp_service.config",
        "nlp_service.engines",
        "nlp_service.floor",
        "nlp_service.pipeline.orchestrator",
        "nlp_service.api.grpc_server",
        "nlp_service.api.routes",
    ):
        importlib.import_module(mod)

    leaked = [b for b in _BANNED if b in sys.modules]
    assert not leaked, f"engine-free front imported {leaked}"


def test_engine_libraries_are_not_installed_in_the_front():
    """Belt-and-braces: the front venv doesn't even ship the engine libs."""
    import importlib.util

    for b in _BANNED:
        assert importlib.util.find_spec(b) is None, f"{b} is installed in the front env"
