# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the base engine module."""

from __future__ import annotations

from nlp_service.engines.base import EngineResult, NerEntity, NlpOp, Token


class TestToken:
    def test_token_creation(self):
        token = Token(
            text="Hello",
            char_start=0,
            char_end=5,
            lemma="hello",
            upos="INTJ",
            xpos="_INTERJ",
            feats={"Number": "Sing"},
            dep_head=0,
            dep_relation="root",
        )
        assert token.text == "Hello"
        assert token.char_start == 0
        assert token.char_end == 5
        assert token.lemma == "hello"
        assert token.upos == "INTJ"
        assert token.xpos == "_INTERJ"
        assert token.feats == {"Number": "Sing"}
        assert token.dep_head == 0
        assert token.dep_relation == "root"

#     def test_token_immutable(self):
#         token = Token(text="Test", char_start=0, char_end=4)
        # Token should be frozen (dataclass(frozen=True))
        # This will raise an error if uncommented:
        # token.text = "Modified"

    def test_token_default_values(self):
        token = Token(text="Test", char_start=0, char_end=4)
        assert token.lemma == ""
        assert token.upos == ""
        assert token.xpos == ""
        assert token.feats == {}
        assert token.dep_head == 0
        assert token.dep_relation == ""


class TestNerEntity:
    def test_ner_entity_creation(self):
        entity = NerEntity(
            text="Shell",
            label="ORG",
            char_start=10,
            char_end=15,
            normalized_value="",
            source_engine="nametag",
        )
        assert entity.text == "Shell"
        assert entity.label == "ORG"
        assert entity.char_start == 10
        assert entity.char_end == 15
        assert entity.source_engine == "nametag"


class TestEngineResult:
    def test_engine_result_creation(self):
        tokens = [Token(text="Test", char_start=0, char_end=4)]
        entities = [NerEntity(text="Shell", label="ORG", char_start=10, char_end=15)]
        result = EngineResult(
            tokens=tokens,
            entities=entities,
            sentences=[(0, 4)],
            paragraphs=[],
        )
        assert len(result.tokens) == 1
        assert len(result.entities) == 1
        assert result.sentences == [(0, 4)]
        assert result.error == ""

    def test_engine_result_with_error(self):
        result = EngineResult(error="Something went wrong")
        assert result.error == "Something went wrong"
        assert result.tokens == []
        assert result.entities == []


class TestNlpOp:
    def test_nlp_op_values(self):
        assert NlpOp.TOKENIZE.value == "TOKENIZE"
        assert NlpOp.LEMMATIZE.value == "LEMMATIZE"
        assert NlpOp.POS_TAG.value == "POS_TAG"
        assert NlpOp.DEP_PARSE.value == "DEP_PARSE"
        assert NlpOp.NER.value == "NER"
        assert NlpOp.DETECT_LANGUAGE.value == "DETECT_LANGUAGE"

    def test_nlp_op_from_string(self):
        op = NlpOp("TOKENIZE")
        assert op == NlpOp.TOKENIZE

    def test_nlp_op_invalid_string(self):
        try:
            NlpOp("INVALID")
            assert False, "Should raise ValueError"
        except ValueError:
            pass


class TestNlpEngineProtocol:
    """Test that the NlpEngine protocol works as expected."""

    def test_protocol_can_be_implemented(self):
        """Test that a class can implement NlpEngine protocol."""
        from typing import Set

        class MinimalEngine:
            @property
            def name(self) -> str:
                return "minimal"

            def supported_languages(self) -> Set[str]:
                return {"en"}

            def supports(self, lang: str, op: NlpOp) -> bool:
                return lang in self.supported_languages() and op == NlpOp.TOKENIZE

            def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
                return EngineResult()

        from nlp_service.engines.base import NlpEngine

        engine = MinimalEngine()
        # Protocol runtime check
        assert isinstance(engine, NlpEngine)
        assert engine.name == "minimal"
        assert engine.supports("en", NlpOp.TOKENIZE)
