# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Protocol, Set, runtime_checkable


class NlpOp(str, Enum):
    """NLP operations supported by the NLP service."""

    TOKENIZE = "TOKENIZE"
    SENTENCE_SPLIT = "SENTENCE_SPLIT"
    LEMMATIZE = "LEMMATIZE"
    POS_TAG = "POS_TAG"
    DEP_PARSE = "DEP_PARSE"
    NER = "NER"
    DETECT_LANGUAGE = "DETECT_LANGUAGE"


@dataclass(frozen=True)
class Token:
    """Represents a single token with linguistic annotations."""

    text: str
    char_start: int
    char_end: int
    lemma: str = ""
    upos: str = ""  # Universal POS tag
    xpos: str = ""  # Language-specific POS tag
    feats: dict[str, str] = field(default_factory=dict)  # Morphological features
    dep_head: int = 0  # Head token index (1-based), 0 = root
    dep_relation: str = ""  # UD dependency relation


@dataclass(frozen=True)
class NerEntity:
    """Represents a named entity extracted from text."""

    text: str
    label: str  # e.g., PER, LOC, ORG, DATE, MONEY
    char_start: int
    char_end: int
    normalized_value: str = ""  # e.g., ISO date for DATE, typed amount for MONEY
    source_engine: str = ""  # Which engine produced this entity


@dataclass
class EngineResult:
    """Result from a single NLP engine."""

    tokens: list[Token] = field(default_factory=list)
    entities: list[NerEntity] = field(default_factory=list)
    sentences: list[tuple[int, int]] = field(default_factory=list)  # (char_start, char_end) tuples
    paragraphs: list[tuple[int, int]] = field(default_factory=list)  # (char_start, char_end) tuples
    error: str = ""  # Non-empty if engine failed
    detected_language: str = ""  # Language code (used by DETECT_LANGUAGE engine)
    language_confidence: float = 0.0  # Confidence score (used by DETECT_LANGUAGE engine)


@runtime_checkable
class NlpEngine(Protocol):
    """Plugin interface for NLP engines.

    Implement this protocol to add a new NLP engine (Stanza, spaCy, NameTag, etc.)
    """

    @property
    def name(self) -> str:
        """Engine name, e.g., 'stanza', 'spacy', 'nametag', 'langid'."""
        ...

    def supported_languages(self) -> Set[str]:
        """Return set of supported language codes (e.g., {'cs', 'en'})."""
        ...

    def supports(self, lang: str, op: NlpOp) -> bool:
        """Check if this engine supports the given language and operation."""
        ...

    def analyze(
        self,
        text: str,
        lang: str,
        ops: Set[NlpOp],
    ) -> EngineResult:
        """Run the requested operations on the given text.

        Args:
            text: Input text to analyze
            lang: Language code (e.g., 'cs', 'en')
            ops: Set of requested operations

        Returns:
            EngineResult with tokens, entities, sentences, paragraphs, and optional error

        Note:
            Engines should run all requested operations in a single pass when possible
            to avoid redundant tokenization/parsing.
        """
        ...