# SPDX-License-Identifier: Apache-2.0
"""RV-P8.1 — `LLM_EMULATED`: an ordinary engine, off by default, honest when it fails.

RV-6's motivation is an estate that cannot host a real engine (the UFAL licence
is CC BY-NC-SA), not a preference. So the bar this file holds the engine to is
*indistinguishability from a real one where it succeeds*, and *loud, ordinary
failure everywhere else*:

* the caller cannot tell an emulated `Analyze` from a real one (same value types,
  same spans, same label vocabulary) — that is the whole point;
* a gateway outage degrades exactly like an absent engine, because from the
  front's side it IS one;
* and a model that answers with something we cannot parse produces a NAMED
  failure, never a half-analysis. A confidently wrong lemma is the failure mode
  this engine must never have quietly, and it is the reason nothing here accepts
  a partial parse.
"""

from __future__ import annotations

import json

import pytest

from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
    LlmEmulatedConfig,
)
from nlp_service.diagnostics import RV_NLP_020, RV_NLP_021
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import NlpOp
from nlp_service.engines.llm_emulated_engine import (
    EMULATED_ENGINE_NAME,
    TEMPLATE_VERSION,
    LlmEmulatedEngine,
)
from nlp_service.engines.llm_gateway import GatewayUnavailable

TEXT = "Zobraz faktury zákazníka Microsoft"

#: What the model is asked to return for LEMMATIZE+POS_TAG on TEXT. Note it does
#: NOT carry offsets: no LLM is asked to count characters, exactly as no UFAL
#: tool reports them — the engine locates each token in the source text itself.
LEMMA_REPLY = json.dumps(
    {
        "tokens": [
            {"text": "Zobraz", "lemma": "zobrazit", "upos": "VERB"},
            {"text": "faktury", "lemma": "faktura", "upos": "NOUN"},
            {"text": "zákazníka", "lemma": "zákazník", "upos": "NOUN"},
            {"text": "Microsoft", "lemma": "Microsoft", "upos": "PROPN"},
        ]
    }
)

NER_REPLY = json.dumps({"entities": [{"text": "Microsoft", "label": "ORGANIZATION"}]})


class FakeGateway:
    """A gateway that answers from a script and records what it was asked."""

    def __init__(self, *replies: str, raises: Exception | None = None):
        self.replies = list(replies)
        self.raises = raises
        self.calls: list[dict] = []

    def chat(self, *, system: str, user: str, purpose: str = "") -> str:
        self.calls.append({"system": system, "user": user, "purpose": purpose})
        if self.raises:
            raise self.raises
        return self.replies.pop(0) if len(self.replies) > 1 else self.replies[0]


def a_cfg(**over) -> LlmEmulatedConfig:
    base = {
        "enabled": True,
        "url": "http://llm-gateway:8080",
        "model": "claude-haiku-4-5",
        "api_key": "ttrk-test",
    }
    base.update(over)
    return LlmEmulatedConfig(**base)


def an_engine(*replies: str, raises: Exception | None = None, **over):
    gw = FakeGateway(*replies, raises=raises)
    return LlmEmulatedEngine(a_cfg(**over), client=gw), gw


# ── (a) registered, and DISABLED by default ──────────────────────────────────


class TestOffByDefault:
    def test_default_config_does_not_enable_it(self):
        assert LlmEmulatedConfig().enabled is False

    def test_disabled_engine_is_not_registered(self):
        cfg = _app_config(enabled=False)
        registry = EngineRegistry(cfg)
        assert EMULATED_ENGINE_NAME not in registry.list_engines()

    def test_disabled_means_the_op_is_unrouted_not_silently_served(self):
        """The degrade posture, stated as the front already states it.

        With emulation off and nothing routing to it, `NER.cs` must reach the
        floor and be labelled — NOT fall through to some other engine that
        happens to support NER. Falling through is the failure this asserts
        against: it would serve a Czech question from an English model and say
        nothing about it.

        (A config that *routes* an op at the disabled engine is a different
        case, and a stricter one: it refuses to load at all — `p8-2`'s
        `test_routing_to_the_emulated_engine_while_it_is_disabled_is_a_load_error`.)
        """
        cfg = _app_config(enabled=False)
        route = EngineRegistry(cfg).route("cs", NlpOp.NER)
        assert route.is_floor
        assert route.engine != EMULATED_ENGINE_NAME

    def test_enabled_engine_registers_like_any_other(self):
        registry = EngineRegistry(_app_config(enabled=True))
        assert EMULATED_ENGINE_NAME in registry.list_engines()


# ── (b) the caller cannot tell ───────────────────────────────────────────────


