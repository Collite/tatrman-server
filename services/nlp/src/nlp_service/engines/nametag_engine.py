# SPDX-License-Identifier: Apache-2.0
"""NameTag 3 — the front-side `NlpEngine` wrapper.

The `/recognize` CoNLL protocol, the BIO accumulation and the CNEC→universal
mapping moved to `ttrnlp.client.backends` / `ttrnlp.doc.labels` at NLS-P3.3
(⚑NLS-D7) — the mapping table had been living in two places, here and in the
wheel's importers, and one of them was going to drift.

Serves cs/en NER. cs NER routes here because Stanza's cs bundle has no NER head,
which is also why the default lane (Stanza-only) cannot serve it at all and
degrades explicitly instead (NL-14). The engine name is `nametag3` — the
capability matrix and the contracts both spell it that way.
"""

from __future__ import annotations

from typing import Set

from ttrnlp.client.backends import BackendClient, analyze_nametag

from nlp_service.config import BackendConfig
from nlp_service.engines.adapters import backend_spec
from nlp_service.engines.base import EngineResult, NlpOp


class Nametag3Engine:
    def __init__(self, backend: BackendConfig):
        self._backend = backend
        self._client = BackendClient(backend_spec(backend), name="nametag3")

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
        return analyze_nametag(self._client, text)
