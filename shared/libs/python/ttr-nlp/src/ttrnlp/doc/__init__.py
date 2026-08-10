# SPDX-License-Identifier: Apache-2.0
"""The annotation model (NL-2).

Public surface (contracts §6):

    Document                    re-export of the pinned gatenlp Document
    AnnotationSet               re-export of the pinned gatenlp AnnotationSet
    build_document(...)         engine JSON -> Document

`serialize.py` (Document <-> proto `AnnotatedDocument`) lands at NLS-P3, when the
proto additions exist. bdocjson stays internal/debug-only — never a wire format
(S-3).
"""

from __future__ import annotations

from ttrnlp.doc.importers import build_document
from ttrnlp.doc.model import AnnotationSet, Document

__all__ = ["AnnotationSet", "Document", "build_document"]
