# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S1.T3 — per-op-per-language routing (test-first).

`EngineRegistry.route(language, op)` resolves each (language, op) to a `Route`
that names an engine **and** a non-empty model + version (S-1 at the route
level). Unsupported (language, op) resolves to the degrade floor with an
`RG-NLP-010` info marker (never a 500, never an empty model). Routing is
config-driven — the assertions build a config, not the live `config.yaml`.
"""

from __future__ import annotations

import pytest

from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
)
from nlp_service.diagnostics import RG_NLP_002, RG_NLP_010
from nlp_service.engines.base import NlpOp
from nlp_service.engines import EngineRegistry


def _config() -> AppConfig:
    """A self-hosted-pinned cs/en config mirroring the RG-P1 routing table."""
    return AppConfig(
        engines=EnginesConfig(
            morphodita=BackendConfig(
                url="http://morphodita:8080",
                model="czech-morfflex2.0-pdtc1.0-220710",
                model_version="220710",
                tier="SELF_HOSTED_PINNED",
            ),
            nametag3=BackendConfig(
                url="http://nametag3:8001",
                model="nametag3-czech-cnec2.0-240830",
                model_version="240830",
                tier="SELF_HOSTED_PINNED",
            ),
            stanza=BackendConfig(
                url="http://stanza:8090",
                model="stanza-cs-en",
                model_version="1.10.0",
                tier="SELF_HOSTED_PINNED",
            ),
            spacy=BackendConfig(
                url="http://spacy:8091",
                model="en_core_web_md",
                model_version="3.8.0",
                tier="SELF_HOSTED_PINNED",
            ),
            langid=LangidEngineConfig(model_version="lingua-2.0"),
        ),
        op_routing={
            "TOKENIZE.cs": "morphodita",
            "SENTENCE_SPLIT.cs": "morphodita",
            "LEMMATIZE.cs": "morphodita",
            "POS_TAG.cs": "morphodita",
            "DEP_PARSE.cs": "stanza",
            "NER.cs": "nametag3",
            "TOKENIZE.en": "stanza",
            "LEMMATIZE.en": "stanza",
            "POS_TAG.en": "stanza",
            "DEP_PARSE.en": "stanza",
            "NER.en": "stanza",
            "NER.en.fallback": "spacy",
            "DETECT_LANGUAGE": "langid",
        },
        default_language="cs",
    )


@pytest.fixture
def registry() -> EngineRegistry:
    return EngineRegistry(_config())


class TestCzechRouting:
    @pytest.mark.parametrize(
        "op,engine",
        [
            (NlpOp.TOKENIZE, "morphodita"),
            (NlpOp.SENTENCE_SPLIT, "morphodita"),
            (NlpOp.LEMMATIZE, "morphodita"),
            (NlpOp.POS_TAG, "morphodita"),
            (NlpOp.DEP_PARSE, "stanza"),
            (NlpOp.NER, "nametag3"),
        ],
    )
    def test_cs_routes(self, registry, op, engine):
        route = registry.route("cs", op)
        assert route.engine == engine
        assert not route.is_floor


class TestEnglishRouting:
    @pytest.mark.parametrize(
        "op,engine",
        [
            (NlpOp.TOKENIZE, "stanza"),
            (NlpOp.LEMMATIZE, "stanza"),
            (NlpOp.NER, "stanza"),
        ],
    )
    def test_en_routes(self, registry, op, engine):
        route = registry.route("en", op)
        assert route.engine == engine
        assert not route.is_floor


class TestDetectLanguage:
    def test_detect_language_routes_to_langid(self, registry):
        route = registry.route("cs", NlpOp.DETECT_LANGUAGE)
        assert route.engine == "langid"


class TestS1AtRouteLevel:
    def test_no_configured_route_has_an_empty_model(self, registry):
        """Every (lang, op) in the routing table names engine + model + version."""
        for lang in ("cs", "en"):
            for op in NlpOp:
                route = registry.route(lang, op)
                assert route.engine, f"{lang}/{op} resolved to no engine"
                assert route.model, f"{lang}/{op} → engine {route.engine!r} has empty model (S-1)"
                assert route.model_version, f"{lang}/{op} → {route.engine!r} has empty version"


class TestDegradeFloor:
    def test_unsupported_language_falls_to_floor_with_rg_nlp_010(self, registry):
        route = registry.route("de", NlpOp.LEMMATIZE)
        assert route.is_floor
        assert RG_NLP_010 in route.info
        # S-1 holds even on the floor: it names its deterministic producer.
        assert route.model

    def test_floor_tokenize_is_deterministic_producer(self, registry):
        route = registry.route("de", NlpOp.TOKENIZE)
        assert route.is_floor
        assert route.model  # e.g. the fold/segmentation producer, never blank


class TestRemoteUnpinnedTier:
    def test_lindat_route_is_flagged_remote_unpinned(self):
        """A Lindat-pointed backend reports REMOTE_UNPINNED + RG-NLP-002."""
        cfg = _config()
        cfg.engines.morphodita.tier = "REMOTE_UNPINNED"
        cfg.engines.morphodita.url = "https://lindat.mff.cuni.cz/services/morphodita/api/tag"
        registry = EngineRegistry(cfg)
        route = registry.route("cs", NlpOp.LEMMATIZE)
        assert route.tier == "REMOTE_UNPINNED"
        assert RG_NLP_002 in route.info
