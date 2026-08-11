# SPDX-License-Identifier: Apache-2.0
"""NLS-P3.2.T1 — RunPipeline, ReloadPacks, lanes and the NL-14 degrade.

Driven over a real `grpc.aio` channel with canned engines, following
`test_grpc_service.py`: the status codes and the response shapes are the contract
three other services read, and a servicer called directly would not exercise the
`context.abort` paths at all.

**The lane matrix is the centre of the file.** Both lanes must reach the same
`QueryPattern` by *different routes* — that is what makes the degrade a degrade
rather than a failure. Asserting only the output would pass even if the fallback
rule were firing in both lanes, which would mean the NER path was dead and nobody
noticed; asserting only the message would pass if the answer had been lost. So
both are asserted, in both lanes.

**Reload is asserted for what does not change.** A refused reload must leave the
`state_id` identical and the previous packs serving. "It returned applied=false"
is the easy half; "and the old snapshot is still answering" is the half that
actually protects a cluster.
"""

from __future__ import annotations

import shutil
from pathlib import Path

import grpc
import pytest

from org.tatrman.nlp.v1 import nlp_pb2, nlp_pb2_grpc
from ttrnlp.doc.serialize import doc_from_proto

from nlp_service.api.grpc_server import NlpServicer
from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
    PipelineConfig,
    RuleRef,
    SourcesConfig,
)
from nlp_service.diagnostics import NLS_NLP_011, NLS_PACK_010
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import EngineResult, NerEntity, NlpOp, Token
from nlp_service.packs_state import PackState

FIXTURES = Path(__file__).parent.parent / "fixtures"
PACKS = FIXTURES / "packs"
LISTS = FIXTURES / "lists"
BROKEN = FIXTURES / "packs-broken"

TEXT = "Zobraz všechny faktury od zákazníka Microsoft"

#: The tokens MorphoDiTa/Stanza would return for TEXT — lemmas included, because
#: lemma-mode gazetteer matching is the whole reason the Czech hero works.
HERO_TOKENS = [
    Token(text="Zobraz", char_start=0, char_end=6, lemma="zobrazit", upos="VERB"),
    Token(text="všechny", char_start=7, char_end=14, lemma="všechen", upos="DET"),
    Token(text="faktury", char_start=15, char_end=22, lemma="faktura", upos="NOUN"),
    Token(text="od", char_start=23, char_end=25, lemma="od", upos="ADP"),
    Token(text="zákazníka", char_start=26, char_end=35, lemma="zákazník", upos="NOUN"),
    Token(text="Microsoft", char_start=36, char_end=45, lemma="Microsoft", upos="PROPN"),
]

HERO_ENTITY = NerEntity(
    text="Microsoft",
    label="ORGANIZATION",
    char_start=36,
    char_end=45,
    normalized_value="cnec:if",
    source_engine="nametag3",
)

PIPELINE = PipelineConfig(
    ops=["TOKENIZE", "SENTENCE_SPLIT", "LEMMATIZE", "NER"],
    gazetteer=["hero-aliases"],
    rules=[
        RuleRef(pack="hero-patterns", phase="name-candidates"),
        RuleRef(pack="hero-patterns", phase="query-match"),
    ],
)


