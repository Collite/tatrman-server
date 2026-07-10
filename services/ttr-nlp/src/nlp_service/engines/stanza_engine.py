from __future__ import annotations

import logging
from typing import Set

import stanza
from stanza.pipeline.core import Pipeline as StanzaPipeline

from nlp_service.config import AppConfig, load_config
from nlp_service.engines.base import EngineResult, NerEntity, NlpOp, Token

logger = logging.getLogger(__name__)


class StanzaEngine:
    """NLP engine implementation using Stanza."""

    def __init__(self, config: AppConfig | None = None):
        self._config = config or load_config()
        self._pipeline: StanzaPipeline | None = None
        self._model_dir = self._config.engines.stanza.model_dir

    @property
    def name(self) -> str:
        return "stanza"

    def supported_languages(self) -> Set[str]:
        return {"cs", "en"}

    def supports(self, lang: str, op: NlpOp) -> bool:
        # Stanza's Czech model bundle does NOT ship the NER processor (only
        # tokenize/mwt/pos/lemma/depparse). Loading a `processors="…,ner"`
        # pipeline for cs raises UnsupportedProcessorError at init and breaks
        # every other op too. Route cs NER to nametag and keep stanza for the
        # rest. EN still gets full NER from Stanza.
        if lang == "cs" and op == NlpOp.NER:
            return False
        supported_ops = {NlpOp.TOKENIZE, NlpOp.LEMMATIZE, NlpOp.POS_TAG, NlpOp.DEP_PARSE, NlpOp.NER}
        return lang in self.supported_languages() and op in supported_ops

    def _get_pipeline(self, lang: str) -> StanzaPipeline:
        """Get or create a Stanza pipeline for the given language."""
        if self._pipeline is None:
            import os as _os

            logger.debug(
                "Stanza: initialising pipeline lang=%s model_dir=%s", lang, self._model_dir,
            )
            # Models are pre-baked into the Docker image; only call download()
            # when the lang subdir is missing (e.g. local dev). Calling it
            # otherwise tries to write a lock/tmp file under model_dir which
            # 13-EACCESes when the dir is root-owned at runtime.
            lang_dir = _os.path.join(self._model_dir or "", lang)
            if not _os.path.isdir(lang_dir):
                logger.info(
                    "Stanza: model dir %s missing — attempting download", lang_dir,
                )
                try:
                    stanza.download(lang, model_dir=self._model_dir)
                except Exception as e:
                    logger.warning(
                        "Stanza download failed (continuing — assuming pre-baked models): %s", e,
                    )
            else:
                logger.debug("Stanza: model dir %s present — skipping download", lang_dir)
            # Drop "ner" for cs — the model isn't bundled (default.pt missing)
            # and the pipeline would fail to init even for unrelated ops.
            processors = (
                "tokenize,mwt,lemma,pos,depparse" if lang == "cs"
                else "tokenize,lemma,pos,depparse,ner"
            )
            logger.info("Stanza Pipeline init lang=%s processors=%s", lang, processors)
            self._pipeline = stanza.Pipeline(
                lang=lang,
                model_dir=self._model_dir,
                processors=processors,
                # Pipeline still accepts `quiet`; only download() dropped it.
                quiet=True,
            )
            logger.info("Stanza pipeline ready for lang=%s", lang)
        return self._pipeline

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        """Run Stanza analysis on the input text."""
        try:
            pipeline = self._get_pipeline(lang)
            doc = pipeline(text)

            tokens: list[Token] = []
            entities: list[NerEntity] = []
            sentences: list[tuple[int, int]] = []

            for sentence in doc.sentences:
                # Stanza's Sentence object doesn't expose `char_start`/
                # `char_end` directly — derive from the first/last token's
                # char offsets. `sentence.tokens` is non-empty for any
                # non-empty input sentence; guard defensively anyway.
                if sentence.tokens:
                    sent_start = sentence.tokens[0].start_char
                    sent_end = sentence.tokens[-1].end_char
                    sentences.append((sent_start, sent_end))

                for word in sentence.words:
                    # Token-level analysis
                    token = Token(
                        text=word.text,
                        char_start=word.start_char,
                        char_end=word.end_char,
                        lemma=word.lemma or "",
                        upos=word.upos or "",
                        xpos=word.xpos or "",
                        feats=_parse_feats(word.feats),
                        dep_head=word.head if word.head else 0,
                        dep_relation=word.deprel or "",
                    )
                    tokens.append(token)

                # NER entities
                for ent in sentence.entities:
                    entities.append(
                        NerEntity(
                            text=ent.text,
                            label=ent.type,
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
                paragraphs=[],  # Stanza doesn't have paragraph detection by default
            )
        except Exception as e:
            logger.exception(f"Stanza engine error: {e}")
            return EngineResult(error=str(e))


def _parse_feats(feats_str: str | None) -> dict[str, str]:
    """Parse Stanza morphological features string into a dict.

    Stanza uses UPOS pipe-delimited format like 'Person=Masc|Polarity=Pos'.
    """
    if not feats_str:
        return {}
    feats = {}
    for part in feats_str.split("|"):
        if "=" in part:
            key, value = part.split("=", 1)
            feats[key] = value
    return feats