from __future__ import annotations

import logging
import time
from collections import deque
from typing import Set

import httpx

from nlp_service.config import AppConfig, load_config
from nlp_service.engines.base import EngineResult, NerEntity, NlpOp

logger = logging.getLogger(__name__)

# NameTag model mapping for supported languages
NAMETAG_MODELS = {
    "cs": "nametag3-czech",
    "en": "english-conll-200831",
}


class NametagEngine:
    """NLP engine implementation using UFAL NameTag HTTP API.

    Supports NER for Czech and English via the public Lindat repository endpoint.
    Rate limited to 5 req/min (configurable).
    """

    def __init__(self, config: AppConfig | None = None):
        self._config = config or load_config()
        self._client: httpx.Client | None = None
        self._rate_queue: deque[float] = deque()

    @property
    def name(self) -> str:
        return "nametag"

    def supported_languages(self) -> Set[str]:
        return {"cs", "en"}

    def supports(self, lang: str, op: NlpOp) -> bool:
        return lang in self.supported_languages() and op == NlpOp.NER

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        """Run NameTag NER on the input text.

        NameTag only supports NER operation, so if other ops are requested
        we return an error.
        """
        if NlpOp.NER not in ops:
            return EngineResult(error="NametagEngine only supports NER operation")

        try:
            # Apply rate limiting
            rate_limit = self._config.engines.nametag.rate_limit_per_minute
            self._apply_rate_limit(rate_limit)

            # Prepare request
            endpoint = self._config.engines.nametag.endpoint
            timeout = self._config.engines.nametag.timeout_seconds
            max_retries = self._config.engines.nametag.max_retries

            model = NAMETAG_MODELS.get(lang)
            if not model:
                return EngineResult(error=f"No NameTag model for language: {lang}")

            data = {
                "data": text,
                "model": model,
                "input": "untokenized",
                "output": "vertical",
            }

            # Make request with retries
            result_text = None
            last_exc: Exception | None = None

            for attempt in range(max_retries + 1):
                try:
                    logger.debug(
                        "NameTag request attempt=%d/%d endpoint=%s lang=%s model=%s text_len=%d",
                        attempt + 1, max_retries + 1, endpoint, lang, model, len(text),
                    )
                    # UFAL's lindat endpoint flipped to HTTPS with a 301
                    # redirect from the legacy URL; follow redirects so the
                    # call resolves regardless of which form is in config.
                    with httpx.Client(timeout=timeout, follow_redirects=True) as client:
                        resp = client.post(endpoint, data=data)

                    logger.debug(
                        "NameTag response status=%d body_len=%d",
                        resp.status_code,
                        len(resp.content),
                    )
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

            # Parse vertical format to extract NER entities
            entities = self._parse_vertical(result_text, text)

            return EngineResult(
                tokens=[],  # NameTag doesn't provide token-level analysis
                entities=entities,
                sentences=[],
                paragraphs=[],
            )

        except Exception as e:
            logger.exception(f"NameTag engine error: {e}")
            return EngineResult(error=str(e))

    def _apply_rate_limit(self, rate_limit: int):
        """Apply in-memory rate limiting.

        Maintains a queue of request timestamps and blocks if too many
        requests have been made within the last minute.
        """
        if rate_limit <= 0:
            return

        now = time.time()
        # Purge timestamps older than 60 seconds
        while self._rate_queue and (now - self._rate_queue[0]) > 60.0:
            self._rate_queue.popleft()

        if len(self._rate_queue) >= rate_limit:
            # Calculate wait time
            oldest = self._rate_queue[0]
            wait_time = 60.0 - (now - oldest)
            if wait_time > 0:
                time.sleep(wait_time)
                # Re-purge after wait
                now = time.time()
                while self._rate_queue and (now - self._rate_queue[0]) > 60.0:
                    self._rate_queue.popleft()

        self._rate_queue.append(time.time())

    def _parse_vertical(self, vertical_text: str, original_text: str) -> list[NerEntity]:
        """Parse NameTag vertical format into NER entities.

        Vertical format: one token per line with word and NE tag
        Example:
            Czech\tO
            Shell\tB-PERS
            UK\tI-PERS
            ,\tO

        B-X = Begin of entity type X
        I-X = Inside entity type X
        O = Outside (no entity)
        """
        entities = []
        if not vertical_text:
            return entities

        lines = vertical_text.strip().split("\n")
        current_entity: NerEntity | None = None

        for line in lines:
            parts = line.split("\t")
            if len(parts) < 2:
                continue

            word = parts[0].strip()
            tag = parts[1].strip()

            if tag == "O":
                if current_entity:
                    entities.append(current_entity)
                    current_entity = None
            elif tag.startswith("B-"):
                # Save previous entity
                if current_entity:
                    entities.append(current_entity)
                # Start new entity
                label = tag[2:]
                current_entity = NerEntity(
                    text=word,
                    label=label,
                    char_start=original_text.find(word) if word in original_text else -1,
                    char_end=0,
                    normalized_value="",
                    source_engine=self.name,
                )
            elif tag.startswith("I-") and current_entity:
                # Continue entity
                current_entity.text += " " + word

        # Don't forget the last entity
        if current_entity:
            entities.append(current_entity)

        # Fix char_end values
        for ent in entities:
            if ent.char_start >= 0:
                ent.char_end = ent.char_start + len(ent.text)

        return entities