def a_config(
    *,
    lane: str = "option",
    packs: list[str] | None = None,
    pipelines: dict | None = None,
) -> AppConfig:
    """The service config the whole file runs against.

    Both lanes are declared exactly as `config.yaml` declares them — base is the
    default lane, the UFAL routing is the `option` overlay — so the lane machinery
    under test is the shipped one, not a test-only arrangement.
    """
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
            stanza=BackendConfig(
                url="http://stanza:8090", model="stanza-cs-en", model_version="1.10.0"
            ),
            spacy=BackendConfig(
                url="http://spacy:8091", model="en_core_web_md", model_version="3.8.0"
            ),
            langid=LangidEngineConfig(model_version="lingua-2.0"),
        ),
        op_routing={
            "TOKENIZE.cs": "stanza",
            "SENTENCE_SPLIT.cs": "stanza",
            "LEMMATIZE.cs": "stanza",
            "POS_TAG.cs": "stanza",
            "DEP_PARSE.cs": "stanza",
            "TOKENIZE.en": "stanza",
            "NER.en": "stanza",
            "DETECT_LANGUAGE": "langid",
        },
        lane=lane,
        lane_overrides={
            "option": {
                "TOKENIZE.cs": "morphodita",
                "SENTENCE_SPLIT.cs": "morphodita",
                "LEMMATIZE.cs": "morphodita",
                "POS_TAG.cs": "morphodita",
                "NER.cs": "nametag3",
            }
        },
        packs=SourcesConfig(sources=packs if packs is not None else [str(PACKS)]),
        lists=SourcesConfig(sources=[str(LISTS)]),
        pipelines=pipelines if pipelines is not None else {"query-patterns": PIPELINE},
        default_language="cs",
    )


def with_canned_engines(registry: EngineRegistry) -> EngineRegistry:
    """Stub whichever engines this lane registered — no network.

    Written as "whichever", not a fixed list: in the default lane MorphoDiTa and
    NameTag 3 are not registered at all, and a test that stubbed them by name
    would fail with an AttributeError instead of exercising the degrade.
    """

    def tokens_only(text, lang, ops):
        return EngineResult(tokens=list(HERO_TOKENS), sentences=[(0, len(TEXT))])

    def ner_only(text, lang, ops):
        return EngineResult(entities=[HERO_ENTITY])

    for name in ("morphodita", "stanza"):
        engine = registry.get_engine(name)
        if engine is not None:
            engine.analyze = tokens_only
    nametag = registry.get_engine("nametag3")
    if nametag is not None:
        nametag.analyze = ner_only
    return registry


async def boot(config: AppConfig, packs: PackState | None = None):
    registry = with_canned_engines(EngineRegistry(config))
    server = grpc.aio.server()
    servicer = NlpServicer(config, registry, packs or PackState(config))
    nlp_pb2_grpc.add_NlpServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("localhost:0")
    await server.start()
    return server, port, servicer


async def run_pipeline(port: int, **kwargs):
    async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
        stub = nlp_pb2_grpc.NlpServiceStub(channel)
        return await stub.RunPipeline(nlp_pb2.RunPipelineRequest(**kwargs))


def codes(response) -> list[str]:
    return [m.code for m in response.messages]


def query_patterns(response):
    doc = doc_from_proto(response.document)
    return sorted(doc.annset("").with_type("QueryPattern"), key=lambda a: a.start)


# ── the happy path ───────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_run_pipeline_returns_every_layer_it_built():
    server, port, _ = await boot(a_config())
    try:
        resp = await run_pipeline(
            port, text=TEXT, language="cs", pipeline="query-patterns"
        )
    finally:
        await server.stop(None)

    doc = doc_from_proto(resp.document)
    types = {a.type for a in doc.annset("")}
    # Engine layer, gazetteer layer, lifted layer, rule output — the whole chain.
    assert {"Token", "Sentence", "ORGANIZATION", "Lookup", "QueryPattern"} <= types
    assert doc.features["pipeline"] == "query-patterns"
    assert doc.features["lane"] == "option"


@pytest.mark.asyncio
async def test_the_pipeline_produces_the_hero_query_pattern():
    server, port, _ = await boot(a_config())
    try:
        resp = await run_pipeline(
            port, text=TEXT, language="cs", pipeline="query-patterns"
        )
    finally:
        await server.stop(None)

    (pattern,) = query_patterns(resp)
    assert pattern.features["query"] == "faktury_zakaznika"
    # contracts §5: the parameter value is the binding's span TEXT, not a
    # normalised id.
    assert pattern.features["nazev_zakaznika"] == "Microsoft"


