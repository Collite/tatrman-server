# SPDX-License-Identifier: Apache-2.0
"""MorphoDiTa engine — front-side HTTP adapter (RG-P1.S1).

Speaks MorphoDiTa's native `/tag` vertical protocol. The backend is either the
self-hosted `morphodita_server` (RG-P1.S2, `SELF_HOSTED_PINNED`) or Lindat
(dev/eval, `REMOTE_UNPINNED`). The adapter always sends an **explicit model id**
(`backend.model`, S-1) — killing the empty-`model` default-picking bug the live
system shipped. Serves cs TOKENIZE/SENTENCE_SPLIT/LEMMATIZE/POS_TAG.
"""

from __future__ import annotations

import logging
import time
from collections import deque
from typing import Set

import httpx

from nlp_service.config import BackendConfig
from nlp_service.engines.base import EngineResult, NlpOp, Token

logger = logging.getLogger(__name__)

_SUPPORTED_OPS = {NlpOp.TOKENIZE, NlpOp.SENTENCE_SPLIT, NlpOp.LEMMATIZE, NlpOp.POS_TAG}


class MorphoditaEngine:
    def __init__(self, backend: BackendConfig):
        self._backend = backend
        self._rate_queue: deque[float] = deque()

    @property
    def name(self) -> str:
        return "morphodita"

    def supported_languages(self) -> Set[str]:
        return {"cs"}

    def supports(self, lang: str, op: NlpOp) -> bool:
        return lang in self.supported_languages() and op in _SUPPORTED_OPS

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        if not ops.intersection({NlpOp.TOKENIZE, NlpOp.SENTENCE_SPLIT, NlpOp.LEMMATIZE, NlpOp.POS_TAG}):
            return EngineResult(error="MorphoDiTa only supports TOKENIZE, SENTENCE_SPLIT, LEMMATIZE, POS_TAG")

        try:
            self._apply_rate_limit(self._backend.rate_limit_per_minute)
            endpoint = self._backend.url
            timeout = self._backend.timeout_seconds
            max_retries = self._backend.max_retries

            data = {
                "data": text,
                # S-1: explicit model id — never blank (the default-picking bug
                # class this phase kills). Single-model self-hosted servers
                # ignore it; Lindat selects on it.
                "model": self._backend.model,
                "input": "untokenized",
                "output": "vertical",
            }

            result_text = None
            last_exc: Exception | None = None
            for attempt in range(max_retries + 1):
                try:
                    with httpx.Client(timeout=timeout, follow_redirects=True) as client:
                        resp = client.post(endpoint, data=data)
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

            tokens, sentences = self._parse_vertical(result_text, text)
            return EngineResult(tokens=tokens, entities=[], sentences=sentences, paragraphs=[])

        except Exception as e:
            logger.exception(f"MorphoDiTa engine error: {e}")
            return EngineResult(error=str(e))

    def batch_lemmatize(self, texts: list[str], lang: str) -> list[list[str]]:
        """Batched lemmatization at the backend hop (RG-P1.S3 / Q-10 §4).

        Newline-joins `texts` into ONE `/tag` pass (blank-line sentence breaks),
        so N strings cost one round-trip instead of N. Returns positional lemma
        lists. The front is responsible for chunking to the backend's
        `--max_request_size`; this issues a single call per chunk.
        """
        if not texts:
            return []
        # One string per "sentence": join with a blank line so MorphoDiTa's
        # vertical output segments them back apart on blank lines.
        joined = "\n\n".join(t.replace("\n", " ") for t in texts)
        self._apply_rate_limit(self._backend.rate_limit_per_minute)
        data = {
            "data": joined,
            "model": self._backend.model,
            "input": "untokenized",
            "output": "vertical",
        }
        with httpx.Client(timeout=self._backend.timeout_seconds, follow_redirects=True) as client:
            resp = client.post(self._backend.url, data=data)
        resp.raise_for_status()
        result_text = resp.json().get("result", "")
        return self._parse_vertical_lemma_groups(result_text, len(texts))

    def _apply_rate_limit(self, rate_limit: int):
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
        """Parse MorphoDiTa vertical (`word\\tlemma\\ttag`, blank-line sentence
        breaks) into tokens + sentence spans."""
        tokens: list[Token] = []
        sentences: list[tuple[int, int]] = []
        if not vertical_text:
            return tokens, sentences

        cursor = 0
        current_sentence_tokens: list[Token] = []
        for line in vertical_text.split("\n"):
            if line == "":
                if current_sentence_tokens:
                    sentences.append(
                        (current_sentence_tokens[0].char_start, current_sentence_tokens[-1].char_end)
                    )
                    current_sentence_tokens = []
                continue

            parts = line.split("\t")
            word = parts[0]
            lemma = parts[1] if len(parts) > 1 else word
            xpos = parts[2] if len(parts) > 2 else ""
            if "_" in lemma:
                lemma = lemma.split("_", 1)[0]
            elif "-" in lemma and lemma[0].isalpha():
                lemma = lemma.split("-", 1)[0]
            upos = self._pdt_first_char_to_ud_pos(xpos)

            found = original_text.find(word, cursor)
            if found >= 0:
                char_start = found
                char_end = char_start + len(word)
                cursor = char_end
            else:
                char_start = -1
                char_end = 0

            token = Token(
                text=word, char_start=char_start, char_end=char_end,
                lemma=lemma, upos=upos, xpos=xpos, feats={}, dep_head=0, dep_relation="",
            )
            tokens.append(token)
            current_sentence_tokens.append(token)

        if current_sentence_tokens:
            sentences.append(
                (current_sentence_tokens[0].char_start, current_sentence_tokens[-1].char_end)
            )
        return tokens, sentences

    def _parse_vertical_lemma_groups(self, vertical_text: str, n: int) -> list[list[str]]:
        """Split vertical output into `n` positional lemma lists on blank lines."""
        groups: list[list[str]] = []
        current: list[str] = []
        for line in vertical_text.split("\n"):
            if line == "":
                if current:
                    groups.append(current)
                    current = []
                continue
            parts = line.split("\t")
            lemma = parts[1] if len(parts) > 1 else parts[0]
            if "_" in lemma:
                lemma = lemma.split("_", 1)[0]
            elif "-" in lemma and lemma[0].isalpha():
                lemma = lemma.split("-", 1)[0]
            current.append(lemma)
        if current:
            groups.append(current)
        # Pad/truncate defensively so the result is positional to the inputs.
        while len(groups) < n:
            groups.append([])
        return groups[:n]

    def _pdt_first_char_to_ud_pos(self, pdt_tag: str) -> str:
        if not pdt_tag:
            return ""
        major = pdt_tag[0]
        if major == "N":
            if len(pdt_tag) > 1 and pdt_tag[1] == "P":
                return "PROPN"
            return "NOUN"
        return {
            "A": "ADJ", "C": "NUM", "D": "ADV", "I": "INTJ", "J": "CCONJ",
            "P": "PRON", "R": "ADP", "T": "PART", "V": "VERB", "X": "X", "Z": "PUNCT",
        }.get(major, "X")
