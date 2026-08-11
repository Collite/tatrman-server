# SPDX-License-Identifier: Apache-2.0
"""NLS-P3.3.T1 — `NlpClient` against a real in-process gRPC server.

The server here is a **stub servicer**, not the real one. That is a deliberate
choice and worth stating, because the task list allowed for importing the
service's servicer: the wheel is published and installed without
`services/nlp` (nlp-mcp, the DFP model-validator), so a test that needed the
service to run would be testing something no consumer can reproduce. What the
client owes its callers is that it speaks the contract correctly — sends the right
fields, applies a deadline, turns an `AnnotatedDocument` back into a `Document` —
and a canned servicer pins all of that. The real servicer is exercised on its own
side, over its own channel, in `services/nlp/tests/pipeline/test_run_pipeline.py`.

The client's actual value-add is the last of those: `run_pipeline` hands back a
gatenlp `Document`, not a wire message. Everything else about it is thin on
purpose.
"""

from __future__ import annotations

import grpc
import pytest
from org.tatrman.common.v1 import response_message_pb2 as common_pb2
from org.tatrman.nlp.v1 import nlp_pb2, nlp_pb2_grpc

from ttrnlp.client.grpc import NlpClient
from ttrnlp.doc import Document
from ttrnlp.doc.serialize import doc_to_proto

TEXT = "Zobraz všechny faktury od zákazníka Microsoft"


def a_document() -> Document:
    """What the front would have built: tokens, a Lookup, a QueryPattern."""
    doc = Document(TEXT)
    doc.features["language"] = "cs"
    doc.features["pipeline"] = "query-patterns"
    annset = doc.annset("")
    annset.add(15, 22, "Token", {"text": "faktury", "lemma": "faktura", "dep_head": 3})
    annset.add(15, 22, "Lookup", {"entity": "faktura", "source": "dfp-entity-aliases"})
    annset.add(
        15,
        45,
        "QueryPattern",
        {"query": "faktury_zakaznika", "nazev_zakaznika": "Microsoft"},
    )
    return doc


class CannedServicer(nlp_pb2_grpc.NlpServiceServicer):
    """Answers the contract, records what it was asked."""

    def __init__(
        self, *, fail_with: grpc.StatusCode | None = None, applied: bool = True
    ):
        self.requests: list = []
        self._fail_with = fail_with
        self._applied = applied

    async def RunPipeline(self, request, context):  # noqa: N802
        self.requests.append(request)
        if self._fail_with is not None:
            await context.abort(self._fail_with, "canned failure: unknown pipeline 'x'")
        return nlp_pb2.RunPipelineResponse(
            document=doc_to_proto(
                a_document(),
                include_sets=list(request.include_sets),
                include_types=list(request.include_types),
            ),
            language="cs",
            language_confidence=0.98,
            used=[
                nlp_pb2.EngineVersion(
                    op="LEMMATIZE",
                    engine="morphodita",
                    model="czech-morfflex2.0-pdtc1.0-220710",
                    model_version="220710",
                )
            ],
            phases=[
                nlp_pb2.PhaseTrace(
                    phase="LEMMATIZE", kind="engine", annotations_added=6, elapsed_ms=11
                ),
                nlp_pb2.PhaseTrace(
                    phase="query-match", kind="rules", annotations_added=1, elapsed_ms=2
                ),
            ],
            messages=[
                common_pb2.ResponseMessage(
                    severity=common_pb2.WARNING,
                    code="NLS-NLP-011",
                    human_message="op not routed in the active lane: cs/NER",
                )
            ],
            trace_id="abc123",
            elapsed_ms=17,
        )

    async def ReloadPacks(self, request, context):  # noqa: N802
        self.requests.append(request)
        if self._applied:
            return nlp_pb2.ReloadPacksResponse(
                applied=True, state_id="0123456789abcdef"
            )
        return nlp_pb2.ReloadPacksResponse(
            applied=False,
            state_id="stillserving00000",
            diagnostics=[
                nlp_pb2.PackDiagnostic(
                    source="/etc/nlp/packs/x.pack.yaml",
                    pack="x",
                    severity="ERROR",
                    code="NLS-PACK-001",
                    message="$.phases[0].control: not a control style",
                )
            ],
        )

    async def Analyze(self, request, context):  # noqa: N802
        self.requests.append(request)
        return nlp_pb2.AnalyzeResponse(
            language="cs",
            detected_language="cs",
            tokens=[nlp_pb2.Token(text="faktury", char_start=15, char_end=22)],
        )

    async def BatchLemmatize(self, request, context):  # noqa: N802
        self.requests.append(request)
        return nlp_pb2.BatchLemmatizeResponse(
            results=[nlp_pb2.LemmaList(lemmas=[f"lemma-of-{t}"]) for t in request.texts]
        )

    async def GetStatus(self, request, context):  # noqa: N802
        self.requests.append(request)
        return nlp_pb2.StatusResponse(
            ready=True,
            lane="option",
            pack_state=nlp_pb2.PackState(state_id="0123456789abcdef", packs_loaded=2),
        )