@pytest.mark.asyncio
async def test_used_names_a_model_for_every_op_that_ran():
    server, port, _ = await boot(a_config())
    try:
        resp = await run_pipeline(
            port, text=TEXT, language="cs", pipeline="query-patterns"
        )
    finally:
        await server.stop(None)

    assert resp.used
    # S-1 holds on this rpc too: never a blank model.
    assert all(ev.model for ev in resp.used)
    assert {ev.engine for ev in resp.used} == {"morphodita", "nametag3"}


@pytest.mark.asyncio
async def test_phase_traces_cover_all_three_kinds_in_order():
    """"The rules produced nothing" has at least four causes — no tokens, no
    lemmas, no Lookups, no match. The trace is what tells them apart without a
    second request."""
    server, port, _ = await boot(a_config())
    try:
        resp = await run_pipeline(
            port, text=TEXT, language="cs", pipeline="query-patterns"
        )
    finally:
        await server.stop(None)

    kinds = [p.kind for p in resp.phases]
    assert kinds == ["engine", "gazetteer", "rules", "rules"]
    by_phase = {p.phase: p for p in resp.phases}
    assert by_phase["hero-aliases"].annotations_added == 2
    assert by_phase["hero-patterns:query-match"].annotations_added == 1


@pytest.mark.asyncio
async def test_the_output_filters_are_honoured():
    server, port, _ = await boot(a_config())
    try:
        resp = await run_pipeline(
            port,
            text=TEXT,
            language="cs",
            pipeline="query-patterns",
            include_types=["QueryPattern"],
        )
    finally:
        await server.stop(None)

    doc = doc_from_proto(resp.document)
    assert {a.type for a in doc.annset("")} == {"QueryPattern"}


# ── the lane matrix (NL-4 / NL-14) ───────────────────────────────────────────


@pytest.mark.asyncio
async def test_the_default_lane_degrades_explicitly_and_still_answers():
    """The NL-14 case, whole. cs NER is unroutable, so: the op is skipped, the
    response says `NLS-NLP-011`, and the pack's fallback rule still reaches the
    same QueryPattern through morphology."""
    server, port, _ = await boot(a_config(lane="default"))
    try:
        resp = await run_pipeline(
            port, text=TEXT, language="cs", pipeline="query-patterns"
        )
    finally:
        await server.stop(None)

    assert NLS_NLP_011 in codes(resp)
    degrade = next(m for m in resp.messages if m.code == NLS_NLP_011)
    assert "cs/NER" in degrade.human_message

    # The answer survived.
    (pattern,) = query_patterns(resp)
    assert pattern.features["nazev_zakaznika"] == "Microsoft"
    # And it came the other way: no NER annotation exists at all.
    doc = doc_from_proto(resp.document)
    assert not list(doc.annset("").with_type("ORGANIZATION"))
    assert list(doc.annset("").with_type("NameCandidate"))


@pytest.mark.asyncio
async def test_the_option_lane_does_not_degrade():
    server, port, _ = await boot(a_config(lane="option"))
    try:
        resp = await run_pipeline(
            port, text=TEXT, language="cs", pipeline="query-patterns"
        )
    finally:
        await server.stop(None)

    assert NLS_NLP_011 not in codes(resp)
    doc = doc_from_proto(resp.document)
    assert len(list(doc.annset("").with_type("ORGANIZATION"))) == 1


@pytest.mark.asyncio
async def test_the_two_lanes_reach_the_same_answer_by_different_rules():
    """Asserting the output alone would pass even if the fallback fired in both
    lanes — which would mean the NER path was dead and nobody noticed."""
    answers = {}
    fired = {}
    for lane in ("option", "default"):
        server, port, _ = await boot(a_config(lane=lane))
        try:
            resp = await run_pipeline(
                port, text=TEXT, language="cs", pipeline="query-patterns"
            )
        finally:
            await server.stop(None)
        (pattern,) = query_patterns(resp)
        answers[lane] = pattern.features["nazev_zakaznika"]
        doc = doc_from_proto(resp.document)
        fired[lane] = bool(list(doc.annset("").with_type("ORGANIZATION")))

    assert answers["option"] == answers["default"] == "Microsoft"
    assert fired == {"option": True, "default": False}


