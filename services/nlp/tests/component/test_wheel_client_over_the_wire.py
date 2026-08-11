# SPDX-License-Identifier: Apache-2.0
"""NLS-P3.3.T5 — the wheel's `NlpClient` against the real front (component tier).

Everything else about the client is tested against a canned servicer in the
wheel's own suite, and the servicers are tested against a canned client in this
one. This is the test that puts the real halves together, and the failures it
catches are the ones neither side can see alone: a proto regenerated on one side
and not the other, a serializer that round-trips through itself but not through
the servicer's own encoder, an interceptor or a message-size limit that only
exists on a started server.

`@pytest.mark.component` — skipped by default; run with `-m component`. It boots
the front IN-PROCESS rather than in a container: the backends are canned, so there
is nothing to pull, and the thing under test is the wire between the wheel's
client and the service's servicer, not the container image. Backend-image
behaviour has its own component tests beside this one.
"""

from __future__ import annotations

import shutil

import grpc
import pytest

from org.tatrman.nlp.v1 import nlp_pb2_grpc
from ttrnlp.client.grpc import NlpClient
from ttrnlp.doc import Document

from nlp_service.api.grpc_server import NlpServicer
from nlp_service.engines import EngineRegistry
from nlp_service.packs_state import PackState

from ..pipeline.test_run_pipeline import (
    PACKS,
    TEXT,
    a_config,
    with_canned_engines,
)

pytestmark = pytest.mark.component


@pytest.fixture
async def front(tmp_path):
    """The real servicer on a real port, with a real pack snapshot.

    The pack tree is copied so the reload test can mutate it — a component test
    that edited the checked-in fixtures would leave the repo dirty and the next
    run would test something else.
    """
    packs = tmp_path / "packs"
    shutil.copytree(PACKS, packs)
    config = a_config(lane="option", packs=[str(packs)])

    registry = with_canned_engines(EngineRegistry(config))
    server = grpc.aio.server()
    nlp_pb2_grpc.add_NlpServiceServicer_to_server(
        NlpServicer(config, registry, PackState(config)), server
    )
    port = server.add_insecure_port("localhost:0")
    await server.start()

    client = NlpClient(f"localhost:{port}", timeout_s=10.0)
    try:
        yield client, packs
    finally:
        await client.close()
        await server.stop(None)


async def test_get_status_round_trips_over_the_wire(front):
    client, _ = front
    status = await client.get_status()

    assert status.ready is True
    assert status.lane == "option"
    assert len(status.pack_state.state_id) == 16
    assert status.pack_state.packs_loaded == 1


async def test_analyze_round_trips_over_the_wire(front):
    client, _ = front
    response = await client.analyze(TEXT, language="cs", ops=["TOKENIZE", "LEMMATIZE"])

    assert [t.text for t in response.tokens][:3] == ["Zobraz", "všechny", "faktury"]
    assert all(ev.model for ev in response.used)  # S-1 over the wire


async def test_run_pipeline_produces_a_document_the_client_can_read(front):
    """The whole chain, both halves real: engines → gazetteer → rules → proto →
    `doc_from_proto` → a Document with the hero's QueryPattern on it."""
    client, _ = front
    result = await client.run_pipeline(TEXT, "query-patterns", language="cs")

    assert isinstance(result.document, Document)
    (pattern,) = result.document.annset("").with_type("QueryPattern")
    assert pattern.features["query"] == "faktury_zakaznika"
    assert pattern.features["nazev_zakaznika"] == "Microsoft"
    # The trace kinds prove all three stages ran, not just the last one.
    assert {p.kind for p in result.phases} == {"engine", "gazetteer", "rules"}


async def test_the_lemma_feature_survives_the_wire_as_written(front):
    """Czech lemmas are the load-bearing feature (the gazetteer matches on them)
    and they are exactly where a UTF-8 or offset mistake would show."""
    client, _ = front
    result = await client.run_pipeline(TEXT, "query-patterns", language="cs")

    lemmas = {
        a.features.get("lemma")
        for a in result.document.annset("").with_type("Token")
    }
    assert {"faktura", "zákazník"} <= lemmas
    lookup = next(iter(result.document.annset("").with_type("Lookup")))
    assert result.document.text[lookup.start : lookup.end] in ("faktury", "zákazníka")


async def test_reload_packs_round_trips_including_a_refusal(front):
    client, packs = front

    applied = await client.reload_packs()
    assert applied.applied is True
    before = applied.state_id

    pack = packs / "hero-patterns.pack.yaml"
    pack.write_text(
        pack.read_text(encoding="utf-8").replace("control: appelt", "control: appelts"),
        encoding="utf-8",
    )

    refused = await client.reload_packs()
    assert refused.applied is False
    assert refused.state_id == before
    assert refused.diagnostics[0].code == "NLS-PACK-010"

    # And the previous snapshot is still answering, over the wire.
    result = await client.run_pipeline(TEXT, "query-patterns", language="cs")
    assert list(result.document.annset("").with_type("QueryPattern"))


async def test_an_unknown_pipeline_arrives_as_invalid_argument(front):
    client, _ = front
    with pytest.raises(grpc.aio.AioRpcError) as raised:
        await client.run_pipeline(TEXT, "nope", language="cs")

    assert raised.value.code() == grpc.StatusCode.INVALID_ARGUMENT
    # The message names what IS configured — worth showing a user verbatim.
    assert "query-patterns" in raised.value.details()
