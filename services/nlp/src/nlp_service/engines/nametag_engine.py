# SPDX-License-Identifier: Apache-2.0
"""NameTag 3 engine — front-side HTTP adapter (RG-P1.S1).

Speaks NameTag's native `/recognize` BIO/vertical protocol. Backend is the
self-hosted `nametag3_server.py` (RG-P1.S2, `SELF_HOSTED_PINNED`) or Lindat
(dev/eval, `REMOTE_UNPINNED`). Always sends an **explicit model id**
(`backend.model`, S-1). Serves cs/en NER (cs NER routes here because Stanza's cs
bundle has no NER head). Engine name is `nametag3` (contracts / capability
matrix).
"""

from __future__ import annotations

import logging
import time
from collections import deque
from typing import Set

import httpx

from nlp_service.config import BackendConfig
from nlp_service.engines.base import EngineResult, NerEntity, NlpOp

logger = logging.getLogger(__name__)

# CNEC 2.0 leading-class-letter → universal entity type. The tagset is
# fine-grained (gu=city, gc=country, pf=firstname, ps=surname, if=firm/inst,
# oa=artifact/product, th=time-interval, …); the resolver's universal path wants
# coarse classes. Map by the FIRST letter (case-insensitive); anything unmapped
# collapses to MISC so an entity is NEVER silently dropped (the leading-letter
# brittleness this guards). Boundary note: products (`o*`) surface as MISC — the
# resolver's DOMAIN path (fuzzy over declared vocabulary), not this NER label,
# is what actually resolves product names like "Octavie".
_CNEC_CLASS_TO_UNIVERSAL = {
    "p": "PERSON",       # personal names
    "g": "LOCATION",     # geographical
    "i": "ORGANIZATION", # institutions
    "t": "DATE",         # time expressions
}


def _cnec_to_universal(cnec_tag: str) -> str:
    if not cnec_tag:
        return "MISC"
    return _CNEC_CLASS_TO_UNIVERSAL.get(cnec_tag[0].lower(), "MISC")


class Nametag3Engine:
    def __init__(self, backend: BackendConfig):
        self._backend = backend
        self._rate_queue: deque[float] = deque()

    @property
    def name(self) -> str:
        return "nametag3"

    def supported_languages(self) -> Set[str]:
        return {"cs", "en"}

    def supports(self, lang: str, op: NlpOp) -> bool:
        return lang in self.supported_languages() and op == NlpOp.NER

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        if NlpOp.NER not in ops:
            return EngineResult(error="Nametag3Engine only supports NER operation")

        model = self._backend.model
        if not model:
            # S-1: an enabled NER route with no explicit model is a config error.
            return EngineResult(error="NameTag 3 has no explicit model id (RG-NLP-003)")

        try:
            self._apply_rate_limit(self._backend.rate_limit_per_minute)
            endpoint = self._backend.url
            timeout = self._backend.timeout_seconds
            max_retries = self._backend.max_retries

            data = {
                "data": text,
                "model": model,
                "input": "untokenized",
                # output=conll gives the BIO `word\tB-/I-/O` form this adapter
                # parses. (output=vertical is a different `idx\ttype\ttext` shape —
                # NOT what _parse_vertical expects.)
                "output": "conll",
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
                        raise Exception("Missing 'result' in NameTag response")
                    break
                except (httpx.TimeoutException, httpx.NetworkError) as e:
                    last_exc = e
                    time.sleep(0.1 * (attempt + 1))
                    continue

            if result_text is None:
                return EngineResult(error=f"NameTag request failed: {last_exc}")

            entities = self._parse_vertical(result_text, text)
            return EngineResult(tokens=[], entities=entities, sentences=[], paragraphs=[])

        except Exception as e:
            logger.exception(f"NameTag engine error: {e}")
            return EngineResult(error=str(e))

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

    def _parse_vertical(self, vertical_text: str, original_text: str) -> list[NerEntity]:
        """Parse NameTag vertical (`word\\tB-/I-/O` tags) into NER entities.

        `NerEntity` is frozen, so accumulate each entity's words/tag in locals
        and build it once at close (a B-/O boundary or EOF). The CNEC class maps
        to a universal label; the raw CNEC tag is preserved in normalized_value.
        """
        entities: list[NerEntity] = []
        if not vertical_text:
            return entities

        words: list[str] = []
        cnec = ""
        start = -1
        cursor = 0

        def _flush() -> None:
            nonlocal words, cnec, start
            if not words:
                return
            text = " ".join(words)
            entities.append(
                NerEntity(
                    text=text,
                    label=_cnec_to_universal(cnec),
                    char_start=start,
                    char_end=(start + len(text)) if start >= 0 else 0,
                    normalized_value=f"cnec:{cnec}",
                    source_engine=self.name,
                )
            )
            words, cnec, start = [], "", -1

        for line in vertical_text.strip().split("\n"):
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            word = parts[0].strip()
            tag = parts[1].strip()

            if tag == "O":
                _flush()
            elif tag.startswith("B-"):
                _flush()
                cnec = tag[2:]
                found = original_text.find(word, cursor)
                start = found
                if found >= 0:
                    cursor = found + len(word)
                words = [word]
            elif tag.startswith("I-") and words:
                words.append(word)
                found = original_text.find(word, cursor)
                if found >= 0:
                    cursor = found + len(word)

        _flush()
        return entities
