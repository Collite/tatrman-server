"""Tests for the NLP orchestrator."""

from __future__ import annotations

from unittest.mock import MagicMock, patch


from nlp_service.engines.base import EngineResult, NerEntity, NlpOp, Token
from nlp_service.pipeline.orchestrator import AnalyzeResponse, Orchestrator


class MockEngine:
    """Mock engine for testing."""

    def __init__(self, name: str, supported_langs: set, supported_ops: set):
        self._name = name
        self._supported_langs = supported_langs
        self._supported_ops = supported_ops

    @property
    def name(self) -> str:
        return self._name

    def supported_languages(self) -> set:
        return self._supported_langs

    def supports(self, lang: str, op: NlpOp) -> bool:
        return lang in self._supported_langs and op in self._supported_ops

    def analyze(self, text: str, lang: str, ops: set[NlpOp]) -> EngineResult:
        tokens = [
            Token(
                text="Ahoj",
                char_start=0,
                char_end=4,
                lemma="ahoj",
                upos="INTJ",
                xpos="_INTERJ",
                feats={},
                dep_head=0,
                dep_relation="root",
            )
        ]
        return EngineResult(
            tokens=tokens,
            entities=[],
            sentences=[(0, 4)],
            paragraphs=[],
        )


class TestOrchestrator:
    """Test the NLP orchestrator."""

    def test_orchestrator_initialization(self):
        """Test that orchestrator can be initialized."""
        orchestrator = Orchestrator()
        assert orchestrator is not None
        assert orchestrator._registry is not None

    def test_analyze_response_structure(self):
        """Test AnalyzeResponse dataclass."""
        response = AnalyzeResponse(
            language="cs",
            language_confidence=1.0,
            engine_used="stanza",
            tokens=[],
            sentences=[],
            paragraphs=[],
            entities=[],
            by_engine={},
            trace_id="test-trace-id",
            elapsed_ms=100,
            messages=[],
        )
        assert response.language == "cs"
        assert response.language_confidence == 1.0
        assert response.engine_used == "stanza"
        assert response.trace_id == "test-trace-id"
        assert response.elapsed_ms == 100

    def test_analyze_normal_mode_single_op(self):
        """Test NORMAL mode with single op routes to correct engine."""
        with patch("nlp_service.pipeline.orchestrator.EngineRegistry") as mock_registry_cls:
            mock_registry = MagicMock()
            mock_registry_cls.return_value = mock_registry

            mock_engine = MockEngine("stanza", {"cs"}, {NlpOp.TOKENIZE})
            mock_registry.get_engine.return_value = mock_engine
            mock_registry.route_op.return_value = mock_engine
            mock_registry.is_ready.return_value = True
            mock_registry.list_engines.return_value = ["stanza"]

            orchestrator = Orchestrator()

            result = orchestrator.analyze(
                text="Ahoj světe",
                language="cs",
                ops={NlpOp.TOKENIZE},
                mode="NORMAL",
                engine_hints={},
            )

            assert result.language == "cs"
            assert result.engine_used == "stanza"
            assert len(result.tokens) == 1
            assert result.tokens[0].text == "Ahoj"

    def test_analyze_normal_mode_engine_not_found(self):
        """Test NORMAL mode when no engine is available for an op."""
        with patch("nlp_service.pipeline.orchestrator.EngineRegistry") as mock_registry_cls:
            mock_registry = MagicMock()
            mock_registry_cls.return_value = mock_registry

            mock_registry.route_op.return_value = None
            mock_registry.is_ready.return_value = True

            orchestrator = Orchestrator()

            result = orchestrator.analyze(
                text="Hello",
                language="cs",
                ops={NlpOp.NER},
                mode="NORMAL",
                engine_hints={},
            )

            assert len(result.messages) > 0
            msg_codes = [m["code"] for m in result.messages]
            assert "engine_not_found" in msg_codes

    def test_analyze_compare_mode_fans_out_to_all_engines(self):
        """Test COMPARE mode invokes all engines for each op."""
        with patch("nlp_service.pipeline.orchestrator.EngineRegistry") as mock_registry_cls:
            mock_registry = MagicMock()
            mock_registry_cls.return_value = mock_registry

            engine1 = MockEngine("stanza", {"cs"}, {NlpOp.TOKENIZE, NlpOp.LEMMATIZE})
            engine2 = MockEngine("morphodita", {"cs"}, {NlpOp.TOKENIZE, NlpOp.LEMMATIZE})

            mock_registry.get_engine.side_effect = lambda name: {
                "stanza": engine1,
                "morphodita": engine2,
            }.get(name)
            mock_registry.get_all_engines_for_op.side_effect = lambda op, lang: {
                (NlpOp.TOKENIZE, "cs"): [engine1, engine2],
                (NlpOp.LEMMATIZE, "cs"): [engine1, engine2],
            }.get((op, lang), [])
            mock_registry.is_ready.return_value = True
            mock_registry.list_engines.return_value = ["stanza", "morphodita"]

            orchestrator = Orchestrator()

            result = orchestrator.analyze(
                text="Ahoj",
                language="cs",
                ops={NlpOp.TOKENIZE, NlpOp.LEMMATIZE},
                mode="COMPARE",
                engine_hints={},
            )

            assert len(result.by_engine) == 2
            assert "stanza" in result.by_engine
            assert "morphodita" in result.by_engine

    def test_analyze_compare_mode_engine_hints_ignored(self):
        """Test COMPARE mode ignores engine hints - runs all engines."""
        with patch("nlp_service.pipeline.orchestrator.EngineRegistry") as mock_registry_cls:
            mock_registry = MagicMock()
            mock_registry_cls.return_value = mock_registry

            engine1 = MockEngine("stanza", {"cs"}, {NlpOp.TOKENIZE})
            engine2 = MockEngine("morphodita", {"cs"}, {NlpOp.TOKENIZE})

            mock_registry.get_engine.side_effect = lambda name: {
                "stanza": engine1,
                "morphodita": engine2,
            }.get(name)
            mock_registry.get_all_engines_for_op.return_value = [engine1, engine2]
            mock_registry.is_ready.return_value = True

            orchestrator = Orchestrator()

            result = orchestrator.analyze(
                text="Ahoj",
                language="cs",
                ops={NlpOp.TOKENIZE},
                mode="COMPARE",
                engine_hints={"TOKENIZE": "stanza"},
            )

            assert len(result.by_engine) == 2

    def test_analyze_normal_mode_engine_hints_respected(self):
        """Test NORMAL mode respects engine hints."""
        with patch("nlp_service.pipeline.orchestrator.EngineRegistry") as mock_registry_cls:
            mock_registry = MagicMock()
            mock_registry_cls.return_value = mock_registry

            engine_hint = MockEngine("morphodita", {"cs"}, {NlpOp.TOKENIZE})
            engine_default = MockEngine("stanza", {"cs"}, {NlpOp.TOKENIZE})

            mock_registry.route_op.side_effect = lambda op, lang, hint: (
                engine_hint if hint == "morphodita" else engine_default
            )
            mock_registry.get_engine.side_effect = lambda name: {
                "stanza": engine_default,
                "morphodita": engine_hint,
            }.get(name)
            mock_registry.is_ready.return_value = True

            orchestrator = Orchestrator()

            result = orchestrator.analyze(
                text="Ahoj",
                language="cs",
                ops={NlpOp.TOKENIZE},
                mode="NORMAL",
                engine_hints={"TOKENIZE": "morphodita"},
            )

            assert result.engine_used == "morphodita"

    def test_language_auto_detection(self):
        """Test language auto-detection when language is empty."""
        with patch("nlp_service.pipeline.orchestrator.EngineRegistry") as mock_registry_cls:
            mock_registry = MagicMock()
            mock_registry_cls.return_value = mock_registry

            langid_engine = MagicMock()
            langid_engine.name = "langid"
            langid_engine.analyze.return_value = EngineResult(
                detected_language="en",
                language_confidence=0.95,
            )

            mock_engine = MockEngine("stanza", {"en"}, {NlpOp.TOKENIZE})
            mock_registry.get_engine.side_effect = lambda name: (
                langid_engine if name == "langid" else mock_engine
            )
            mock_registry.route_op.return_value = mock_engine
            mock_registry.is_ready.return_value = True

            orchestrator = Orchestrator()

            result = orchestrator.analyze(
                text="Hello world",
                language="",
                ops={NlpOp.DETECT_LANGUAGE, NlpOp.TOKENIZE},
                mode="NORMAL",
            )

            assert result.language == "en"

    def test_language_detection_error_falls_back_to_default(self):
        """Test that language detection error falls back to default language."""
        with patch("nlp_service.pipeline.orchestrator.EngineRegistry") as mock_registry_cls:
            mock_registry = MagicMock()
            mock_registry_cls.return_value = mock_registry

            langid_engine = MagicMock()
            langid_engine.name = "langid"
            langid_engine.analyze.return_value = EngineResult(error="Detection failed")

            mock_engine = MockEngine("stanza", {"cs"}, {NlpOp.TOKENIZE})
            mock_registry.get_engine.side_effect = lambda name: (
                langid_engine if name == "langid" else mock_engine
            )
            mock_registry.route_op.return_value = mock_engine
            mock_registry.is_ready.return_value = True

            orchestrator = Orchestrator()

            result = orchestrator.analyze(
                text="Ahoj",
                language="",
                ops={NlpOp.DETECT_LANGUAGE, NlpOp.TOKENIZE},
                mode="NORMAL",
            )

            assert "lang_detection_failed" in [m["code"] for m in result.messages]


