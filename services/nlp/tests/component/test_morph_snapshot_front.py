# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.1 T6 — the front against the REAL `morph/v*` snapshot (component tier).

The unit suite runs against a fourteen-entry fixture lexicon, deliberately: a
service test whose assertions depend on which words a corpus importer happened
to cover this month is a service test that fails for reasons that have nothing
to do with the service. This is the other half — the same two hero sentences,
the same rpcs, against the artifact a release would actually ship: 25k rows,
compiled by `just morph-compile` from `lexicon/cs/LAYERS`.

**What only this tier can catch.** That the compiled artifact loads through the
*runtime* loader rather than only through the compiler that wrote it; that the
hero's words are in it (coverage is a number until a sentence needs a word);
that a real snapshot's ranked answers still put the right lemma at the head of
the list where the fixture's one-entry-per-word lexicon cannot be wrong; and
that the miss queue fills with words that are genuinely absent rather than with
everything.

`@pytest.mark.component` — skipped by default, run with
`just test-py services/nlp -m component`. The artifact is a build output, not a
checked-in file, so a missing one SKIPS with the command that makes it. A
component tier that silently passed because its subject was absent is worse than
one that does not run.
"""

from __future__ import annotations

import json
from pathlib import Path

import grpc
import pytest

from org.tatrman.nlp.v1 import nlp_pb2, nlp_pb2_grpc
from ttrnlp.doc.serialize import doc_from_proto

from nlp_service.api.grpc_server import NlpServicer
from nlp_service.config import MorphConfig, MorphQueueConfig, MorphWorldConfig
from nlp_service.engines import EngineRegistry
from nlp_service.morph_queue import build_sink
from nlp_service.morph_state import MORPH_ENGINE, MorphSnapshot
from nlp_service.packs_state import PackState

from ..conftest import WORLD
from ..pipeline.test_morph_pipeline import (
    COMPARE,
    ENGINE_FREE_PIPELINE,
    INVOICES,
)
from ..pipeline.test_run_pipeline import a_config, with_canned_engines

pytestmark = pytest.mark.component

#: Where `just morph-compile` writes the artifact — the REPO ROOT's `dist/`,
#: not the module's (justfile `morph-compile`: `$root/dist/morph`). Both exist
#: in this tree and only one of them is the build output.
DIST = Path(__file__).resolve().parents[4] / "dist" / "morph"


def real_sources() -> list[str]:
    snapshot = DIST / "cs.morph.snap"
    if not snapshot.exists():
        pytest.skip(
            f"no compiled snapshot at {snapshot} — run `just morph-compile` "
            "(and `just morph-verify`) first"
        )
    # The separable share-alike member files ride with it (C-F3); the loader
    # takes the leading run of snapshot-magic sources as one core.
    return [str(snapshot), *sorted(str(p) for p in DIST.glob("*.morph.part"))]


@pytest.fixture
async def front(tmp_path, morph_overlay):
    """The real servicer, the real snapshot, the fixture world overlay.

    The overlay is the fixture one on purpose: proper nouns are a *world's* data
    (LM-10) and the published core artifact does not carry `Microsoft` — which
    is exactly the arrangement a deployment has, and exactly what makes the
    default lane's fallback rule reachable.
    """
    config = a_config(
        lane="default", pipelines={"query-patterns": ENGINE_FREE_PIPELINE}
    )
    config.morph = MorphConfig(
        sources=[*real_sources(), str(morph_overlay)],
        world=WORLD,
        queue=MorphQueueConfig(sink=f"dir:{tmp_path}/queue"),
        worlds={WORLD: MorphWorldConfig()},
    )

    server = grpc.aio.server()
    servicer = NlpServicer(
        config,
        with_canned_engines(EngineRegistry(config)),
        PackState(config),
        morph=MorphSnapshot(config),
        queue=build_sink(config.morph),
    )
    nlp_pb2_grpc.add_NlpServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("localhost:0")
    await server.start()
    try:
        yield port, tmp_path / "queue"
    finally:
        await server.stop(None)


async def run(port, text):
    async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
        stub = nlp_pb2_grpc.NlpServiceStub(channel)
        return await stub.RunPipeline(
            nlp_pb2.RunPipelineRequest(
                text=text, language="cs", pipeline="query-patterns"
            )
        )


def token(response, text):
    doc = doc_from_proto(response.document)
    return next(
        t
        for t in doc.annset("").with_type("Token")
        if t.features.get("text") == text
    )


@pytest.mark.asyncio
async def test_the_invoices_hero_answers_from_the_published_lexicon(front):
    port, _ = front
    resp = await run(port, INVOICES)

    doc = doc_from_proto(resp.document)
    (pattern,) = list(doc.annset("").with_type("QueryPattern"))
    assert pattern.features["query"] == "faktury_zakaznika"
    assert pattern.features["nazev_zakaznika"] == "Microsoft"


@pytest.mark.asyncio
async def test_the_compare_hero_resolves_every_word_it_should(front):
    """⛑ The check that caught `zobrazit` at NLS-P8.4: a lemma held as a single
    form is "covered" by every count and unusable in a sentence."""
    port, _ = front
    resp = await run(port, COMPARE)

    assert token(resp, "Porovnej").features["lemma"] == "porovnat"
    assert token(resp, "tržby").features["lemma"] == "tržba"
    assert token(resp, "loňský").features["lemma"] == "loňský"
    assert token(resp, "letošním").features["lemma"] == "letošní"


@pytest.mark.asyncio
async def test_the_provenance_features_are_on_the_wire(front):
    port, _ = front
    resp = await run(port, COMPARE)

    tržby = token(resp, "tržby")
    assert tržby.features["morph_provenance"] == "lexicon"
    assert tržby.features["upos"] == "NOUN"
    assert json.loads(tržby.features["analyses"][0])["provenance"] == "lexicon"

    # The world overlay's own row, from a world layer rather than the core.
    assert token(resp, "Kauflandu").features["lemma"] == "Kaufland"


@pytest.mark.asyncio
async def test_a_word_the_published_lexicon_lacks_reaches_the_spool(front):
    port, queue = front
    await run(port, "Porovnej kvartální tržby s pololetními")

    rows = [
        json.loads(line)
        for line in (queue / f"{WORLD}.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    spooled = {row["token"] for row in rows}
    assert "kvartální" in spooled, "the p8.4 eval's own missing target"
    assert "tržby" not in spooled, "a lexicon hit is not a miss"
    assert all(row["world"] == WORLD for row in rows)


@pytest.mark.asyncio
async def test_status_names_the_artifact_that_is_serving(front):
    port, _ = front
    async with grpc.aio.insecure_channel(f"localhost:{port}") as channel:
        stub = nlp_pb2_grpc.NlpServiceStub(channel)
        resp = await stub.GetStatus(nlp_pb2.StatusRequest())

    assert resp.ready is True
    assert resp.morph_state.language == "cs"
    assert resp.morph_state.rows > 1000, "this is the published artifact, not a fixture"
    assert list(resp.morph_state.worlds) == [WORLD]

    (row,) = [c for c in resp.capabilities if c.engine == MORPH_ENGINE]
    assert nlp_pb2.NlpOp.Name(row.op) == "LEMMATIZE"
    assert row.model_version == resp.morph_state.version