class TestAnalyzeIsIndistinguishable:
    def test_lemmatize_returns_the_engine_result_shape_with_real_spans(self):
        engine, _ = an_engine(LEMMA_REPLY)
        result = engine.analyze(TEXT, "cs", {NlpOp.LEMMATIZE})

        assert result.error == ""
        assert [t.text for t in result.tokens] == [
            "Zobraz", "faktury", "zákazníka", "Microsoft",
        ]
        assert [t.lemma for t in result.tokens] == [
            "zobrazit", "faktura", "zákazník", "Microsoft",
        ]
        # The spans are the reconciliation key the orchestrator merges on, so
        # they must index the ORIGINAL text — not the model's idea of it.
        for token in result.tokens:
            assert TEXT[token.char_start : token.char_end] == token.text

    def test_pos_and_lemma_ride_one_call(self):
        """Two ops, one round trip — asking twice would double the cost for a
        strictly worse joint result (the tags would not have to agree)."""
        engine, gw = an_engine(LEMMA_REPLY)
        result = engine.analyze(TEXT, "cs", {NlpOp.LEMMATIZE, NlpOp.POS_TAG})
        assert len(gw.calls) == 1
        assert [t.upos for t in result.tokens] == ["VERB", "NOUN", "NOUN", "PROPN"]

    def test_ner_spans_and_labels_match_the_engine_it_stands_in_for(self):
        engine, _ = an_engine(NER_REPLY)
        result = engine.analyze(TEXT, "cs", {NlpOp.NER})
        ent = result.entities[0]
        assert (ent.text, ent.label) == ("Microsoft", "ORGANIZATION")
        assert TEXT[ent.char_start : ent.char_end] == "Microsoft"
        assert ent.source_engine == EMULATED_ENGINE_NAME

    def test_cs_and_en_do_not_share_a_label_vocabulary(self):
        """The suite refuses to harmonise NameTag's CNEC-derived coarse labels
        with spaCy's OntoNotes ones (`ttrnlp.doc.labels`), and a pack matches
        `ORGANIZATION` in cs and `ORG` in en because of it. An emulated engine
        that answered `ORG` for cs would look right and break every cs pack, so
        the vocabulary is per-language and enforced, not suggested."""
        engine, _ = an_engine(json.dumps({"entities": [{"text": "Microsoft", "label": "ORG"}]}))
        result = engine.analyze(TEXT, "cs", {NlpOp.NER})
        assert result.error != ""
        assert result.entities == []

    def test_supports_declares_only_the_v1_op_set(self):
        engine, _ = an_engine(LEMMA_REPLY)
        assert engine.supports("cs", NlpOp.LEMMATIZE)
        assert engine.supports("cs", NlpOp.NER)
        # DEP_PARSE is not promised (RV-6): a wrong parse is silent, and the
        # head indices would have to be self-consistent to be worth anything.
        assert not engine.supports("cs", NlpOp.DEP_PARSE)
        assert not engine.supports("cs", NlpOp.TOKENIZE)


# ── (c) an outage degrades like an absent engine ─────────────────────────────


class TestOutageDegradesLikeAbsence:
    def test_gateway_unavailable_is_an_engine_error_with_no_output(self):
        engine, _ = an_engine(raises=GatewayUnavailable("timed out"))
        result = engine.analyze(TEXT, "cs", {NlpOp.LEMMATIZE})
        assert result.tokens == [] and result.entities == []
        assert RV_NLP_020 in result.error

    def test_outage_is_not_cached(self):
        """A failure must not become the deployment's answer for that input."""
        engine, gw = an_engine(raises=GatewayUnavailable("timed out"))
        engine.analyze(TEXT, "cs", {NlpOp.LEMMATIZE})
        engine.analyze(TEXT, "cs", {NlpOp.LEMMATIZE})
        assert len(gw.calls) == 2


# ── (e) malformed output is a named failure, never a half-analysis ───────────


