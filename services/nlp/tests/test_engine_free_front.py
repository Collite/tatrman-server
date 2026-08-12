# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S3.T6 — the front is engine-free.

Importing the whole front (routing + orchestrator + gRPC servicer + adapters)
must NOT pull in torch / stanza / spaCy — those live only in the backend
images. The front is contract + routing + langid. This is the import-level
guarantee; the image-level guarantee (no model files / native deps) is the
Dockerfile, which no longer has a model-download stage.

NLS-P3.2 makes this load-bearing in a new way. The front now runs the whole
`ttr-nlp` suite in-process — rule engine, gazetteers, pack loader, serializer —
and that is only possible because the wheel is model-free by design (⚑NLS-D3).
gatenlp ships `lib_stanza` / `lib_spacy` convenience importers that `import
stanza` / `import spacy` at module top, so one innocent-looking import inside the
wheel would drag torch into an image built to be model-free. The wheel has its own
test for that; this one checks the front after the dependency was added, which is
the assertion that would actually fail in the image build.
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
        # NLS-P3.2: the pipeline surface, which imports the whole wheel.
        "nlp_service.packs_state",
        "nlp_service.pipeline.runner",
        # NLS-P9.1 (LM): the Czech morphology now runs IN this process. That is
        # only allowed because `ttrnlp.morph` is stdlib plus a file it reads —
        # no model, no torch, no native anything. An in-process lemmatizer is
        # exactly the kind of thing that arrives with a dependency, so the front
        # asserts it from its own side rather than trusting the wheel's guard.
        "nlp_service.morph_state",
        "nlp_service.morph_queue",
    ):
        importlib.import_module(mod)

    leaked = [b for b in _BANNED if b in sys.modules]
    assert not leaked, f"engine-free front imported {leaked}"


def test_the_ttr_nlp_wheel_brings_no_engine_with_it():
    """The dependency added at NLS-P3.2, checked from the front's side.

    Named separately from the sweep above so the failure says which half broke:
    the front importing an engine directly is a different mistake from the wheel
    acquiring one, and they have different fixes.
    """
    for mod in (
        "ttrnlp.doc",
        "ttrnlp.doc.serialize",
        "ttrnlp.rules",
        "ttrnlp.gazetteer",
        "ttrnlp.packs.loader",
        "ttrnlp.packs.validate",
        "ttrnlp.morph",
    ):
        importlib.import_module(mod)

    leaked = [b for b in _BANNED if b in sys.modules]
    assert not leaked, f"ttr-nlp pulled in {leaked} — see the wheel's ⚑NLS-D3 guard"


def test_engine_libraries_are_not_installed_in_the_front():
    """Belt-and-braces: the front venv doesn't even ship the engine libs."""
    import importlib.util

    for b in _BANNED:
        assert importlib.util.find_spec(b) is None, f"{b} is installed in the front env"
