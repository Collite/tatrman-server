from __future__ import annotations

import logging
import time
from collections import deque
from typing import Set

import httpx

from kadmos_service.config import AppConfig, load_config
from kadmos_service.engines.base import EngineResult, NlpOp, Token

logger = logging.getLogger(__name__)

# MorphoDiTa model mapping (for potential future use)
MORPHODITA_MODELS = {
    "cs": "czech-pdt",
}


class MorphoditaEngine:
    """NLP engine implementation using UFAL MorphoDiTa HTTP API.

    Supports TOKENIZE, LEMMATIZE, POS_TAG for Czech via the public Lindat
    repository endpoint. Rate limited (configurable).
    """

    def __init__(self, config: AppConfig | None = None):
        self._config = config or load_config()
        self._client: httpx.Client | None = None
        self._rate_queue: deque[float] = deque()

    @property
    def name(self) -> str:
        return "morphodita"

    def supported_languages(self) -> Set[str]:
        return {"cs"}

    def supports(self, lang: str, op: NlpOp) -> bool:
        # MorphoDiTa's /api/tag with `output=vertical` returns one token per
        # line with `word\tlemma\ttag`, sentences separated by blank lines —
        # so SENTENCE_SPLIT comes for free with TOKENIZE.
        supported_ops = {NlpOp.TOKENIZE, NlpOp.SENTENCE_SPLIT, NlpOp.LEMMATIZE, NlpOp.POS_TAG}
        return lang in self.supported_languages() and op in supported_ops

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        """Run MorphoDiTa analysis on the input text.

        MorphoDiTa provides tokenization, lemmatization, and POS tagging
        for Czech. It does not support NER or dependency parsing.
        """
        if not ops.intersection({NlpOp.TOKENIZE, NlpOp.LEMMATIZE, NlpOp.POS_TAG}):
            return EngineResult(error="MorphoDiTa only supports TOKENIZE, LEMMATIZE, POS_TAG")

        try:
            # Apply rate limiting
            rate_limit = self._config.engines.morphodita.rate_limit_per_minute
            self._apply_rate_limit(rate_limit)

            # Prepare request
            endpoint = self._config.engines.morphodita.endpoint
            timeout = self._config.engines.morphodita.timeout_seconds
            max_retries = self._config.engines.morphodita.max_retries

            # MorphoDiTa accepts raw text with specific format
            data = {
                "data": text,
                # Empty `model` lets MorphoDiTa pick the current default
                # Czech model (currently czech-morfflex2.1-pdtc2.0-…). Hard-
                # coding "czech-pdt" pinned us to a model that may not be
                # served anymore.
                "input": "untokenized",
                "output": "vertical",
            }

            # Make request with retries
            result_text = None
            last_exc: Exception | None = None

            for attempt in range(max_retries + 1):
                try:
                    logger.debug(
                        "MorphoDiTa request attempt=%d/%d endpoint=%s lang=%s text_len=%d",
                        attempt + 1, max_retries + 1, endpoint, lang, len(text),
                    )
                    # Lindat 301-redirects /services/morphodita → static landing
                    # page; the real API lives at /api/tag. follow_redirects is
                    # belt-and-braces in case anyone keeps the legacy URL in
                    # config.
                    with httpx.Client(timeout=timeout, follow_redirects=True) as client:
                        resp = client.post(endpoint, data=data)

                    logger.debug(
                        "MorphoDiTa response status=%d body_len=%d",
                        resp.status_code, len(resp.content),
                    )
                    if resp.status_code != 200:
                        last_exc = Exception(f"HTTP {resp.status_code}")
                        time.sleep(0.1 * (attempt + 1))
                        continue

                    payload = resp.json()
                    result_text = payload.get("result", "")
                    if not isinstance(result_text, str):
                        raise Exception("Missing 'result' in MorphoDiTa response")
                    break

                except (httpx.TimeoutException, httpx.NetworkError) as e:
                    last_exc = e
                    time.sleep(0.1 * (attempt + 1))
                    continue

            if result_text is None:
                return EngineResult(error=f"MorphoDiTa request failed: {last_exc}")

            # Parse vertical format to extract tokens with lemmas and POS
            tokens, sentences = self._parse_vertical(result_text, text)

            return EngineResult(
                tokens=tokens,
                entities=[],
                sentences=sentences,
                paragraphs=[],
            )

        except Exception as e:
            logger.exception(f"MorphoDiTa engine error: {e}")
            return EngineResult(error=str(e))

    def _apply_rate_limit(self, rate_limit: int):
        """Apply in-memory rate limiting.

        Maintains a queue of request timestamps and blocks if too many
        requests have been made within the last minute.
        """
        if rate_limit <= 0:
            return

        now = time.time()
        while self._rate_queue and (now - self._rate_queue[0]) > 60.0:
            self._rate_queue.popleft()

        if len(self._rate_queue) >= rate_limit:
            oldest = self._rate_queue[0]
            wait_time = 60.0 - (now - oldest)
            if wait_time > 0:
                time.sleep(wait_time)
                now = time.time()
                while self._rate_queue and (now - self._rate_queue[0]) > 60.0:
                    self._rate_queue.popleft()

        self._rate_queue.append(time.time())

    def _parse_vertical(
        self, vertical_text: str, original_text: str
    ) -> tuple[list[Token], list[tuple[int, int]]]:
        """Parse MorphoDiTa vertical format into tokens + sentence spans.

        MorphoDiTa /api/tag with output=vertical returns one token per line as
        `word\tlemma\ttag`, with BLANK LINES between sentences. The previous
        implementation inferred sentence boundaries from `.`/`!`/`?` tokens,
        which broke whenever MorphoDiTa's actual sentence-splitter disagreed
        (e.g. on abbreviations or numeric punctuation like "2026.03"). Trust
        the server's segmentation by walking the blank-line separators.

        `tag` is a 15-char PDT positional tag; the first character is the
        major POS class (N=Noun, V=Verb, A=Adj, R=preposition, …) — see
        https://wiki.korpus.cz/doku.php/seznamy:tagy.
        """
        tokens: list[Token] = []
        sentences: list[tuple[int, int]] = []

        if not vertical_text:
            return tokens, sentences

        # Walk character offsets in the source so we get true char_start even
        # when the same token appears multiple times in the text.
        cursor = 0
        current_sentence_tokens: list[Token] = []

        for line in vertical_text.split("\n"):
            if line == "":
                # Blank line = end of sentence (per /api/tag vertical format).
                if current_sentence_tokens:
                    sent_start = current_sentence_tokens[0].char_start
                    sent_end = current_sentence_tokens[-1].char_end
                    sentences.append((sent_start, sent_end))
                    current_sentence_tokens = []
                continue

            parts = line.split("\t")
            word = parts[0]
            lemma = parts[1] if len(parts) > 1 else word
            xpos = parts[2] if len(parts) > 2 else ""
            # PDT lemmas carry _;X / -N suffixes (sense annotations) — strip
            # the meta part so downstream consumers see the bare lemma.
            if "_" in lemma:
                lemma = lemma.split("_", 1)[0]
            elif "-" in lemma and lemma[0].isalpha():
                lemma = lemma.split("-", 1)[0]
            upos = self._pdt_first_char_to_ud_pos(xpos)

            # Locate the token in the source starting from the running cursor;
            # MorphoDiTa returns tokens in source order so cursor only moves
            # forward.
            found = original_text.find(word, cursor)
            if found >= 0:
                char_start = found
                char_end = char_start + len(word)
                cursor = char_end
            else:
                char_start = -1
                char_end = 0

            token = Token(
                text=word,
                char_start=char_start,
                char_end=char_end,
                lemma=lemma,
                upos=upos,
                xpos=xpos,
                feats={},
                dep_head=0,
                dep_relation="",
            )
            tokens.append(token)
            current_sentence_tokens.append(token)

        # Tail sentence (no trailing blank line).
        if current_sentence_tokens:
            sentences.append(
                (
                    current_sentence_tokens[0].char_start,
                    current_sentence_tokens[-1].char_end,
                )
            )
        return tokens, sentences

    def _pdt_first_char_to_ud_pos(self, pdt_tag: str) -> str:
        """Map the *first character* of a PDT positional tag to a UD POS.

        MorphoDiTa returns 15-character PDT positional tags (e.g.
        ``NNMS1-----A----`` = noun common masculine singular nominative).
        The first character is the major POS class; the rest encodes
        morphology. The old lookup table was keyed on Penn-Treebank-style
        short tags (NN, VBZ, …) and never matched, so every Czech token
        ended up as ``X`` — which made golem's proposeDomainSpans drop
        every span (only NOUN/PROPN survive).
        """
        if not pdt_tag:
            return ""
        # PDT major POS classes per
        # https://wiki.korpus.cz/doku.php/seznamy:tagy
        major = pdt_tag[0]
        if major == "N":
            # NN* = common, NP* = proper. The second char is the detailed
            # noun type; for proper-noun detection check char[1].
            if len(pdt_tag) > 1 and pdt_tag[1] == "P":
                return "PROPN"
            return "NOUN"
        return {
            "A": "ADJ",
            "C": "NUM",
            "D": "ADV",  # pronominal adverbs
            "I": "INTJ",
            "J": "CCONJ",
            "P": "PRON",
            "R": "ADP",  # preposition
            "T": "PART",
            "V": "VERB",
            "X": "X",  # unknown / foreign
            "Z": "PUNCT",
        }.get(major, "X")
