# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the LangidEngine."""

from __future__ import annotations

from unittest.mock import MagicMock, patch


from nlp_service.engines.base import NlpOp
from nlp_service.engines.langid_engine import LangidEngine


class TestLangidEngine:
    """Test the LangidEngine."""

    def test_langid_engine_name(self):
        """Test engine name property."""
        engine = LangidEngine()
        assert engine.name == "langid"

    def test_supported_languages(self):
        """Test supported languages include cs and en."""
        engine = LangidEngine()
        langs = engine.supported_languages()
        assert "cs" in langs
        assert "en" in langs

    def test_supports_only_detect_language(self):
        """Test that engine only supports DETECT_LANGUAGE op."""
        engine = LangidEngine()
        assert engine.supports("cs", NlpOp.DETECT_LANGUAGE) is True
        assert engine.supports("cs", NlpOp.TOKENIZE) is False
        assert engine.supports("en", NlpOp.LEMMATIZE) is False

    def test_analyze_returns_detected_language_and_confidence(self):
        """Test that analyze returns language and confidence via proper fields.

        Mocks the lingua 2.x API (`detect_language_of` +
        `compute_language_confidence_values`) — see the langid engine bugfix in
        Stage 2.3 (the original mocked the non-existent `.detect`).
        """
        engine = LangidEngine()
        with patch.object(engine, "_detector") as mock_detector:
            language = MagicMock()
            language.iso_code_639_1.name = "EN"
            mock_detector.detect_language_of.return_value = language

            confidence_value = MagicMock()
            confidence_value.language = language
            confidence_value.value = 0.95
            mock_detector.compute_language_confidence_values.return_value = [confidence_value]

            result = engine.analyze("Hello world", "", {NlpOp.DETECT_LANGUAGE})

            assert result.detected_language == "en"
            assert result.language_confidence == 0.95
            assert result.error == ""
            assert result.tokens == []

    def test_analyze_error_when_no_language_detected(self):
        """Test that analyze returns error when no language detected."""
        engine = LangidEngine()
        with patch.object(engine, "_detector") as mock_detector:
            mock_detector.detect_language_of.return_value = None

            result = engine.analyze("", "", {NlpOp.DETECT_LANGUAGE})

            assert result.error == "No language detected"

    def test_analyze_rejects_non_detect_language_ops(self):
        """Test that analyze returns error for non-DETECT_LANGUAGE ops."""
        engine = LangidEngine()
        result = engine.analyze("Hello", "en", {NlpOp.TOKENIZE})
        assert result.error == "LangidEngine only supports DETECT_LANGUAGE operation"

    def test_analyze_against_real_lingua_detects_cs_and_en(self):
        """Integration guard against the real lingua 2.x API (no mocks).

        Locks the Stage 2.3 bugfix: the original `.detect`/`.iso_code_639_1.value`
        calls raised and silently defaulted every input to Czech. Pure local lib,
        no model download / network.
        """
        engine = LangidEngine()

        cs = engine.analyze("Kdo je zákazník Shell UK?", "", {NlpOp.DETECT_LANGUAGE})
        assert cs.error == ""
        assert cs.detected_language == "cs"
        assert cs.language_confidence > 0.0

        en = engine.analyze(
            "Who is the customer Shell UK and what are their orders?",
            "",
            {NlpOp.DETECT_LANGUAGE},
        )
        assert en.error == ""
        assert en.detected_language == "en"
        assert en.language_confidence > 0.0