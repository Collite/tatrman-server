# SPDX-License-Identifier: Apache-2.0
"""`ttrnlp.morph` — the Czech morphology runtime (LM, architecture §2).

Query-time morphology: a tokenizer, a snapshot of curated analyses, an
annotator that writes them onto tokens, and a gazetteer that matches on lemmas
instead of surface forms. Everything here is a **lookup** — the paradigm engine,
the importers and the editorial loop live in `ttr-morph` and never run at query
time (P-1: deterministic core).

    tokenize      own rule tokenizer, cs profile as data (LM-9)
    records       Analysis / LookupResult / Generated + THE fold (contracts §1/§4)
    snapshot      morph/v* artifact + world overlay loader, fail-all (NL-15)
    annotate      token features <- ranked analyses, provenance-marked
    chain         lexicon -> statistical seam (unwired in v1) -> fold/stem guess
    gazetteer     match-if-any Lookup annotation over lemma lists (LM-8)
    helpers       lemma_any(...) etc — PAMPAC feature matchers for rules

**Zero new dependencies** (architecture §2). The snapshot is parsed with the
stdlib and folding is `unicodedata`; the whole module runs in-process inside the
engine-free `nlp` front, and `tests/morph/test_guards.py` keeps it that way.
"""

from __future__ import annotations

from ttrnlp.morph.records import (
    MATCHED_EXACT,
    MATCHED_FOLDED,
    PROVENANCE_LEXICON,
    PROVENANCE_PROVISIONAL,
    PROVENANCE_STATISTICAL,
    Analysis,
    Generated,
    LookupResult,
    fold,
)
from ttrnlp.morph.snapshot import (
    LoadError,
    MorphManifest,
    MorphState,
    MorphStats,
    load_morph,
)
from ttrnlp.morph.tokenize import Token, tokenize

__all__ = [
    "MATCHED_EXACT",
    "MATCHED_FOLDED",
    "PROVENANCE_LEXICON",
    "PROVENANCE_PROVISIONAL",
    "PROVENANCE_STATISTICAL",
    "Analysis",
    "Generated",
    "LoadError",
    "LookupResult",
    "MorphManifest",
    "MorphState",
    "MorphStats",
    "Token",
    "fold",
    "load_morph",
    "tokenize",
]
