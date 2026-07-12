# SPDX-License-Identifier: Apache-2.0
"""The degrade floor (RG-P1.S3.T5) — deterministic, in-front, always available.

When a routed (language, op) has no backend, the op drops to the floor:
TOKENIZE + `fold` + DETECT_LANGUAGE. `fold` is the S-2 normalization spec
(lowercase → NFD → strip combining marks) — the same fold the Kotlin `ttr-text`
lib implements (RG-P0.S3), so folding is byte-identical across the estate. The
floor produces tokens (with the folded form as a deterministic pseudo-lemma) so
consumers get *something* usable, labelled `RG-NLP-010`, instead of a 500.
"""

from __future__ import annotations

import re
import unicodedata
from typing import Set

from nlp_service.engines.base import EngineResult, NlpOp, Token

# Unicode word runs OR single non-space punctuation — deterministic, model-free.
_TOKEN_RE = re.compile(r"\w+|[^\w\s]", re.UNICODE)


def fold(text: str) -> str:
    """S-2 normalization: lowercase → NFD → strip combining marks."""
    decomposed = unicodedata.normalize("NFD", text.lower())
    return "".join(c for c in decomposed if not unicodedata.combining(c))


class FloorEngine:
    """The degrade-floor tokenizer — supports TOKENIZE/SENTENCE_SPLIT for ANY
    language. Deterministic; no models, no network."""

    @property
    def name(self) -> str:
        return "floor"

    def supported_languages(self) -> Set[str]:
        return set()  # any — the floor is language-agnostic

    def supports(self, lang: str, op: NlpOp) -> bool:
        return op in {NlpOp.TOKENIZE, NlpOp.SENTENCE_SPLIT}

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        tokens = [
            Token(
                text=m.group(0),
                char_start=m.start(),
                char_end=m.end(),
                lemma=fold(m.group(0)),  # the S-2 fold as the deterministic lemma
            )
            for m in _TOKEN_RE.finditer(text)
        ]
        sentences = [(0, len(text))] if text else []
        return EngineResult(tokens=tokens, entities=[], sentences=sentences, paragraphs=[])