@pytest.mark.asyncio
async def test_get_status_reports_the_lane_and_drops_the_unrouted_row():
    """contracts §2.4: `NER.cs` has no capability row in the default lane. That
    absence is half the degrade — a row naming the floor would read as a yes."""
    for lane, expect_ner_cs in (("option", True), ("default", False)):
        server, port, _ = await boot(a_config(lane=lane))
        try:
            async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
                stub = nlp_pb2_grpc.NlpServiceStub(channel)
                status = await stub.GetStatus(nlp_pb2.StatusRequest())
        finally:
            await server.stop(None)

        assert status.lane == lane
        rows = {
            (c.language, nlp_pb2.NlpOp.Name(c.op)): c.engine
            for c in status.capabilities
        }
        assert (("cs", "NER") in rows) is expect_ner_cs
        # The rest of cs is served either way — the degrade is one op, not a lane
        # that stops working.
        assert ("cs", "TOKENIZE") in rows


@pytest.mark.asyncio
async def test_analyze_carries_the_same_degrade_message():
    """T7, the NL-14 tail. Same helper, same code, shape untouched — one more
    entry on messages[99], which is what the Rule-6 slot is for."""
    server, port, _ = await boot(a_config(lane="default"))
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            resp = await stub.Analyze(
                nlp_pb2.AnalyzeRequest(
                    text=TEXT, language="cs", ops=[nlp_pb2.TOKENIZE, nlp_pb2.NER]
                )
            )
    finally:
        await server.stop(None)

    assert NLS_NLP_011 in [m.code for m in resp.messages]
    # The shape is unchanged: tokens still came back.
    assert resp.tokens


@pytest.mark.asyncio
async def test_analyze_does_not_cry_degrade_in_the_option_lane():
    server, port, _ = await boot(a_config(lane="option"))
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            resp = await stub.Analyze(
                nlp_pb2.AnalyzeRequest(
                    text=TEXT, language="cs", ops=[nlp_pb2.TOKENIZE, nlp_pb2.NER]
                )
            )
    finally:
        await server.stop(None)

    assert NLS_NLP_011 not in [m.code for m in resp.messages]


# ── the error paths ──────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_an_unknown_pipeline_is_invalid_argument_and_lists_the_real_ones():
    server, port, _ = await boot(a_config())
    try:
        with pytest.raises(grpc.aio.AioRpcError) as raised:
            await run_pipeline(port, text=TEXT, language="cs", pipeline="nope")
    finally:
        await server.stop(None)

    assert raised.value.code() == grpc.StatusCode.INVALID_ARGUMENT
    assert "query-patterns" in raised.value.details()


@pytest.mark.asyncio
async def test_a_missing_pipeline_name_is_invalid_argument():
    server, port, _ = await boot(a_config())
    try:
        with pytest.raises(grpc.aio.AioRpcError) as raised:
            await run_pipeline(port, text=TEXT, language="cs")
    finally:
        await server.stop(None)

    assert raised.value.code() == grpc.StatusCode.INVALID_ARGUMENT


@pytest.mark.asyncio
async def test_a_broken_pack_tree_leaves_analyze_working_and_fails_run_pipeline():
    """The fail-all posture, from the outside: `ready=false`, the diagnostics on
    `GetStatus.pack_state`, `RunPipeline` refused — and `Analyze` untouched,
    because it needs no packs and it is what the deployed consumers call."""
    config = a_config(packs=[str(BROKEN)])
    server, port, _ = await boot(config)
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)

            status = await stub.GetStatus(nlp_pb2.StatusRequest())
            assert status.ready is False
            assert status.pack_state.diagnostics
            assert status.pack_state.diagnostics[0].code == "NLS-PACK-001"
            assert status.pack_state.state_id == ""

            with pytest.raises(grpc.aio.AioRpcError) as raised:
                await stub.RunPipeline(
                    nlp_pb2.RunPipelineRequest(
                        text=TEXT, language="cs", pipeline="query-patterns"
                    )
                )
            assert raised.value.code() == grpc.StatusCode.FAILED_PRECONDITION

            analyze = await stub.Analyze(
                nlp_pb2.AnalyzeRequest(
                    text=TEXT, language="cs", ops=[nlp_pb2.TOKENIZE]
                )
            )
            assert analyze.tokens
    finally:
        await server.stop(None)