async def serve(servicer: CannedServicer) -> tuple[grpc.aio.Server, int]:
    server = grpc.aio.server()
    nlp_pb2_grpc.add_NlpServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("localhost:0")
    await server.start()
    return server, port


@pytest.fixture
async def canned():
    """A running server and a connected client, torn down together."""
    servicer = CannedServicer()
    server, port = await serve(servicer)
    client = NlpClient(f"localhost:{port}")
    try:
        yield client, servicer
    finally:
        await client.close()
        await server.stop(None)


# ── run_pipeline: the value-add ──────────────────────────────────────────────


@pytest.mark.asyncio
async def test_run_pipeline_returns_a_document_not_a_wire_message(canned):
    """The reason consumers use this instead of the generated stub."""
    client, _ = canned
    result = await client.run_pipeline(TEXT, "query-patterns", language="cs")

    assert isinstance(result.document, Document)
    assert result.document.text == TEXT
    patterns = list(result.document.annset("").with_type("QueryPattern"))
    assert len(patterns) == 1
    assert patterns[0].features["nazev_zakaznika"] == "Microsoft"
    # And the wire encoding is gone: `dep_head` is an int again, not a double.
    token = next(a for a in result.document.annset("") if a.type == "Token")
    assert token.features["dep_head"] == 3
    assert isinstance(token.features["dep_head"], int)


@pytest.mark.asyncio
async def test_run_pipeline_sends_what_it_was_given(canned):
    client, servicer = canned
    await client.run_pipeline(
        TEXT,
        "query-patterns",
        language="cs",
        include_sets=[""],
        include_types=["QueryPattern"],
    )

    (request,) = servicer.requests
    assert (request.text, request.language, request.pipeline) == (
        TEXT,
        "cs",
        "query-patterns",
    )
    assert list(request.include_types) == ["QueryPattern"]
    assert list(request.include_sets) == [""]


@pytest.mark.asyncio
async def test_the_filters_reach_the_document(canned):
    client, _ = canned
    result = await client.run_pipeline(
        TEXT, "query-patterns", include_types=["QueryPattern"]
    )
    assert {a.type for a in result.document.annset("")} == {"QueryPattern"}


@pytest.mark.asyncio
async def test_traces_and_used_come_back_as_plain_objects(canned):
    client, _ = canned
    result = await client.run_pipeline(TEXT, "query-patterns")

    assert [(p.kind, p.annotations_added) for p in result.phases] == [
        ("engine", 6),
        ("rules", 1),
    ]
    assert result.used[0].engine == "morphodita"
    assert result.used[0].model  # S-1 survives the trip
    assert (result.language, result.trace_id, result.elapsed_ms) == ("cs", "abc123", 17)
    assert result.language_confidence == pytest.approx(0.98)


@pytest.mark.asyncio
async def test_the_degrade_message_is_findable_without_a_comprehension(canned):
    """`NLS-NLP-011` is the code every caller actually looks for, so it has a
    named accessor — a caller who has to write the filter tends not to look."""
    client, _ = canned
    result = await client.run_pipeline(TEXT, "query-patterns")

    degrade = result.diagnostic("NLS-NLP-011")
    assert degrade is not None
    assert degrade.severity == "WARNING"
    assert "cs/NER" in degrade.message
    assert result.diagnostic("NLS-PACK-001") is None


