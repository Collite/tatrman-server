# SPDX-License-Identifier: Apache-2.0
"""The engine protocol, and the value types it deals in.

NLS-P3.3 (⚑NLS-D7) moved the HTTP adapters into `ttrnlp.client.backends`, and the
value types went with them — a `Token` is what a backend reports, not something
this service invented, and the wheel's consumers need it as much as the front
does. They are re-exported here so every call site in the service keeps reading
`from nlp_service.engines.base import Token`: the move is about where code lives,
not about churning two hundred imports.

What stays here is what the *service* owns: the `NlpEngine` protocol that
`EngineRegistry` builds against. Routing (which backend serves a (language, op)),
the registry, the orchestrator and `langid` are all service-side by design — the
division is "how to talk to one backend" versus "which one to talk to", and the
second is a deployment's business.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, Set, runtime_checkable

from ttrnlp.client.backends import (
    EngineResult as EngineResult,
)
from ttrnlp.client.backends import (
    NerEntity as NerEntity,
)
from ttrnlp.client.backends import (
    NlpOp as NlpOp,
)
from ttrnlp.client.backends import (
    Token as Token,
)


@dataclass(frozen=True)
class EngineVersion:
    """S-1 model-identity echo: which engine+model served an op.

    Mirrors the proto `org.tatrman.nlp.v1.EngineVersion`. Populated on every
    model-touched response's `used[]` (never a blank `model`).

    Stayed service-side while the other value types moved, and the reason is the
    S-1 contract itself: an `EngineVersion` records what a *route* resolved to,
    and routes are what this service owns. A backend client knows which model it
    was told to ask for; it does not know that this was the op it served on this
    request.
    """

    op: str
    engine: str
    model: str
    model_version: str


@runtime_checkable
class NlpEngine(Protocol):
    """Plugin interface for NLP engines.

    Implement this protocol to add a new NLP engine (Stanza, spaCy, NameTag, etc.)
    """

    @property
    def name(self) -> str:
        """Engine name, e.g., 'stanza', 'spacy', 'nametag', 'langid'."""
        ...

    def supported_languages(self) -> Set[str]:
        """Return set of supported language codes (e.g., {'cs', 'en'})."""
        ...

    def supports(self, lang: str, op: NlpOp) -> bool:
        """Check if this engine supports the given language and operation."""
        ...

    def analyze(
        self,
        text: str,
        lang: str,
        ops: Set[NlpOp],
    ) -> EngineResult:
        """Run the requested operations on the given text.

        Args:
            text: Input text to analyze
            lang: Language code (e.g., 'cs', 'en')
            ops: Set of requested operations

        Returns:
            EngineResult with tokens, entities, sentences, paragraphs, and optional error

        Note:
            Engines should run all requested operations in a single pass when possible
            to avoid redundant tokenization/parsing.
        """
        ...


__all__ = [
    "EngineResult",
    "EngineVersion",
    "NerEntity",
    "NlpEngine",
    "NlpOp",
    "Token",
]
