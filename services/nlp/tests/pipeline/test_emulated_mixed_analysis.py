# SPDX-License-Identifier: Apache-2.0
"""RV-P8.1 T5 — the mixed analysis, driven through the real front dispatch.

RV-40's claim is that backends mix *within one analysis*: MorphoDiTa lemmatises
while the emulated engine recognises entities, and the caller gets one response.
This drives that through the actual `Orchestrator` and `EngineRegistry` — not a
hand-assembled merge — because the interesting part is the part neither engine
can see: the orchestrator reconciles engines on the character span, and an
emulated engine whose offsets disagree with the tokeniser's would produce a
response that looks fine and carries entities pointing at the wrong words.

⚑ **Tier deviation, recorded.** The task list says "component pass". In this repo
`@pytest.mark.component` means a real-dependency tier that CI deselects (the nlp
job runs `-m 'not component'`), and the gateway here is stubbed — there is no
real dependency to be had. A test that never runs is not a pass, so this sits in
the unit tier where it gates every PR. The routing table is bound directly, as
the list allows; `p8-2` is where a config expresses it.
"""

from __future__ import annotations

import json

from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
    LlmEmulatedConfig,
    load_config,
)
from nlp_service.diagnostics import RG_NLP_010
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import EngineResult, NlpOp, Token
from nlp_service.engines.llm_emulated_engine import (
    EMULATED_ENGINE_NAME,
    LlmEmulatedEngine,
)
from nlp_service.pipeline.orchestrator import Orchestrator

TEXT = "Zobraz faktury zákazníka Microsoft"

#: What MorphoDiTa returns for TEXT — the spans the emulated engine must agree
#: with, since the merge joins on exactly these.
MORPH_TOKENS = [
    Token(text="Zobraz", char_start=0, char_end=6, lemma="zobrazit", upos="VERB"),
    Token(text="faktury", char_start=7, char_end=14, lemma="faktura", upos="NOUN"),
    Token(text="zákazníka", char_start=15, char_end=24, lemma="zákazník", upos="NOUN"),
    Token(text="Microsoft", char_start=25, char_end=34, lemma="Microsoft", upos="PROPN"),
]

NER_REPLY = json.dumps({"entities": [{"text": "Microsoft", "label": "ORGANIZATION"}]})


class FakeGateway:
    def __init__(self, reply: str):
        self.reply = reply
        self.calls = 0

    def chat(self, *, system: str, user: str, purpose: str = "") -> str:
        self.calls += 1
        return self.reply


def a_config(*, emulation: bool) -> AppConfig:
    """MorphoDiTa for morphology, the emulated engine for cs NER.

    Written as a single lane with both engines in the base table: the lane
    machinery is `p8-2`'s subject, and binding it here would test two things at
    once.
    """
    return AppConfig(
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
                enabled=emulation,
                url="http://llm-gateway:8080",
                model="claude-haiku-4-5",
            ),
        ),
        op_routing={
            "TOKENIZE.cs": "morphodita",
            "SENTENCE_SPLIT.cs": "morphodita",
            "LEMMATIZE.cs": "morphodita",
            "POS_TAG.cs": "morphodita",
            "NER.cs": EMULATED_ENGINE_NAME,
        },
    )


def a_registry(*, emulation: bool, reply: str = NER_REPLY) -> tuple[EngineRegistry, FakeGateway]:
    registry = EngineRegistry(a_config(emulation=emulation))
    gateway = FakeGateway(reply)

    def morphodita_analyze(text, lang, ops):
        return EngineResult(tokens=list(MORPH_TOKENS), sentences=[(0, len(TEXT))])

    registry.get_engine("morphodita").analyze = morphodita_analyze  # type: ignore[method-assign]
    if emulation:
        registry._engines[EMULATED_ENGINE_NAME] = LlmEmulatedEngine(
            registry._config.engines.llm_emulated, client=gateway
        )
    return registry, gateway


