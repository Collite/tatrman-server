# SPDX-License-Identifier: Apache-2.0
"""The annotation model (NL-2).

Public surface (contracts §6):

    Document                    re-export of the pinned gatenlp Document
    AnnotationSet               re-export of the pinned gatenlp AnnotationSet
    build_document(...)         engine JSON -> Document
    doc_to_proto / doc_from_proto   Document <-> proto `AnnotatedDocument`

`serialize.py` needs the `[grpc]` extra and the generated `org.tatrman.nlp.v1`
stubs, so its two functions are resolved on first use rather than imported here —
a consumer who installed the core wheel for the rule engine should not have
`import ttrnlp.doc` fail over a client they are not using. bdocjson stays
internal/debug-only — never a wire format (S-3).
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from ttrnlp.doc.importers import build_document
from ttrnlp.doc.model import AnnotationSet, Document

if TYPE_CHECKING:  # pragma: no cover
    from ttrnlp.doc.serialize import doc_from_proto as doc_from_proto
    from ttrnlp.doc.serialize import doc_to_proto as doc_to_proto

__all__ = [
    "AnnotationSet",
    "Document",
    "build_document",
    "doc_from_proto",
    "doc_to_proto",
]

_LAZY = {"doc_to_proto", "doc_from_proto"}


def __getattr__(name: str) -> Any:
    if name not in _LAZY:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    from ttrnlp.doc import serialize

    return getattr(serialize, name)


def __dir__() -> list[str]:
    return __all__