# ── ReloadPacks (NL-15) ──────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_a_reload_that_finds_nothing_new_keeps_the_same_state_id():
    server, port, _ = await boot(a_config())
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            before = (await stub.GetStatus(nlp_pb2.StatusRequest())).pack_state.state_id
            resp = await stub.ReloadPacks(nlp_pb2.ReloadPacksRequest())
    finally:
        await server.stop(None)

    assert resp.applied is True
    assert resp.state_id == before  # same bytes ⇒ same id
    assert not resp.diagnostics


@pytest.mark.asyncio
async def test_a_reload_over_a_broken_tree_is_refused_and_the_old_packs_keep_serving(
    tmp_path,
):
    """The half that protects a cluster. `applied=false` is easy; "and the
    previous snapshot is still answering" is the assertion that matters."""
    mutable = tmp_path / "packs"
    shutil.copytree(PACKS, mutable)
    config = a_config(packs=[str(mutable)])

    server, port, _ = await boot(config)
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            before = (await stub.GetStatus(nlp_pb2.StatusRequest())).pack_state.state_id

            # Break the mounted pack the way a bad push would.
            pack = mutable / "hero-patterns.pack.yaml"
            pack.write_text(
                pack.read_text(encoding="utf-8").replace(
                    "control: appelt", "control: appelts"
                ),
                encoding="utf-8",
            )

            refused = await stub.ReloadPacks(nlp_pb2.ReloadPacksRequest())
            assert refused.applied is False
            assert refused.state_id == before
            assert refused.diagnostics[0].code == NLS_PACK_010
            assert any(d.code == "NLS-PACK-001" for d in refused.diagnostics)

            # The old packs are still serving — the answer is unchanged.
            resp = await stub.RunPipeline(
                nlp_pb2.RunPipelineRequest(
                    text=TEXT, language="cs", pipeline="query-patterns"
                )
            )
            (pattern,) = query_patterns(resp)
            assert pattern.features["query"] == "faktury_zakaznika"

            # contracts §2.4: pack_state.diagnostics is for a failed BOOT. Boot
            # was fine, so it stays empty — the refusal went to whoever asked.
            status = await stub.GetStatus(nlp_pb2.StatusRequest())
            assert not status.pack_state.diagnostics
            assert status.pack_state.state_id == before
    finally:
        await server.stop(None)


@pytest.mark.asyncio
async def test_a_reload_after_a_real_change_applies_and_gives_a_new_state_id(tmp_path):
    mutable = tmp_path / "packs"
    shutil.copytree(PACKS, mutable)
    config = a_config(packs=[str(mutable)])

    server, port, _ = await boot(config)
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            before = (await stub.GetStatus(nlp_pb2.StatusRequest())).pack_state.state_id

            pack = mutable / "hero-patterns.pack.yaml"
            pack.write_text(
                pack.read_text(encoding="utf-8") + "\n# a real edit\n",
                encoding="utf-8",
            )

            resp = await stub.ReloadPacks(nlp_pb2.ReloadPacksRequest())
            assert resp.applied is True
            assert resp.state_id != before
            assert not resp.diagnostics
    finally:
        await server.stop(None)


