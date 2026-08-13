# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.1 T1/T3/T5 — the cs morph pipeline, over the wire.

Driven over a real `grpc.aio` channel like `test_run_pipeline.py`, and reusing
that file's config and canned engines **by import rather than by copy**. That is
deliberate: T2's requirement is that the lane matrix is untouched by the morph
swap, and the only way to assert it is for both files to run the same routing
table. A copied one would drift, and the drift would look like a passing test.

What this file is really about:

*The hero still answers, and it answers from the lexicon.* Both lanes reach the
same `QueryPattern` — the option lane through NameTag's `ORGANIZATION`, the
default lane through the world overlay's `PROPN`. The second path is the one
worth staring at: with no cs NER engine anywhere, a Czech deployment reaches the
customer's name because a world put *Microsoft* in its own entity layer (LM-10),
and the token that carries it says `morph_provenance: lexicon`.

*One substrate.* The morph tokenizer owns the `Token` layer, and an engine op
that runs beside it contributes its sentences and entities and none of its
tokens. Two `Token` layers at two sets of offsets is the failure that makes a
rule fire on a laptop and not in production (LM-9).

*Engine-free means engine-free.* A morph pipeline with no leftover engine ops
calls no backend at all — asserted by making every canned engine raise.
"""

from __future__ import annotations

import json

import grpc
import pytest
from org.tatrman.nlp.v1 import nlp_pb2, nlp_pb2_grpc
from ttrnlp.doc.serialize import doc_from_proto

from nlp_service.api.grpc_server import NlpServicer
from nlp_service.config import (
    STEP_MORPH_ANNOTATE,
    STEP_MORPH_TOKENIZE,
    MorphConfig,
    MorphQueueConfig,
    MorphWorldConfig,
    PipelineConfig,
    RuleRef,
)
from nlp_service.diagnostics import NLS_NLP_011, NLS_PACK_010
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import EngineResult
from nlp_service.morph_queue import SpoolSink, build_sink
from nlp_service.morph_state import MORPH_ENGINE, MorphSnapshot
from nlp_service.packs_state import PackState
from nlp_service.pipeline.runner import KIND_ENGINE, KIND_MORPH
from tests.conftest import WORLD
from tests.pipeline.test_run_pipeline import a_config, with_canned_engines

INVOICES = "Zobraz všechny faktury od zákazníka Microsoft"
COMPARE = "Porovnej tržby Kauflandu za loňský rok s letošním"

MORPH_PIPELINE = PipelineConfig(
    morph=True,
    # `NER` stays: the lane matrix is untouched by the swap, so cs NER is still
    # NameTag in the option lane and still unrouted (NLS-NLP-011) in the default
    # one. TOKENIZE/LEMMATIZE are gone — they are the morph front's now.
    ops=["NER"],
    gazetteer=["hero-aliases"],
    rules=[
        RuleRef(pack="hero-patterns", phase="name-candidates"),
        RuleRef(pack="hero-patterns", phase="query-match"),
    ],
)

ENGINE_FREE_PIPELINE = MORPH_PIPELINE.model_copy(update={"ops": []})


def morph_config(sources, *, lane="option", pipelines=None, sink="none", spool=""):
    config = a_config(lane=lane, pipelines=pipelines or {"query-patterns": MORPH_PIPELINE})
    config.morph = MorphConfig(
        sources=list(sources),
        world=WORLD,
        queue=MorphQueueConfig(sink=sink, spool_dir=spool),
        worlds={WORLD: MorphWorldConfig()},
    )
    return config


async def boot(config, *, queue=None, explode=False, silent=False):
    """Boot a servicer over canned engines.

    `silent` swaps them for engines that answer with nothing. The shared canned
    ones return the invoices hero's tokens at the invoices hero's offsets, which
    is right for the tests about that sentence and produces out-of-range
    annotations for any other text — so a test running a different sentence
    through the engine leg asks for silence rather than for a second set of
    canned offsets nobody would keep in step.
    """
    registry = with_canned_engines(EngineRegistry(config))
    for name in registry.list_engines():
        engine = registry.get_engine(name)
        if name == "langid":
            continue
        if explode:
            engine.analyze = _never_call
        elif silent:
            engine.analyze = _nothing
    server = grpc.aio.server()
    servicer = NlpServicer(
        config,
        registry,
        PackState(config),
        morph=MorphSnapshot(config),
        queue=queue if queue is not None else build_sink(config.morph),
    )
    nlp_pb2_grpc.add_NlpServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("localhost:0")
    await server.start()
    return server, port, servicer


def _never_call(text, lang, ops):  # pragma: no cover — the assertion is that it isn't
    raise AssertionError(f"an engine was called for {ops} — this pipeline is engine-free")


def _nothing(text, lang, ops):
    return EngineResult()


async def run(port, **kwargs):
    async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
        stub = nlp_pb2_grpc.NlpServiceStub(channel)
        return await stub.RunPipeline(nlp_pb2.RunPipelineRequest(**kwargs))


async def status(port):
    async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
        stub = nlp_pb2_grpc.NlpServiceStub(channel)
        return await stub.GetStatus(nlp_pb2.StatusRequest())


async def report(port, **kwargs):
    async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
        stub = nlp_pb2_grpc.NlpServiceStub(channel)
        return await stub.ReportToken(nlp_pb2.ReportTokenRequest(**kwargs))


async def reload_packs(port):
    async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
        stub = nlp_pb2_grpc.NlpServiceStub(channel)
        return await stub.ReloadPacks(nlp_pb2.ReloadPacksRequest())


def tokens(response):
    doc = doc_from_proto(response.document)
    return sorted(doc.annset("").with_type("Token"), key=lambda a: a.start)


def by_text(response, text):
    return next(t for t in tokens(response) if t.features.get("text") == text)


def codes(response):
    return [m.code for m in response.messages]


# ── the front runs in-process ────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_the_tokens_carry_the_lexicons_answer(morph_sources):
    server, port, _ = await boot(morph_config(morph_sources))
    try:
        resp = await run(port, text=COMPARE, language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)

    token = by_text(resp, "Kauflandu")
    assert token.features["lemma"] == "Kaufland"
    assert token.features["upos"] == "PROPN"
    assert token.features["morph_provenance"] == "lexicon"
    # ⚑ On the wire the ranked records are JSON strings — §2.1's value domain
    # has no room for a list of objects, and the serializer says so out loud
    # rather than dropping them (`ttrnlp.doc.serialize`).
    assert json.loads(token.features["analyses"][0])["lemma"] == "Kaufland"


@pytest.mark.asyncio
async def test_the_traces_name_the_two_in_process_steps(morph_sources):
    server, port, _ = await boot(morph_config(morph_sources))
    try:
        resp = await run(port, text=COMPARE, language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)

    morph = [p for p in resp.phases if p.kind == KIND_MORPH]
    assert [p.phase for p in morph] == [STEP_MORPH_TOKENIZE, STEP_MORPH_ANNOTATE]
    assert morph[0].annotations_added == 8, "one Token per word of the compare hero"
    assert morph[1].annotations_added == 8, "…and every one of them looked up"


@pytest.mark.asyncio
async def test_used_names_the_snapshot_that_answered(morph_sources):
    """S-1 on the lexicon leg. The `model` is the artifact's content hash, which
    is a stronger identity claim than any backend on the estate can make: two
    runs are told apart by the bytes, not by a tag somebody moved."""
    server, port, _ = await boot(morph_config(morph_sources))
    try:
        resp = await run(port, text=COMPARE, language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)

    (lexicon,) = [ev for ev in resp.used if ev.engine == MORPH_ENGINE]
    assert lexicon.op == "LEMMATIZE"
    assert lexicon.model.startswith("sha256:")
    assert lexicon.model_version == "0.1.0"


@pytest.mark.asyncio
async def test_a_morph_pipeline_with_no_leftover_ops_calls_no_backend(morph_sources):
    server, port, _ = await boot(
        morph_config(
            morph_sources,
            lane="default",
            pipelines={"query-patterns": ENGINE_FREE_PIPELINE},
        ),
        explode=True,
    )
    try:
        resp = await run(port, text=COMPARE, language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)

    assert [p.kind for p in resp.phases if p.kind == KIND_ENGINE] == []
    assert by_text(resp, "tržby").features["lemma"] == "tržba"


# ── one substrate (LM-9) ─────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_an_engine_op_beside_morph_contributes_no_tokens(morph_sources):
    """The canned NameTag returns an entity; the canned MorphoDiTa would return
    six tokens at ITS offsets. Only the entity may land."""
    server, port, _ = await boot(morph_config(morph_sources))
    try:
        resp = await run(port, text=INVOICES, language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)

    doc = doc_from_proto(resp.document)
    assert len(tokens(resp)) == 6, "one Token per word, from our tokenizer only"
    assert {a.type for a in doc.annset("")} >= {"Token", "ORGANIZATION", "Lookup"}
    assert all(t.features.get("engine") is None for t in tokens(resp))


@pytest.mark.asyncio
async def test_the_engine_leg_is_still_traced_and_still_degrades(morph_sources):
    server, port, _ = await boot(morph_config(morph_sources, lane="default"))
    try:
        resp = await run(port, text=INVOICES, language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)

    # Default lane: NER.cs is unrouted, exactly as it was before the swap.
    assert NLS_NLP_011 in codes(resp)
    assert [p.phase for p in resp.phases if p.kind == KIND_ENGINE] == []


# ── the hero, both lanes ─────────────────────────────────────────────────────


@pytest.mark.asyncio
@pytest.mark.parametrize("lane", ["option", "default"])
async def test_both_lanes_reach_the_hero_query_pattern(morph_sources, lane):
    server, port, _ = await boot(morph_config(morph_sources, lane=lane))
    try:
        resp = await run(port, text=INVOICES, language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)

    doc = doc_from_proto(resp.document)
    (pattern,) = list(doc.annset("").with_type("QueryPattern"))
    assert pattern.features["query"] == "faktury_zakaznika"
    assert pattern.features["nazev_zakaznika"] == "Microsoft"


@pytest.mark.asyncio
async def test_the_default_lane_reaches_it_through_the_WORLD_OVERLAY(morph_sources):
    """⚑ The load-bearing one. With no cs NER anywhere, the fallback rule needs
    `upos: PROPN` on *Microsoft* — and the core lexicon does not know proper
    nouns, by design (LM-10). The world's own entity layer is what supplies it,
    which is what world overlays are *for*."""
    server, port, _ = await boot(morph_config(morph_sources, lane="default"))
    try:
        resp = await run(port, text=INVOICES, language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)

    doc = doc_from_proto(resp.document)
    assert not list(doc.annset("").with_type("ORGANIZATION")), "no NER in this lane"
    name = by_text(resp, "Microsoft")
    assert name.features["upos"] == "PROPN"
    assert name.features["morph_provenance"] == "lexicon"
    assert list(doc.annset("").with_type("NameCandidate"))


@pytest.mark.asyncio
async def test_the_gazetteer_matches_on_a_lemma_the_lexicon_produced(morph_sources):
    server, port, _ = await boot(morph_config(morph_sources))
    try:
        resp = await run(port, text=INVOICES, language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)

    doc = doc_from_proto(resp.document)
    lookups = sorted(doc.annset("").with_type("Lookup"), key=lambda a: a.start)
    assert [look.features["entity"] for look in lookups] == ["faktura", "subjekt"]
    # LM-8: the trie stepped over the token's whole candidate set, and says so.
    assert {look.features["matching"] for look in lookups} == {"morph-lemma"}


# ── the language gate ────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_a_language_the_snapshot_is_not_in_takes_the_engine_path(morph_sources):
    """A Czech snapshot in front of an English request would tokenize by Czech
    rules and report every word to the enrichment queue as a miss."""
    server, port, _ = await boot(morph_config(morph_sources), silent=True)
    try:
        resp = await run(port, text="Show me the invoices", language="en",
                         pipeline="query-patterns")
    finally:
        await server.stop(None)

    assert [p.kind for p in resp.phases if p.kind == KIND_MORPH] == []
    assert not [ev for ev in resp.used if ev.engine == MORPH_ENGINE]


# ── the queue (T3: the miss sink is wired) ───────────────────────────────────


@pytest.mark.asyncio
async def test_a_word_the_lexicon_does_not_know_reaches_the_spool(
    morph_sources, tmp_path
):
    server, port, _ = await boot(
        morph_config(
            morph_sources,
            sink=f"dir:{tmp_path}/queue",
            pipelines={"query-patterns": ENGINE_FREE_PIPELINE},
        )
    )
    try:
        await run(
            port,
            text="Porovnej kvartální tržby",
            language="cs",
            pipeline="query-patterns",
        )
    finally:
        await server.stop(None)

    spooled = (tmp_path / "queue" / f"{WORLD}.jsonl").read_text(encoding="utf-8")
    assert "kvartální" in spooled
    assert "tržby" not in spooled, "a lexicon hit is not a miss"


@pytest.mark.asyncio
async def test_the_misses_carry_this_deployments_world(morph_sources, tmp_path):
    sink = SpoolSink(tmp_path / "q", {WORLD: MorphWorldConfig()})
    server, port, _ = await boot(
        morph_config(
            morph_sources, pipelines={"query-patterns": ENGINE_FREE_PIPELINE}
        ),
        queue=sink,
    )
    try:
        await run(port, text="kvartální", language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)

    assert {r.world for r in sink.pending()} == {WORLD}


@pytest.mark.asyncio
async def test_nothing_is_spooled_when_the_sink_is_off(morph_sources, tmp_path):
    server, port, _ = await boot(
        morph_config(
            morph_sources, pipelines={"query-patterns": ENGINE_FREE_PIPELINE}
        )
    )
    try:
        await run(port, text="kvartální", language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)
    assert not (tmp_path / "queue").exists()


# ── ReportToken (T4) ─────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_a_reported_token_is_accepted_and_spooled(morph_sources, tmp_path):
    server, port, _ = await boot(
        morph_config(morph_sources, sink=f"dir:{tmp_path}/queue")
    )
    try:
        resp = await report(
            port, world=WORLD, token="Kauflandu", verdict="resolved_wrong"
        )
    finally:
        await server.stop(None)

    assert resp.accepted is True
    spooled = (tmp_path / "queue" / f"{WORLD}.jsonl").read_text(encoding="utf-8")
    assert '"verdict": "resolved_wrong"' in spooled


@pytest.mark.asyncio
async def test_an_unknown_world_is_refused_not_spooled_elsewhere(
    morph_sources, tmp_path
):
    server, port, _ = await boot(
        morph_config(morph_sources, sink=f"dir:{tmp_path}/queue")
    )
    try:
        resp = await report(port, world="somebody-else", token="x")
    finally:
        await server.stop(None)

    assert resp.accepted is False
    assert not list((tmp_path / "queue").glob("*.jsonl"))


@pytest.mark.asyncio
async def test_a_disabled_sink_answers_false_rather_than_UNIMPLEMENTED(morph_sources):
    server, port, _ = await boot(morph_config(morph_sources))
    try:
        resp = await report(port, world=WORLD, token="Kauflandu")
    finally:
        await server.stop(None)
    assert resp.accepted is False


@pytest.mark.asyncio
async def test_a_span_from_a_token_only_world_never_reaches_the_disk(
    morph_sources, tmp_path
):
    """S-4, proven on the bytes."""
    server, port, _ = await boot(
        morph_config(morph_sources, sink=f"dir:{tmp_path}/queue")
    )
    try:
        resp = await report(
            port,
            world=WORLD,
            token="Kauflandu",
            context_span="Porovnej tržby Kauflandu za loňský rok",
        )
    finally:
        await server.stop(None)

    assert resp.accepted is True
    raw = (tmp_path / "queue" / f"{WORLD}.jsonl").read_text(encoding="utf-8")
    assert "context_span" not in raw
    assert "Porovnej" not in raw


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("kwargs", "detail"),
    [
        ({"token": "x"}, "required"),
        ({"world": WORLD}, "required"),
        ({"world": WORLD, "token": "x", "verdict": "dunno"}, "not one of"),
    ],
)
async def test_a_malformed_report_is_INVALID_ARGUMENT(morph_sources, kwargs, detail):
    """A caller that sent nonsense is told so, rather than handed a `false` it
    would read as "the sink is off"."""
    server, port, _ = await boot(morph_config(morph_sources))
    try:
        with pytest.raises(grpc.aio.AioRpcError) as excinfo:
            await report(port, **kwargs)
    finally:
        await server.stop(None)
    assert excinfo.value.code() == grpc.StatusCode.INVALID_ARGUMENT
    assert detail in excinfo.value.details()


# ── GetStatus (T5) ───────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_status_carries_the_lexicon_capability_row(morph_sources):
    server, port, _ = await boot(morph_config(morph_sources))
    try:
        resp = await status(port)
    finally:
        await server.stop(None)

    rows = [c for c in resp.capabilities if c.engine == MORPH_ENGINE]
    assert len(rows) == 1
    (row,) = rows
    assert row.language == "cs"
    assert nlp_pb2.NlpOp.Name(row.op) == "LEMMATIZE"
    assert row.model_version == "0.1.0"
    assert row.tier == nlp_pb2.SELF_HOSTED_PINNED
    # Wave C's tier, not v1's: the literal `lexicon+statistical` appears only
    # when a statistical backend is actually routed.
    assert not [c for c in resp.capabilities if c.engine == "lexicon+statistical"]


@pytest.mark.asyncio
async def test_status_says_which_snapshot_is_serving(morph_sources):
    server, port, _ = await boot(morph_config(morph_sources))
    try:
        resp = await status(port)
    finally:
        await server.stop(None)

    morph = resp.morph_state
    assert morph.version == "0.1.0"
    assert morph.language == "cs"
    assert morph.rows > 0 and morph.forms > 0
    assert list(morph.worlds) == [WORLD]
    assert morph.content_hash.startswith("sha256:")
    assert not morph.diagnostics


@pytest.mark.asyncio
async def test_status_names_the_two_in_process_steps(morph_sources):
    server, port, _ = await boot(morph_config(morph_sources))
    try:
        resp = await status(port)
    finally:
        await server.stop(None)

    (pipeline,) = resp.pipelines
    assert list(pipeline.steps)[:2] == [STEP_MORPH_TOKENIZE, STEP_MORPH_ANNOTATE]


@pytest.mark.asyncio
async def test_a_front_with_no_morph_configured_reports_an_empty_block():
    config = a_config(pipelines={"engine-only": PipelineConfig(ops=["TOKENIZE"])})
    server, port, _ = await boot(config)
    try:
        resp = await status(port)
    finally:
        await server.stop(None)

    assert resp.ready is True
    assert resp.morph_state.version == ""
    assert not resp.morph_state.diagnostics


# ── the unreadable snapshot: NOT_READY, LM-MORPH-001, and still serving ──────


@pytest.mark.asyncio
async def test_an_unreadable_source_is_NOT_READY_with_LM_MORPH_001(tmp_path):
    server, port, _ = await boot(morph_config([str(tmp_path / "never-mounted.snap")]))
    try:
        resp = await status(port)
    finally:
        await server.stop(None)

    assert resp.ready is False
    assert [d.code for d in resp.morph_state.diagnostics] == ["LM-MORPH-001"]
    assert not resp.pack_state.diagnostics, "the pack half is fine and says so"


@pytest.mark.asyncio
async def test_a_morph_pipeline_with_no_snapshot_answers_FAILED_PRECONDITION(tmp_path):
    server, port, _ = await boot(morph_config([str(tmp_path / "never-mounted.snap")]))
    try:
        with pytest.raises(grpc.aio.AioRpcError) as excinfo:
            await run(port, text=COMPARE, language="cs", pipeline="query-patterns")
    finally:
        await server.stop(None)

    assert excinfo.value.code() == grpc.StatusCode.FAILED_PRECONDITION
    assert "morph_state" in excinfo.value.details()


@pytest.mark.asyncio
async def test_analyze_keeps_working_without_a_lexicon(tmp_path):
    """The whole reason a failed morph load is not fatal: Themis, Echo and
    kantheon call `Analyze`, and it needs no lexicon at all."""
    server, port, _ = await boot(morph_config([str(tmp_path / "never-mounted.snap")]))
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            resp = await stub.Analyze(
                nlp_pb2.AnalyzeRequest(
                    text=INVOICES, language="cs", ops=[nlp_pb2.TOKENIZE]
                )
            )
    finally:
        await server.stop(None)
    assert resp.tokens


# ── reload (T5): both snapshots, or neither ──────────────────────────────────


@pytest.mark.asyncio
async def test_a_reload_picks_up_a_snapshot_that_finally_mounted(
    tmp_path, morph_core
):
    """The mount case coming good. This is why an unreadable source is a
    diagnostic and not a boot error."""
    late = tmp_path / "cs.morph.snap"
    config = morph_config([str(late)])
    server, port, servicer = await boot(config)
    try:
        assert servicer.morph.state is None
        late.write_text(morph_core.read_text(encoding="utf-8"), encoding="utf-8")

        resp = await reload_packs(port)
        assert resp.applied is True
        after = await status(port)
    finally:
        await server.stop(None)

    assert after.ready is True
    assert after.morph_state.version == "0.1.0"


@pytest.mark.asyncio
async def test_a_broken_morph_source_refuses_the_reload_and_keeps_serving(
    tmp_path, morph_core, morph_overlay
):
    """⚑ The half-apply this file's `reload.py` exists to prevent: the packs are
    fine, the lexicon is not, and a front serving a new pack tree against an old
    lexicon is a combination nobody authored and nobody can name from the
    response."""
    snapshot = tmp_path / "cs.morph.snap"
    snapshot.write_text(morph_core.read_text(encoding="utf-8"), encoding="utf-8")
    server, port, servicer = await boot(morph_config([str(snapshot)]))
    try:
        before = servicer.morph.state.stats().content_hash
        snapshot.write_text("not a snapshot at all\n", encoding="utf-8")

        resp = await reload_packs(port)
        after = await status(port)
    finally:
        await server.stop(None)

    assert resp.applied is False
    assert [d.code for d in resp.diagnostics][0] == NLS_PACK_010
    assert "LM-MORPH-001" in [d.code for d in resp.diagnostics]
    # …and the old one is still answering, which is the half that protects a
    # cluster rather than the half that reports.
    assert servicer.morph.state.stats().content_hash == before
    assert after.morph_state.content_hash == before
    assert after.ready is True


@pytest.mark.asyncio
async def test_a_broken_pack_tree_does_not_swap_the_lexicon_either(
    tmp_path, morph_sources
):
    config = morph_config(morph_sources)
    config.packs.sources = [str(tmp_path / "packs")]
    (tmp_path / "packs").mkdir()
    (tmp_path / "packs" / "broken.pack.yaml").write_text("pack: [\n", encoding="utf-8")

    server, port, servicer = await boot(config)
    try:
        resp = await reload_packs(port)
    finally:
        await server.stop(None)

    assert resp.applied is False
    assert servicer.morph.state is not None
