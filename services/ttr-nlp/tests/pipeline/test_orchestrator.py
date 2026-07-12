# SPDX-License-Identifier: Apache-2.0
"""Orchestrator tests (RG-P1.S1) — routing, merge, and the S-1 `used[]` stamp.

Uses a real `EngineRegistry` built from a test config with the HTTP-adapter
engines' `analyze` stubbed (no network). Verifies ops group by routed engine,
results merge, and every served op is echoed in `used[]` with a non-blank model
(S-1 / T6).
"""

from __future__ import annotations


from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
)
from nlp_service.contract import is_s1_clean
from nlp_service.diagnostics import RG_NLP_010
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import EngineResult, NerEntity, NlpOp, Token
from nlp_service.pipeline.orchestrator import AnalyzeResponse, Orchestrator


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
            "POS_TAG.cs": "morphodita",
            "DEP_PARSE.cs": "stanza",
            "NER.cs": "nametag3",
            "TOKENIZE.en": "stanza",
            "NER.en": "stanza",
            "NER.en.fallback": "spacy",
            "DETECT_LANGUAGE": "langid",
        },
        default_language="cs",
    )


def _stub(monkeypatch, registry: EngineRegistry, engine_name: str, result: EngineResult) -> None:
    engine = registry.get_engine(engine_name)
    monkeypatch.setattr(engine, "analyze", lambda text, lang, ops: result)


_HERO_TOKENS = [
    Token(text="Octavie", char_start=22, char_end=29, lemma="Octavia", upos="PROPN", xpos="NNIP1"),
]


class TestAnalyzeResponse:
    def test_response_structure(self):
        response = AnalyzeResponse(
            language="cs", language_confidence=1.0, engine_used="morphodita",
        )
        assert response.language == "cs"
        assert response.used == []


class TestNormalMode:
    def test_cs_lemmatize_routes_to_morphodita_and_stamps_used(self, monkeypatch):
        registry = EngineRegistry(_config())
        _stub(monkeypatch, registry, "morphodita", EngineResult(tokens=_HERO_TOKENS, sentences=[(0, 30)]))
        orch = Orchestrator(_config(), registry)

        result = orch.analyze("Kolik jsme utržili za Octavie", "cs", {NlpOp.LEMMATIZE})

        assert result.tokens[0].lemma == "Octavia"
        assert result.engine_used == "morphodita"
        # S-1: used[] populated, engine + model named, no blank model.
        assert len(result.used) == 1
        assert result.used[0].engine == "morphodita"
        assert result.used[0].op == "LEMMATIZE"
        assert result.used[0].model
        assert is_s1_clean(result.used)

    def test_cs_ner_routes_to_nametag3(self, monkeypatch):
        registry = EngineRegistry(_config())
        ent = NerEntity(text="pražských pobočkách", label="G", char_start=32, char_end=51, source_engine="nametag3")
        _stub(monkeypatch, registry, "nametag3", EngineResult(entities=[ent]))
        orch = Orchestrator(_config(), registry)

        result = orch.analyze("... v pražských pobočkách", "cs", {NlpOp.NER})

        assert result.entities[0].label == "G"
        assert result.used[0].engine == "nametag3"
        assert result.used[0].model

    def test_multi_op_calls_each_engine_once_and_merges(self, monkeypatch):
        registry = EngineRegistry(_config())
        calls = {"morphodita": 0, "nametag3": 0}

        def morpho(text, lang, ops):
            calls["morphodita"] += 1
            return EngineResult(tokens=_HERO_TOKENS)

        def nametag(text, lang, ops):
            calls["nametag3"] += 1
            return EngineResult(entities=[NerEntity(text="Octavie", label="P", char_start=22, char_end=29, source_engine="nametag3")])

        monkeypatch.setattr(registry.get_engine("morphodita"), "analyze", morpho)
        monkeypatch.setattr(registry.get_engine("nametag3"), "analyze", nametag)
        orch = Orchestrator(_config(), registry)

        result = orch.analyze("Octavie", "cs", {NlpOp.LEMMATIZE, NlpOp.POS_TAG, NlpOp.NER})

        assert calls == {"morphodita": 1, "nametag3": 1}  # one pass each
        assert result.tokens and result.entities
        # used[] has one entry per served op (LEMMATIZE, POS_TAG via morphodita; NER via nametag3)
        ops_stamped = sorted(ev.op for ev in result.used)
        assert ops_stamped == ["LEMMATIZE", "NER", "POS_TAG"]
        assert is_s1_clean(result.used)

    def test_engine_hint_respected(self, monkeypatch):
        registry = EngineRegistry(_config())
        _stub(monkeypatch, registry, "stanza", EngineResult(tokens=_HERO_TOKENS))
        orch = Orchestrator(_config(), registry)

        # Force cs TOKENIZE onto stanza instead of the configured morphodita.
        result = orch.analyze("Octavie", "cs", {NlpOp.TOKENIZE}, engine_hints={"TOKENIZE": "stanza"})
        assert result.used[0].engine == "stanza"


class TestLanguageDetection:
    def test_auto_detection_real_langid(self):
        orch = Orchestrator(_config())
        result = orch.analyze(
            "Who is the customer Shell UK?", "", {NlpOp.DETECT_LANGUAGE}
        )
        assert result.language == "en"
        # langid stamped in used[] (S-1).
        assert any(ev.engine == "langid" and ev.op == "DETECT_LANGUAGE" for ev in result.used)


class TestDegradeFloorMarker:
    def test_unsupported_language_emits_rg_nlp_010(self):
        orch = Orchestrator(_config())
        result = orch.analyze("Hallo Welt", "de", {NlpOp.LEMMATIZE})
        codes = [m["code"] for m in result.messages]
        assert RG_NLP_010 in codes