class TestMalformedOutputNeverFabricates:
    @pytest.mark.parametrize(
        "reply",
        [
            "I'm happy to help! Here are the lemmas:",          # prose, no JSON
            json.dumps({"tokens": "faktura"}),                   # right key, wrong type
            json.dumps({"lemmas": ["faktura"]}),                 # a shape we never asked for
            json.dumps({"tokens": [{"text": "faktury"}]}),       # token with no lemma
            json.dumps({"tokens": [{"text": "faktury", "lemma": "faktura", "upos": "NOUNISH"}]}),
        ],
        ids=["prose", "wrong-type", "wrong-shape", "missing-lemma", "invented-upos"],
    )
    def test_unusable_output_yields_a_named_error_and_nothing_else(self, reply):
        engine, _ = an_engine(reply)
        result = engine.analyze(TEXT, "cs", {NlpOp.LEMMATIZE})
        assert result.tokens == []
        assert RV_NLP_021 in result.error

    def test_a_fenced_reply_is_read_not_rejected(self):
        """Stripping a markdown fence is not leniency about content — the JSON
        inside is still parsed strictly. Rejecting the single most common
        wrapper would make the engine unusable for no honesty gained."""
        engine, _ = an_engine(f"```json\n{LEMMA_REPLY}\n```")
        result = engine.analyze(TEXT, "cs", {NlpOp.LEMMATIZE})
        assert result.error == ""
        assert len(result.tokens) == 4

    def test_a_token_that_is_not_in_the_text_fails_the_whole_analysis(self):
        """A token the source text does not contain is the model inventing
        content, and there is no honest way to serve the rest of an answer that
        contains it — the tokens around an invented one are exactly as trustworthy
        as it is. So the analysis fails, naming the token.

        It also cannot be emitted at `char_start=-1` the way the UFAL parsers do
        for a token they fail to locate: `merged_by_span` is keyed on the span, so
        two of those collapse into one."""
        reply = json.dumps(
            {
                "tokens": [
                    {"text": "Zobraz", "lemma": "zobrazit", "upos": "VERB"},
                    {"text": "Siemens", "lemma": "Siemens", "upos": "PROPN"},
                ]
            }
        )
        engine, _ = an_engine(reply)
        result = engine.analyze(TEXT, "cs", {NlpOp.LEMMATIZE})
        assert result.tokens == []
        assert RV_NLP_021 in result.error
        assert "Siemens" in result.error  # named, not merely counted


# ── (d) + T4: temperature 0, the model class, and deployment determinism ─────


class TestDeterminismPosture:
    def test_the_engine_advertises_its_template_version(self):
        """S-1 echoes must distinguish prompt revisions: two deployments running
        the same model and different templates are not the same engine."""
        engine, _ = an_engine(LEMMA_REPLY)
        assert engine.model_version == f"claude-haiku-4-5/tpl-{TEMPLATE_VERSION}"

    def test_same_input_same_output_within_a_deployment(self):
        engine, gw = an_engine(LEMMA_REPLY)
        first = engine.analyze(TEXT, "cs", {NlpOp.LEMMATIZE})
        second = engine.analyze(TEXT, "cs", {NlpOp.LEMMATIZE})
        assert len(gw.calls) == 1
        assert [t.lemma for t in first.tokens] == [t.lemma for t in second.tokens]

    def test_the_cache_key_separates_ops_languages_and_texts(self):
        engine, gw = an_engine(LEMMA_REPLY, NER_REPLY)
        engine.analyze(TEXT, "cs", {NlpOp.LEMMATIZE})
        engine.analyze(TEXT, "cs", {NlpOp.NER})
        assert len(gw.calls) == 2

    def test_the_cache_is_bounded(self):
        engine, gw = an_engine(LEMMA_REPLY, cache_max_entries=1)
        engine.analyze("Zobraz", "cs", {NlpOp.LEMMATIZE})
        engine.analyze("faktury", "cs", {NlpOp.LEMMATIZE})
        engine.analyze("Zobraz", "cs", {NlpOp.LEMMATIZE})
        assert len(gw.calls) == 3  # the first was evicted, not kept forever

    def test_the_route_is_advertised_as_unpinned(self):
        """Temperature 0 is necessary and not sufficient (RV-6): a provider can
        change what a model name serves. So the route carries the tier that
        already means 'non-conformant for parity/determinism', and every caller
        reading a route gets the caution without a new field."""
        registry = EngineRegistry(_app_config(enabled=True))
        registry._config.op_routing["NER.cs"] = EMULATED_ENGINE_NAME
        registry._op_routing["NER.cs"] = EMULATED_ENGINE_NAME
        route = registry.route("cs", NlpOp.NER)
        assert route.engine == EMULATED_ENGINE_NAME
        assert route.tier == "REMOTE_UNPINNED"


# ── helpers ──────────────────────────────────────────────────────────────────


def _app_config(*, enabled: bool) -> AppConfig:
    return AppConfig(
        engines=EnginesConfig(
            stanza=BackendConfig(url="http://stanza:8090", model="stanza-cs-en", model_version="1.10.0"),
            spacy=BackendConfig(url="http://spacy:8091", model="en_core_web_md", model_version="3.8.0"),
            morphodita=BackendConfig(enabled=False),
            nametag3=BackendConfig(enabled=False),
            langid=LangidEngineConfig(model_version="lingua-2.0"),
            llm_emulated=LlmEmulatedConfig(
                enabled=enabled, url="http://llm-gateway:8080", model="claude-haiku-4-5"
            ),
        ),
        op_routing={"TOKENIZE.cs": "stanza", "LEMMATIZE.cs": "stanza"},
    )