class TestAnalyzeResponse:
    """Test the AnalyzeResponse structure."""

    def test_response_with_tokens(self):
        """Test response with token data."""
        tokens = [
            Token(
                text="Hello",
                char_start=0,
                char_end=5,
                lemma="hello",
                upos="INTJ",
                xpos="_INTERJ",
                feats={},
                dep_head=0,
                dep_relation="root",
            )
        ]
        response = AnalyzeResponse(
            language="en",
            language_confidence=1.0,
            engine_used="stanza",
            tokens=tokens,
            sentences=[(0, 5)],
            paragraphs=[],
            entities=[],
            by_engine={},
            trace_id="trace-123",
            elapsed_ms=50,
            messages=[],
        )
        assert len(response.tokens) == 1
        assert response.tokens[0].text == "Hello"
        assert response.tokens[0].lemma == "hello"

    def test_response_with_entities(self):
        """Test response with NER entities."""
        entities = [
            NerEntity(
                text="Shell",
                label="ORG",
                char_start=0,
                char_end=5,
                normalized_value="",
                source_engine="nametag",
            )
        ]
        response = AnalyzeResponse(
            language="cs",
            language_confidence=1.0,
            engine_used="nametag",
            tokens=[],
            sentences=[],
            paragraphs=[],
            entities=entities,
            by_engine={},
            trace_id="trace-456",
            elapsed_ms=75,
            messages=[],
        )
        assert len(response.entities) == 1
        assert response.entities[0].text == "Shell"
        assert response.entities[0].label == "ORG"

    def test_response_messages(self):
        """Test response with warning/error messages."""
        messages = [
            {"severity": "WARNING", "code": "lang_detection_failed", "message": "Language detection failed, defaulting to Czech"},
            {"severity": "ERROR", "code": "engine_error", "message": "stanza failed: some error"},
        ]
        response = AnalyzeResponse(
            language="cs",
            language_confidence=0.5,
            engine_used="stanza",
            tokens=[],
            sentences=[],
            paragraphs=[],
            entities=[],
            by_engine={},
            trace_id="trace-789",
            elapsed_ms=200,
            messages=messages,
        )
        assert len(response.messages) == 2
        assert response.messages[0]["severity"] == "WARNING"
        assert response.messages[1]["severity"] == "ERROR"

    def test_response_by_engine_populated_in_compare_mode(self):
        """Test byEngine map is populated in COMPARE mode results."""
        engine_results = {
            "stanza": EngineResult(
                tokens=[Token(text="Test", char_start=0, char_end=4, lemma="test", upos="NN", xpos="_NN", feats={}, dep_head=0, dep_relation="root")],
                entities=[],
                sentences=[(0, 4)],
                paragraphs=[],
            ),
        }
        response = AnalyzeResponse(
            language="cs",
            language_confidence=1.0,
            engine_used="stanza",
            tokens=[],
            sentences=[],
            paragraphs=[],
            entities=[],
            by_engine=engine_results,
            trace_id="trace-compare",
            elapsed_ms=100,
            messages=[],
        )
        assert len(response.by_engine) == 1
        assert "stanza" in response.by_engine