@pytest.mark.asyncio
async def test_a_reload_recovers_a_service_that_booted_broken(tmp_path):
    """The other direction: boot NOT_READY, fix the mount, reload, serve. Without
    this, a bad push would mean a restart rather than a reload."""
    mutable = tmp_path / "packs"
    mutable.mkdir()
    shutil.copy(BROKEN / "broken.pack.yaml", mutable / "broken.pack.yaml")
    config = a_config(packs=[str(mutable)])

    server, port, _ = await boot(config)
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            assert (await stub.GetStatus(nlp_pb2.StatusRequest())).pack_state.diagnostics

            (mutable / "broken.pack.yaml").unlink()
            shutil.copy(PACKS / "hero-patterns.pack.yaml", mutable)

            resp = await stub.ReloadPacks(nlp_pb2.ReloadPacksRequest())
            assert resp.applied is True
            assert resp.state_id

            served = await stub.RunPipeline(
                nlp_pb2.RunPipelineRequest(
                    text=TEXT, language="cs", pipeline="query-patterns"
                )
            )
            assert query_patterns(served)
            status = await stub.GetStatus(nlp_pb2.StatusRequest())
            assert not status.pack_state.diagnostics
    finally:
        await server.stop(None)


# ── GetStatus additions, and ReportToken ─────────────────────────────────────


@pytest.mark.asyncio
async def test_get_status_lists_the_configured_pipelines_with_their_steps():
    server, port, _ = await boot(a_config())
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            status = await stub.GetStatus(nlp_pb2.StatusRequest())
    finally:
        await server.stop(None)

    (info,) = status.pipelines
    assert info.name == "query-patterns"
    assert list(info.steps) == [
        "TOKENIZE",
        "SENTENCE_SPLIT",
        "LEMMATIZE",
        "NER",
        "hero-aliases",
        "hero-patterns:name-candidates",
        "hero-patterns:query-match",
    ]


@pytest.mark.asyncio
async def test_get_status_reports_what_loaded():
    server, port, _ = await boot(a_config())
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            status = await stub.GetStatus(nlp_pb2.StatusRequest())
    finally:
        await server.stop(None)

    assert status.pack_state.packs_loaded == 1
    assert status.pack_state.lists_loaded == 1
    assert len(status.pack_state.state_id) == 16
    assert not status.pack_state.diagnostics


@pytest.mark.asyncio
async def test_report_token_answers_accepted_false_until_the_sink_lands():
    """LM's rpc, riding the P3.1 proto window. `accepted=false` rather than
    UNIMPLEMENTED: `accepted` is already the field that means "the sink is
    disabled" (cz-lemma contracts §6), so a client written against the final shape
    needs no special case now and keeps working when NLS-P9 wires it."""
    server, port, _ = await boot(a_config())
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            resp = await stub.ReportToken(
                nlp_pb2.ReportTokenRequest(
                    world="dfp", token="fakturami", verdict="miss", language="cs"
                )
            )
    finally:
        await server.stop(None)

    assert resp.accepted is False


# ── a deployment with no packs at all ────────────────────────────────────────


@pytest.mark.asyncio
async def test_no_pack_sources_is_ready_with_no_pipelines():
    """A fresh deployment. Not an error state: there is nothing to load and
    nothing to run, and `RunPipeline` says so per request rather than the service
    refusing to be ready."""
    config = a_config(packs=[], pipelines={})
    config.lists = SourcesConfig(sources=[])
    server, port, _ = await boot(config)
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            status = await stub.GetStatus(nlp_pb2.StatusRequest())
            assert not status.pack_state.diagnostics
            assert not status.pipelines

            with pytest.raises(grpc.aio.AioRpcError) as raised:
                await stub.RunPipeline(
                    nlp_pb2.RunPipelineRequest(text=TEXT, pipeline="anything")
                )
            assert raised.value.code() == grpc.StatusCode.INVALID_ARGUMENT
            assert "none configured" in raised.value.details()
    finally:
        await server.stop(None)


# ── detection decides the degrade set (NL-14 × contracts §2.2) ───────────────

#: Long enough for lingua to be sure, and English on purpose — the point of the
#: pair below is that the DETECTED language is what the degrade is computed for.
EN_TEXT = "Show me all invoices from the customer Microsoft for the last quarter"


