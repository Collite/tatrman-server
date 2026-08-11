# SPDX-License-Identifier: Apache-2.0
"""RV-P8.2 T5 — the resolver-path smoke: what a `resolve.bind` caller receives.

The resolver does not read this service's internals; it reads an `AnalyzeResponse`
over gRPC and carries `parse.usedList` straight into every binding's
`BindingProvenance.modelVersions` (`ResolverPipeline.kt`). So the per-op engine
identity RV-40 promised reaches a `resolve.bind` consumer **if and only if** it is
on this wire, per op, with a non-blank model — which is what this file asserts,
over a real channel rather than against the orchestrator's return value.

⚑ Scope, recorded honestly: the Kotlin half of that hop is the resolver's own
tier and is not re-proven here (this repo has no cross-language harness that
boots both). What is proven is that the emulated engine puts a distinguishable
identity on the wire the resolver already reads — the half that was missing.
"""

from __future__ import annotations

import json

import grpc
import pytest

from org.tatrman.nlp.v1 import nlp_pb2, nlp_pb2_grpc

from nlp_service.api.grpc_server import NlpServicer
from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
    LlmEmulatedConfig,
)
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import EngineResult, Token
from nlp_service.engines.llm_emulated_engine import (
    EMULATED_ENGINE_NAME,
    LlmEmulatedEngine,
)

TEXT = "Zobraz faktury zákazníka Microsoft"


class FakeGateway:
    def chat(self, *, system: str, user: str, purpose: str = "") -> str:
        return json.dumps({"entities": [{"text": "Microsoft", "label": "ORGANIZATION"}]})


def _routed_registry() -> EngineRegistry:
    config = AppConfig(
        engines=EnginesConfig(
            morphodita=BackendConfig(
                url="http://morphodita:8080/tag",
                model="czech-morfflex2.0-pdtc1.0-220710",
                model_version="czech-morfflex2.0-pdtc1.0-220710",
            ),
            nametag3=BackendConfig(enabled=False),
            stanza=BackendConfig(enabled=False),
            spacy=BackendConfig(enabled=False),
            langid=LangidEngineConfig(model_version="lingua-2.0"),
            llm_emulated=LlmEmulatedConfig(
                enabled=True, url="http://llm-gateway:8080", model="claude-haiku-4-5"
            ),
        ),
        op_routing={
            "TOKENIZE.cs": "morphodita",
            "LEMMATIZE.cs": "morphodita",
            "NER.cs": EMULATED_ENGINE_NAME,
            "DETECT_LANGUAGE": "langid",
        },
        default_language="cs",
    )
    registry = EngineRegistry(config)
    registry.get_engine("morphodita").analyze = lambda text, lang, ops: EngineResult(  # type: ignore[method-assign]
        tokens=[
            Token(text="Zobraz", char_start=0, char_end=6, lemma="zobrazit", upos="VERB"),
            Token(text="Microsoft", char_start=25, char_end=34, lemma="Microsoft", upos="PROPN"),
        ]
    )
    registry._engines[EMULATED_ENGINE_NAME] = LlmEmulatedEngine(
        config.engines.llm_emulated, client=FakeGateway()
    )
    return registry


async def _boot(registry: EngineRegistry):
    server = grpc.aio.server()
    nlp_pb2_grpc.add_NlpServiceServicer_to_server(NlpServicer(registry._config, registry), server)
    port = server.add_insecure_port("localhost:0")
    await server.start()
    return server, port


@pytest.mark.asyncio
async def test_the_wire_carries_a_different_engine_identity_per_op():
    registry = _routed_registry()
    server, port = await _boot(registry)
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as ch:
            stub = nlp_pb2_grpc.NlpServiceStub(ch)
            response = await stub.Analyze(
                nlp_pb2.AnalyzeRequest(
                    text=TEXT,
                    language="cs",
                    ops=[nlp_pb2.TOKENIZE, nlp_pb2.LEMMATIZE, nlp_pb2.NER],
                )
            )
    finally:
        await server.stop(None)

    used = {u.op: u for u in response.used}
    assert used["LEMMATIZE"].engine == "morphodita"
    assert used["NER"].engine == EMULATED_ENGINE_NAME
    # S-1's rule holds for emulation too: never a blank model on the wire, and
    # the version names the prompt revision, so two deployments running the same
    # model on different templates are distinguishable in a binding's provenance.
    assert used["NER"].model == "claude-haiku-4-5"
    assert used["NER"].model_version == "claude-haiku-4-5/tpl-1"
    assert response.entities[0].text == "Microsoft"


@pytest.mark.asyncio
async def test_getstatus_advertises_the_emulated_row_as_unpinned():
    """What an estate reads before deciding to trust an answer. The tier is the
    caution: `REMOTE_UNPINNED` is what the matrix already uses to mean
    "non-conformant for parity/determinism", and emulation is exactly that."""
    registry = _routed_registry()
    server, port = await _boot(registry)
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as ch:
            stub = nlp_pb2_grpc.NlpServiceStub(ch)
            status = await stub.GetStatus(nlp_pb2.StatusRequest())
    finally:
        await server.stop(None)

    rows = {(c.language, nlp_pb2.NlpOp.Name(c.op)): c for c in status.capabilities}
    ner_cs = rows[("cs", "NER")]
    assert ner_cs.engine == EMULATED_ENGINE_NAME
    assert ner_cs.tier == nlp_pb2.REMOTE_UNPINNED
    assert rows[("cs", "LEMMATIZE")].tier == nlp_pb2.SELF_HOSTED_PINNED