# ── reload_packs ─────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_reload_packs_reports_applied():
    servicer = CannedServicer()
    server, port = await serve(servicer)
    async with NlpClient(f"localhost:{port}") as client:
        result = await client.reload_packs()
    await server.stop(None)

    assert result.applied is True
    assert result.state_id == "0123456789abcdef"
    assert result.diagnostics == []


@pytest.mark.asyncio
async def test_a_refused_reload_is_not_an_exception():
    """`applied=False` is an outcome, not an error: the previous snapshot is still
    serving and `state_id` names it. Raising would make a caller treat a healthy
    service as broken."""
    servicer = CannedServicer(applied=False)
    server, port = await serve(servicer)
    async with NlpClient(f"localhost:{port}") as client:
        result = await client.reload_packs()
    await server.stop(None)

    assert result.applied is False
    assert result.state_id == "stillserving00000"
    assert result.diagnostics[0].code == "NLS-PACK-001"
    assert "control" in result.diagnostics[0].message


# ── the pre-existing rpcs ────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_analyze_passes_ops_by_name(canned):
    """Callers name ops as strings; the enum values are the client's problem."""
    client, servicer = canned
    response = await client.analyze(TEXT, language="cs", ops=["TOKENIZE", "LEMMATIZE"])

    (request,) = servicer.requests
    assert list(request.ops) == [nlp_pb2.TOKENIZE, nlp_pb2.LEMMATIZE]
    assert response.tokens[0].text == "faktury"


@pytest.mark.asyncio
async def test_batch_lemmatize_is_positional(canned):
    client, _ = canned
    lemmas = await client.batch_lemmatize(["a", "b", "c"], language="cs")
    assert lemmas == [["lemma-of-a"], ["lemma-of-b"], ["lemma-of-c"]]


@pytest.mark.asyncio
async def test_get_status_returns_the_raw_status(canned):
    client, _ = canned
    status = await client.get_status()
    assert status.ready is True
    assert status.lane == "option"
    assert status.pack_state.packs_loaded == 2


# ── the error paths ──────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_a_server_error_surfaces_with_its_code_and_message():
    """Not swallowed and not rewrapped: the front's INVALID_ARGUMENT message lists
    the pipelines that DO exist, and that text is worth showing a user verbatim."""
    servicer = CannedServicer(fail_with=grpc.StatusCode.INVALID_ARGUMENT)
    server, port = await serve(servicer)
    async with NlpClient(f"localhost:{port}") as client:
        with pytest.raises(grpc.aio.AioRpcError) as raised:
            await client.run_pipeline(TEXT, "nope")
    await server.stop(None)

    assert raised.value.code() == grpc.StatusCode.INVALID_ARGUMENT
    assert "unknown pipeline" in raised.value.details()


@pytest.mark.asyncio
async def test_a_connection_refused_fails_rather_than_hangs():
    """Port 1 has nothing on it. A client with no deadline would sit here."""
    async with NlpClient("localhost:1", timeout_s=2.0) as client:
        with pytest.raises(grpc.aio.AioRpcError) as raised:
            await client.get_status()

    assert raised.value.code() in (
        grpc.StatusCode.UNAVAILABLE,
        grpc.StatusCode.DEADLINE_EXCEEDED,
    )


@pytest.mark.asyncio
async def test_the_deadline_is_applied_and_overridable():
    """A caller that forgets a deadline gets a hang, so the client always sets
    one. Per-call override exists because a batch job and an interactive request
    want very different numbers."""
    async with NlpClient("localhost:1", timeout_s=0.05) as client:
        with pytest.raises(grpc.aio.AioRpcError) as raised:
            await client.run_pipeline(TEXT, "p", timeout_s=0.05)
    assert raised.value.code() in (
        grpc.StatusCode.UNAVAILABLE,
        grpc.StatusCode.DEADLINE_EXCEEDED,
    )


@pytest.mark.asyncio
async def test_the_client_closes_its_channel(canned):
    client, _ = canned
    await client.close()
    # Closing twice must not raise — a context manager plus an explicit close is
    # an ordinary way to write this by accident.
    await client.close()
