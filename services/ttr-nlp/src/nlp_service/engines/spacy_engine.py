# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import logging
from typing import Set

import spacy

from nlp_service.config import AppConfig, load_config
from nlp_service.engines.base import EngineResult, NerEntity, NlpOp, Token

logger = logging.getLogger(__name__)


class SpacyEngine:
    """NLP engine implementation using spaCy.

    Supports English NER and basic tokenization via en_core_web_md model.
    """

    def __init__(self, config: AppConfig | None = None):
        self._config = config or load_config()
        self._nlp = None
        self._model_name = self._config.engines.spacy.model_name

    @property
    def name(self) -> str:
        return "spacy"

    def supported_languages(self) -> Set[str]:
        # en_core_web_md is English
        return {"en"}

    def supports(self, lang: str, op: NlpOp) -> bool:
        # spaCy with en_core_web_md supports tokenization, NER, and basic linguistic features
        supported_ops = {NlpOp.TOKENIZE, NlpOp.NER}
        return lang == "en" and op in supported_ops

    def _get_nlp(self):
        """Load spaCy model if not already loaded."""
        if self._nlp is None:
            try:
                self._nlp = spacy.load(self._model_name)
            except OSError:
                logger.warning(f"spaCy model {self._model_name} not found. Attempting to download...")
                import subprocess

                subprocess.run(["python", "-m", "spacy", "download", self._model_name], check=True)
                self._nlp = spacy.load(self._model_name)
        return self._nlp

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        """Run spaCy analysis on the input text."""
        try:
            nlp = self._get_nlp()
            doc = nlp(text)

            tokens: list[Token] = []
            entities: list[NerEntity] = []
            sentences: list[tuple[int, int]] = []

            # spaCy provides sentences
            for sent in doc.sents:
                sentences.append((sent.start_char, sent.end_char))

            # spaCy provides tokens
            for token in doc:
                feat_dict = {}
                if token.pos_:
                    feat_dict["POS"] = token.pos_
                if token.tag_:
                    feat_dict["TAG"] = token.tag_
                if token.dep_:
                    feat_dict["DEP"] = token.dep_

                # Head index: spaCy uses token.i (token index in doc), which is 0-based
                # We need to convert to 1-based for dep_head (0 means root)
                head_idx = token.head.i + 1 if token.head.i != token.i else 0

                token_obj = Token(
                    text=token.text,
                    char_start=token.idx,
                    char_end=token.idx + len(token.text),
                    lemma=token.lemma_,
                    upos=token.pos_,
                    xpos=token.tag_,
                    feats=feat_dict,
                    dep_head=head_idx,
                    dep_relation=token.dep_,
                )
                tokens.append(token_obj)

            # NER entities
            for ent in doc.ents:
                entities.append(
                    NerEntity(
                        text=ent.text,
                        label=ent.label_,
                        char_start=ent.start_char,
                        char_end=ent.end_char,
                        normalized_value="",
                        source_engine=self.name,
                    )
                )

            return EngineResult(
                tokens=tokens,
                entities=entities,
                sentences=sentences,
                paragraphs=[],
            )
        except Exception as e:
            logger.exception(f"spaCy engine error: {e}")
            return EngineResult(error=str(e))