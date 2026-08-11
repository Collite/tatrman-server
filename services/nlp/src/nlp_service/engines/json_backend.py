# SPDX-License-Identifier: Apache-2.0
"""Uniform-JSON backends (Stanza, spaCy) — the front-side `NlpEngine` wrapper.

The transport and the response mapping moved to `ttrnlp.client.backends` at
NLS-P3.3 (⚑NLS-D7); what is left here is the part that is genuinely the service's:
the capability table (which languages and ops this engine claims) and the
`NlpEngine` shape `EngineRegistry` builds against.

The backend protocol itself is documented next to the parser that reads it. In
short::

    POST {url}/analyze  {"text", "language", "ops": [..], "model"}
      -> {"tokens": [...], "entities": [...], "sentences": [...], "modelVersion"}
"""

from __future__ import annotations

from typing import Dict, Set

from ttrnlp.client.backends import BackendClient, analyze_uniform_json

from nlp_service.config import BackendConfig
from nlp_service.engines.adapters import backend_spec
from nlp_service.engines.base import EngineResult, NlpOp


class JsonBackendEngine:
    """A front-side client to a uniform-JSON NLP backend."""

    def __init__(
        self,
        name: str,
        backend: BackendConfig,
        capabilities: Dict[str, Set[NlpOp]],
    ):
        self._name = name
        self._backend = backend
        self._capabilities = capabilities
        self._client = BackendClient(backend_spec(backend), name=name)

    @property
    def name(self) -> str:
        return self._name

    @property
    def reported_model_version(self) -> str:
        """Backend-reported model version, learned from responses (S-1)."""
        return self._client.reported_model_version

    def supported_languages(self) -> Set[str]:
        return set(self._capabilities.keys())

    def supports(self, lang: str, op: NlpOp) -> bool:
        return op in self._capabilities.get(lang, set())

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        return analyze_uniform_json(self._client, text, lang, ops)
