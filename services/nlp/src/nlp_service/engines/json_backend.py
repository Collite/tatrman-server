# SPDX-License-Identifier: Apache-2.0
"""HTTP-adapter engine for self-hosted backends that speak the uniform JSON
contract (Stanza, spaCy).

RG-P1.S1 makes the front **engine-free**: Stanza/spaCy no longer run in-process
(no `import stanza` / `import spacy`, no torch). Each becomes its own backend
image exposing:

    POST {url}/analyze  {"text", "language", "ops": [..]}
      -> {"tokens": [{text,charStart,charEnd,lemma,upos,xpos,feats,depHead,depRelation}],
          "entities": [{text,label,charStart,charEnd,normalizedValue,sourceEngine}],
          "sentences": [{charStart,charEnd}],
          "modelVersion": "..."}

The backend images land in RG-P1.S3; this adapter is the front-side client and
is exercised in tests with a mocked transport. `model_version` echoed by the
backend feeds the S-1 `used[]` stamp.
"""

from __future__ import annotations

import logging
import time
from typing import Dict, Set

import httpx

from nlp_service.config import BackendConfig
from nlp_service.engines.base import EngineResult, NerEntity, NlpOp, Token

logger = logging.getLogger(__name__)


class JsonBackendEngine:
    """A front-side HTTP client to a uniform-JSON NLP backend."""

    def __init__(
        self,
        name: str,
        backend: BackendConfig,
        capabilities: Dict[str, Set[NlpOp]],
    ):
        self._name = name
        self._backend = backend
        self._capabilities = capabilities
        # Backend-reported model version, learned from the last response (S-1).
        self.reported_model_version: str = backend.model_version

    @property
    def name(self) -> str:
        return self._name

    def supported_languages(self) -> Set[str]:
        return set(self._capabilities.keys())

    def supports(self, lang: str, op: NlpOp) -> bool:
        return op in self._capabilities.get(lang, set())

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        url = self._backend.url.rstrip("/") + "/analyze"
        payload = {
            "text": text,
            "language": lang,
            "ops": sorted(o.value for o in ops),
            # S-1: name the model explicitly; the backend serves exactly this.
            "model": self._backend.model,
        }
        timeout = self._backend.timeout_seconds
        max_retries = self._backend.max_retries

        last_exc: Exception | None = None
        for attempt in range(max_retries + 1):
            try:
                with httpx.Client(timeout=timeout) as client:
                    resp = client.post(url, json=payload)
                if resp.status_code != 200:
                    last_exc = Exception(f"HTTP {resp.status_code}")
                    time.sleep(0.1 * (attempt + 1))
                    continue
                return self._parse(resp.json())
            except (httpx.TimeoutException, httpx.NetworkError) as e:
                last_exc = e
                time.sleep(0.1 * (attempt + 1))
                continue
            except Exception as e:  # noqa: BLE001 — surface as engine error
                logger.exception("%s backend error: %s", self._name, e)
                return EngineResult(error=str(e))
        return EngineResult(error=f"{self._name} backend request failed: {last_exc}")

    def _parse(self, body: dict) -> EngineResult:
        if self._backend.model and body.get("modelVersion"):
            self.reported_model_version = str(body["modelVersion"])
        tokens = [
            Token(
                text=t.get("text", ""),
                char_start=int(t.get("charStart", -1)),
                char_end=int(t.get("charEnd", 0)),
                lemma=t.get("lemma", ""),
                upos=t.get("upos", ""),
                xpos=t.get("xpos", ""),
                feats=dict(t.get("feats", {}) or {}),
                dep_head=int(t.get("depHead", 0)),
                dep_relation=t.get("depRelation", ""),
            )
            for t in body.get("tokens", [])
        ]
        entities = [
            NerEntity(
                text=e.get("text", ""),
                label=e.get("label", ""),
                char_start=int(e.get("charStart", -1)),
                char_end=int(e.get("charEnd", 0)),
                normalized_value=e.get("normalizedValue", ""),
                source_engine=e.get("sourceEngine", "") or self._name,
            )
            for e in body.get("entities", [])
        ]
        sentences = [
            (int(s.get("charStart", 0)), int(s.get("charEnd", 0)))
            for s in body.get("sentences", [])
        ]
        return EngineResult(tokens=tokens, entities=entities, sentences=sentences, paragraphs=[])