@pytest.mark.asyncio
async def test_an_empty_language_degrades_against_the_detected_one():
    """The degrade set is language-specific, so it cannot be decided before the
    language is.

    On the default lane `NER.cs` is unrouted and `NER.en` is not. Deciding the
    set against `default_language` when the request left `language` empty meant
    an English document lost NER to a degrade computed for Czech — and said so
    in an `NLS-NLP-011` naming `cs/NER`, while the response's own `language`
    field said `en`. A rule phase keyed on NER then matched nothing, which looks
    exactly like a pack with a typo in it.
    """
    server, port, _ = await boot(a_config(lane="default"))
    try:
        resp = await run_pipeline(port, text=EN_TEXT, pipeline="query-patterns")
    finally:
        await server.stop(None)

    assert resp.language == "en"
    degraded = [m.human_message for m in resp.messages if m.code == NLS_NLP_011]
    # Nothing this pipeline asks for is unroutable in en, so there is no degrade
    # at all. Computed against `default_language` instead, this list held
    # `cs/NER` — a degrade named for a language the response does not report.
    assert degraded == [], degraded


@pytest.mark.asyncio
async def test_an_empty_language_still_degrades_when_the_detected_one_cannot_serve():
    """The other half: detection finding `cs` on the default lane must still
    produce the `cs/NER` degrade. The fix is "compute it later", not "compute it
    less"."""
    server, port, _ = await boot(a_config(lane="default"))
    try:
        resp = await run_pipeline(port, text=TEXT, pipeline="query-patterns")
    finally:
        await server.stop(None)

    assert resp.language == "cs"
    degraded = [m.human_message for m in resp.messages if m.code == NLS_NLP_011]
    assert any(m.endswith("cs/NER") for m in degraded), degraded


@pytest.mark.asyncio
async def test_detection_still_stamps_the_langid_engine_in_used():
    """S-1 survives the split into two orchestrator calls: the detection pass is
    where `DETECT_LANGUAGE`'s `used[]` entry comes from, and dropping it would
    lose the model identity for the op that decided everything downstream."""
    server, port, _ = await boot(a_config())
    try:
        resp = await run_pipeline(port, text=TEXT, pipeline="query-patterns")
    finally:
        await server.stop(None)

    detect = [ev for ev in resp.used if ev.op == "DETECT_LANGUAGE"]
    assert detect, [ev.op for ev in resp.used]
    assert detect[0].engine == "langid"
    assert detect[0].model_version == "lingua-2.0"
    assert 0.0 < resp.language_confidence <= 1.0


# ── ready is both halves (contracts §2.4) ────────────────────────────────────


@pytest.mark.asyncio
async def test_a_broken_pack_tree_reports_not_ready():
    """`ready` is what a consumer gates traffic on, and with a failed pack load
    every `RunPipeline` answers FAILED_PRECONDITION. Reporting the engine
    registry alone put a healthy front in front of a service that could not serve
    the rpc — which is not what the README, `config.yaml`, `k8s/values.yaml` or
    `packs_state.py` say happens."""
    server, port, _ = await boot(a_config(packs=[str(BROKEN)]))
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            status = await stub.GetStatus(nlp_pb2.StatusRequest())
    finally:
        await server.stop(None)

    assert status.ready is False
    assert status.pack_state.diagnostics


@pytest.mark.asyncio
async def test_a_sound_pack_tree_reports_ready():
    server, port, _ = await boot(a_config())
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
            stub = nlp_pb2_grpc.NlpServiceStub(channel)
            status = await stub.GetStatus(nlp_pb2.StatusRequest())
    finally:
        await server.stop(None)

    assert status.ready is True


# ── the unrouted-op helper, directly ─────────────────────────────────────────


def test_unrouted_ops_names_only_what_the_lane_cannot_serve():
    from nlp_service.pipeline.runner import PipelineRunner

    for lane, expected in (("option", []), ("default", [NlpOp.NER])):
        config = a_config(lane=lane)
        runner = PipelineRunner(config, EngineRegistry(config))
        unrouted = runner.unrouted_ops("cs", [NlpOp.TOKENIZE, NlpOp.LEMMATIZE, NlpOp.NER])
        assert unrouted == expected, lane
