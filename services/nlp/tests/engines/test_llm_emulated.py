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
    EngineConfigError,
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

    def test_a_partial_word_is_not_in_the_text_however_well_find_locates_it(self):
        """The failure `str.find` cannot see, and the likeliest one in Czech.

        `Microsoft` IS a substring of `Microsoftu` — a model that answered with
        the base form of an inflected word located cleanly, produced a span that
        is not a word, attached to no token the orchestrator merged, and was
        served as a confident wrong answer. Nothing about the reply is malformed;
        the only evidence is the stranded `u`.
        """
        text = "Microsoftu patří Praha"
        reply = json.dumps(
            {
                "tokens": [
                    {"text": "Microsoft", "lemma": "Microsoft", "upos": "PROPN"},
                    {"text": "patří", "lemma": "patřit", "upos": "VERB"},
                    {"text": "Praha", "lemma": "Praha", "upos": "PROPN"},
                ]
            }
        )
        engine, _ = an_engine(reply)
        result = engine.analyze(text, "cs", {NlpOp.LEMMATIZE})
        assert result.tokens == []
        assert RV_NLP_021 in result.error
        assert "'u'" in result.error  # the stranded character, named

    def test_a_token_stream_that_drops_the_end_of_the_text_fails(self):
        """Same rule from the other side: the prompt asks for every token in
        order, punctuation included, so a token stream that stops early has
        dropped content — and dropped content is exactly as unserveable as
        invented content."""
        text = "Zobraz faktury zákazníka Microsoft."
        engine, _ = an_engine(LEMMA_REPLY)  # the same four tokens, no final "."
        result = engine.analyze(text, "cs", {NlpOp.LEMMATIZE})
        assert result.tokens == []
        assert RV_NLP_021 in result.error

    def test_a_legitimate_split_inside_a_word_is_still_accepted(self):
        """What the coverage rule buys over a word-boundary rule: adjacent spans.

        A tokeniser that splits `don't` into `do` + `n't` cuts a word in half and
        is right to — UD does exactly this. Contiguity accepts it and still
        rejects the truncation above, which a boundary rule could not do.
        """
        text = "I don't know"
        reply = json.dumps(
            {
                "tokens": [
                    {"text": "I", "lemma": "I", "upos": "PRON"},
                    {"text": "do", "lemma": "do", "upos": "AUX"},
                    {"text": "n't", "lemma": "not", "upos": "PART"},
                    {"text": "know", "lemma": "know", "upos": "VERB"},
                ]
            }
        )
        engine, _ = an_engine(reply)
        result = engine.analyze(text, "en", {NlpOp.LEMMATIZE})
        assert result.error == ""
        assert [(t.char_start, t.char_end) for t in result.tokens] == [
            (0, 1), (2, 4), (4, 7), (8, 12)
        ]

    def test_an_entity_that_is_only_part_of_a_word_is_refused(self):
        """Entities are sparse — nothing covers the gaps, so coverage cannot be
        the check. Word boundaries are: an alphanumeric may not sit against an
        alphanumeric edge of the surface."""
        engine, _ = an_engine(
            json.dumps({"entities": [{"text": "Microsoft", "label": "ORGANIZATION"}]})
        )
        result = engine.analyze("Faktury Microsoftu za leden", "cs", {NlpOp.NER})
        assert result.entities == []
        assert RV_NLP_021 in result.error
        assert "only inside a longer word" in result.error

    def test_an_entity_ending_on_punctuation_is_not_a_boundary_violation(self):
        """The rule catches a word cut short, not entities that fail to be
        surrounded by spaces — `Praze` before a full stop is an ordinary
        entity."""
        engine, _ = an_engine(json.dumps({"entities": [{"text": "Praze", "label": "LOCATION"}]}))
        result = engine.analyze("Tržby v Praze.", "cs", {NlpOp.NER})
        assert [e.text for e in result.entities] == ["Praze"]

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


# ── entities are not a stream ────────────────────────────────────────────────


