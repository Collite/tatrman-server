# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S1 — the gRPC service binding, driven in-process.

Boots `NlpService` on an ephemeral port and exercises Analyze / GetStatus /
BatchLemmatize over a real grpc.aio channel — the automated form of the S1
verify ("grpcurl against a locally-run front returns an Analyze response with a
populated used[]; GetStatus returns the capability matrix"). Engines are stubbed
(no network).
"""

from __future__ import annotations

import grpc
import pytest

from org.tatrman.nlp.v1 import nlp_pb2, nlp_pb2_grpc

from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
)
from nlp_service.api.grpc_server import NlpServicer
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import EngineResult, Token


def _config() -> AppConfig:
    return AppConfig(
        engines=EnginesConfig(
            morphodita=BackendConfig(
                url="http://morphodita:8080/tag",
                model="czech-morfflex2.0-pdtc1.0-220710",
                model_version="czech-morfflex2.0-pdtc1.0-220710",
            ),
            nametag3=BackendConfig(
                url="http://nametag3:8001/recognize",
                model="nametag3-czech-cnec2.0-240830",
                model_version="nametag3-czech-cnec2.0-240830",
            ),
            stanza=BackendConfig(url="http://stanza:8090", model="stanza-cs-en", model_version="1.10.0"),
            spacy=BackendConfig(url="http://spacy:8091", model="en_core_web_md", model_version="3.8.0"),
            langid=LangidEngineConfig(model_version="lingua-2.0"),
        ),
        op_routing={
            "TOKENIZE.cs": "morphodita",
            "LEMMATIZE.cs": "morphodita",
            "NER.cs": "nametag3",
            "TOKENIZE.en": "stanza",
            "DETECT_LANGUAGE": "langid",
        },
        default_language="cs",
    )


async def _boot(registry: EngineRegistry) -> tuple[grpc.aio.Server, int]:
    server = grpc.aio.server()
    nlp_pb2_grpc.add_NlpServiceServicer_to_server(NlpServicer(registry._config, registry), server)
    port = server.add_insecure_port("localhost:0")
    await server.start()
    return server, port


@pytest.mark.asyncio
async def test_analyze_over_grpc_populates_used():
    registry = EngineRegistry(_config())
    registry.get_engine("morphodita").analyze = lambda text, lang, ops: EngineResult(
        tokens=[Token(text="Octavie", char_start=0, char_end=7, lemma="Octavia", upos="PROPN")]
    )
    server, port = await _boot(registry)
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as ch:
            stub = nlp_pb2_grpc.NlpServiceStub(ch)
            resp = await stub.Analyze(
                nlp_pb2.AnalyzeRequest(text="Octavie", language="cs", ops=[nlp_pb2.LEMMATIZE])
            )
    finally:
        await server.stop(None)

    assert resp.tokens[0].lemma == "Octavia"
    assert resp.detected_language == "cs"
    assert len(resp.used) == 1
    assert resp.used[0].engine == "morphodita"
    assert resp.used[0].model  # S-1: never blank on the wire


@pytest.mark.asyncio
async def test_getstatus_returns_capability_matrix():
    registry = EngineRegistry(_config())
    server, port = await _boot(registry)
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as ch:
            stub = nlp_pb2_grpc.NlpServiceStub(ch)
            status = await stub.GetStatus(nlp_pb2.StatusRequest())
    finally:
        await server.stop(None)

    assert status.ready is True
    caps = {(c.language, nlp_pb2.NlpOp.Name(c.op)): c for c in status.capabilities}
    lemma_cs = caps[("cs", "LEMMATIZE")]
    assert lemma_cs.engine == "morphodita"
    assert lemma_cs.tier == nlp_pb2.SELF_HOSTED_PINNED
    assert lemma_cs.model_version


@pytest.mark.asyncio
async def test_batch_lemmatize_over_grpc():
    registry = EngineRegistry(_config())
    registry.get_engine("morphodita").batch_lemmatize = lambda texts, lang: [
        ["Octavia"], ["pobočka"]
    ]
    server, port = await _boot(registry)
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as ch:
            stub = nlp_pb2_grpc.NlpServiceStub(ch)
            resp = await stub.BatchLemmatize(
                nlp_pb2.BatchLemmatizeRequest(texts=["Octavie", "pobočkách"], language="cs")
            )
    finally:
        await server.stop(None)

    assert [list(r.lemmas) for r in resp.results] == [["Octavia"], ["pobočka"]]
    assert resp.used[0].engine == "morphodita"
    assert resp.used[0].model