class TestTheMixedAnalysis:
    def test_lemmas_and_emulated_entities_come_back_as_one_response(self):
        registry, gateway = a_registry(emulation=True)
        response = Orchestrator(registry._config, registry).analyze(
            TEXT, "cs", {NlpOp.TOKENIZE, NlpOp.LEMMATIZE, NlpOp.NER}
        )

        assert [t.lemma for t in response.tokens] == [
            "zobrazit", "faktura", "zákazník", "Microsoft",
        ]
        assert [(e.text, e.label) for e in response.entities] == [("Microsoft", "ORGANIZATION")]
        assert gateway.calls == 1

    def test_the_entity_lands_on_the_same_span_the_tokeniser_produced(self):
        """The merge reconciles on `(char_start, char_end)`, and it does so
        silently — an emulated engine that had counted characters itself, or
        normalised the surface form, would produce an entity at a span no token
        occupies and nothing would say so."""
        registry, _ = a_registry(emulation=True)
        response = Orchestrator(registry._config, registry).analyze(
            TEXT, "cs", {NlpOp.TOKENIZE, NlpOp.NER}
        )
        entity = response.entities[0]
        spans = {(t.char_start, t.char_end) for t in response.tokens}
        assert (entity.char_start, entity.char_end) in spans

    def test_the_s1_echo_names_a_different_engine_per_op(self):
        """The per-op echo RV-40 promised, end to end: one response, two
        engines, and a caller can tell which op each served."""
        registry, _ = a_registry(emulation=True)
        response = Orchestrator(registry._config, registry).analyze(
            TEXT, "cs", {NlpOp.LEMMATIZE, NlpOp.NER}
        )
        by_op = {u.op: u for u in response.used}
        assert by_op["LEMMATIZE"].engine == "morphodita"
        assert by_op["NER"].engine == EMULATED_ENGINE_NAME
        assert by_op["NER"].model_version.endswith("/tpl-1")


class TestTheSameConfigWithEmulationOff:
    def test_ner_degrades_instead_of_falling_through(self):
        """P8.1 T2(c)'s posture at the front's level: with the engine disabled
        the op is unrouted, the response says so by name, and the rest of the
        analysis still runs (NL-14)."""
        registry, gateway = a_registry(emulation=False)
        response = Orchestrator(registry._config, registry).analyze(
            TEXT, "cs", {NlpOp.TOKENIZE, NlpOp.LEMMATIZE, NlpOp.NER}
        )

        assert response.entities == []
        assert gateway.calls == 0
        assert any(m["code"] == RG_NLP_010 for m in response.messages)
        # The other phases ran — a missing engine costs its own op, not the turn.
        assert [t.lemma for t in response.tokens] == [
            "zobrazit", "faktura", "zákazník", "Microsoft",
        ]

    def test_a_gateway_outage_looks_the_same_from_here(self):
        """The claim T2(c) makes, stated where a caller would notice it: an
        engine that is present but cannot reach its gateway leaves the same
        response shape as one that was never registered."""
        registry, _ = a_registry(emulation=True, reply="not json at all")
        response = Orchestrator(registry._config, registry).analyze(
            TEXT, "cs", {NlpOp.TOKENIZE, NlpOp.LEMMATIZE, NlpOp.NER}
        )
        assert response.entities == []
        assert [t.lemma for t in response.tokens] == [
            "zobrazit", "faktura", "zákazník", "Microsoft",
        ]


class TestBootPosture:
    def test_the_shipped_config_boots_with_emulation_off(self):
        """`enabled: false` in the file AND absent from every routing table —
        the two halves of "nothing routes to it by default"."""
        config = load_config()
        assert config.engines.llm_emulated.enabled is False
        assert EMULATED_ENGINE_NAME not in config.resolved_op_routing().values()
        for overlay in config.lane_overrides.values():
            assert EMULATED_ENGINE_NAME not in overlay.values()

    def test_a_default_boot_registers_no_emulated_engine(self):
        assert EMULATED_ENGINE_NAME not in EngineRegistry(load_config()).list_engines()