class TestEntitiesAreLocatedIndependently:
    """A shared forward cursor is right for tokens and wrong for entities.

    The prompt asks for document order and models routinely ignore it — and with
    one cursor, one entity arriving early cost EVERY entity on the request, which
    is a full `RV-NLP-021` for a reply that was merely unsorted. Tokens keep the
    cursor: a stream is ordered by construction, and the cursor is what makes a
    repeated word land on its own occurrence.
    """

    TEXT = "Praha a Microsoft"

    def test_entities_out_of_document_order_are_located_not_refused(self):
        engine, _ = an_engine(
            json.dumps(
                {
                    "entities": [
                        {"text": "Microsoft", "label": "ORGANIZATION"},
                        {"text": "Praha", "label": "LOCATION"},
                    ]
                }
            )
        )
        result = engine.analyze(self.TEXT, "cs", {NlpOp.NER})
        assert result.error == ""
        assert [(e.text, e.char_start, e.char_end) for e in result.entities] == [
            ("Microsoft", 8, 17),
            ("Praha", 0, 5),
        ]

    def test_a_repeated_entity_still_lands_on_its_own_occurrence(self):
        """What the cursor used to buy, kept: two mentions are two spans, not the
        same span twice (the orchestrator dedups on the span, so they would
        collapse into one entity)."""
        text = "Praha a Praha"
        engine, _ = an_engine(
            json.dumps(
                {
                    "entities": [
                        {"text": "Praha", "label": "LOCATION"},
                        {"text": "Praha", "label": "LOCATION"},
                    ]
                }
            )
        )
        result = engine.analyze(text, "cs", {NlpOp.NER})
        assert [(e.char_start, e.char_end) for e in result.entities] == [(0, 5), (8, 13)]

    def test_more_mentions_than_the_text_carries_is_still_a_named_failure(self):
        """Searching from zero must not turn into "every entity finds something":
        a third `Praha` in a text with two is the model inventing a mention, and
        that is the invention rule, unchanged."""
        engine, _ = an_engine(
            json.dumps({"entities": [{"text": "Praha", "label": "LOCATION"}] * 2})
        )
        result = engine.analyze("Praha", "cs", {NlpOp.NER})
        assert result.entities == []
        assert RV_NLP_021 in result.error
        assert "claimed more often" in result.error

    def test_a_nested_entity_can_have_its_own_span(self):
        engine, _ = an_engine(
            json.dumps(
                {
                    "entities": [
                        {"text": "Univerzita Karlova", "label": "ORGANIZATION"},
                        {"text": "Karlova", "label": "PERSON"},
                    ]
                }
            )
        )
        result = engine.analyze("Univerzita Karlova v Praze", "cs", {NlpOp.NER})
        assert [(e.char_start, e.char_end) for e in result.entities] == [(0, 18), (11, 18)]


# ── an enabled engine has to be able to reach a model ────────────────────────


class TestEnabledMeansServiceable:
    """RV-P8.2's rule — a config that cannot serve must not load half-way —
    applied to the engine's own config rather than only to routing.

    Helm's `required` guards the chart and nothing else. Every other way in
    (`NLP_LLM_EMULATED_ENABLED=true` from compose, a dev shell, a bare env) left
    the engine registered with no address: `supports()` reads the templates on
    disk and says yes, `validate_routing` passes, and the estate boots green to
    spend three transport failures per request discovering what the config knew.
    """

    def test_enabled_without_a_url_is_a_boot_error(self):
        cfg = _app_config(enabled=True)
        cfg.engines.llm_emulated.url = ""
        with pytest.raises(EngineConfigError, match="url"):
            EngineRegistry(cfg)

    def test_enabled_without_a_model_is_a_boot_error(self):
        """`model` is the S-1 echo as much as it is the call: an engine with no
        model id cannot name what produced an analysis."""
        cfg = _app_config(enabled=True)
        cfg.engines.llm_emulated.model = "  "
        with pytest.raises(EngineConfigError, match="model"):
            EngineRegistry(cfg)

    def test_a_missing_key_warns_rather_than_refusing_to_boot(self, caplog):
        """The asymmetry is deliberate. A gateway with no address cannot be
        called at all; a keyless one is an ordinary local deployment, and failing
        on it would make a working setup unbootable to pre-empt a 401 the gateway
        reports perfectly well itself."""
        cfg = _app_config(enabled=True)
        cfg.engines.llm_emulated.api_key = ""
        with caplog.at_level("WARNING"):
            registry = EngineRegistry(cfg)
        assert EMULATED_ENGINE_NAME in registry.list_engines()
        assert "api_key" in caplog.text

    def test_a_disabled_engine_is_not_held_to_any_of_it(self):
        """Off is off: the shipped config has an empty url and must keep booting."""
        cfg = _app_config(enabled=False)
        cfg.engines.llm_emulated.url = ""
        assert EMULATED_ENGINE_NAME not in EngineRegistry(cfg).list_engines()


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
