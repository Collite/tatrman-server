# SPDX-License-Identifier: Apache-2.0
"""The analysis records (LM contracts §1) and THE fold definition (§4).

**Ambiguity is the API** (P-2). Two different things are called ambiguity in
Czech morphology and they are represented differently on purpose:

*kind (a)* — one lemma, many readings. ``tržby`` is *tržba* in the genitive
singular, the nominative plural and the accusative plural. That is **one**
`Analysis` whose ``feats`` is a *set of readings*, because a caller asking
"which lemma" has an unambiguous answer and only a caller asking "which case"
sees a choice.

*kind (b)* — many lemmas. ``má`` is *mít* or *můj*; folded, ``byt`` is *byt* or
*být*. That is **several** `Analysis` candidates in one `LookupResult`, ranked.

Nothing here disambiguates: no tagger, no context, no scoring (design §10). A
consumer that wants one answer takes the head of the list, which is what the
annotator writes into the ``lemma`` token feature so that every pre-morph
consumer keeps working unchanged.

**Provenance is load-bearing, not decoration.** ``lexicon`` means a curated
entry answered; ``statistical`` means a model or the stem/fold last resort did
(v1: only the last resort — the seam is unwired, see `chain.py`);
``provisional`` means an unverified generated world-entity row answered (Q-7).
The NC-free claim of the default cs path is exactly the claim that the named
acceptance cases resolve with ``lexicon`` provenance, so the literal set is
enforced here rather than trusted to spelling.
"""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass

#: A curated lexicon entry answered.
PROVENANCE_LEXICON = "lexicon"
#: A model or the fold/stem last resort answered (chain.py marks these).
PROVENANCE_STATISTICAL = "statistical"
#: An unverified generated world-entity row answered (Q-7, overlays only).
PROVENANCE_PROVISIONAL = "provisional"

#: Every literal an `Analysis` may carry (contracts §1).
PROVENANCES = frozenset(
    {PROVENANCE_LEXICON, PROVENANCE_STATISTICAL, PROVENANCE_PROVISIONAL}
)

#: `LookupResult.matched_via` — the form was found as written.
MATCHED_EXACT = "exact"
#: `LookupResult.matched_via` — the form was found through the fold index.
MATCHED_FOLDED = "folded"

MATCHED_VIA = frozenset({MATCHED_EXACT, MATCHED_FOLDED})


def fold(s: str) -> str:
    """NFD, drop combining marks, casefold — THE one fold definition.

    *tržby* and *trzby*, *Kauflandu* and *kauflandu* fold to the same key. This
    is what makes the diacritics-less hero variant resolve at all.

    Contracts §4 calls this "THE one definition" and means it literally: the
    snapshot's ``#fold-index`` is built by the `ttr-morph` compiler and consumed
    by the loader in this wheel, so the two sides must agree byte for byte or
    every folded lookup silently misses. `ttr-morph` depends on `ttr-nlp` and
    re-exports *this function* rather than reimplementing it — the drift cannot
    happen if there is only one implementation.

    (`ttrnlp.gazetteer.annotate.fold_diacritics` is the same algorithm, written
    before this module existed, for the ``fold-diacritics`` matching mode.
    `tests/morph/test_records.py` asserts the two agree on every fixture so the
    duplicate cannot drift while it lives; folding them into one is a P2-merge
    follow-up rather than a change this list is allowed to make.)
    """
    decomposed = unicodedata.normalize("NFD", s)
    stripped = "".join(ch for ch in decomposed if not unicodedata.combining(ch))
    return stripped.casefold()


@dataclass(frozen=True)
class Analysis:
    """One reading of one form (contracts §1 — field names are FINAL).

    ``vzor``/``flags`` are *editorial* provenance: the paradigm the entry was
    generated from. They ride beside the UD payload, never inside it, so a
    consumer that only speaks UD never has to know what a vzor is, and the
    editorial side never loses the information it needs to regenerate.

    ``rank`` is precomputed at compile time (CAC frequency order for the core
    layers, flat for entity layers). Ranking at lookup time would mean either
    carrying the frequency table into the runtime or scoring — and scoring is
    the line the whole suite keeps world-side (NL-17).
    """

    lemma: str
    upos: str
    #: UD feature atoms. kind-(a) ambiguity packs several readings into one set.
    feats: frozenset[str]
    vzor: str | None = None
    flags: tuple[str, ...] = ()
    provenance: str = PROVENANCE_LEXICON
    rank: int = 0
    #: Decomposition — ``abych`` is ``("aby", "bych")`` (B-O4).
    parts: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.provenance not in PROVENANCES:
            raise ValueError(
                f"unknown provenance {self.provenance!r} — contracts §1 has "
                f"exactly {sorted(PROVENANCES)}; the NC-free proof of the cs "
                "path is an assertion about these literals, so a typo here "
                "would make an acceptance case pass for the wrong reason"
            )


@dataclass(frozen=True)
class LookupResult:
    """What one form resolved to, and how (contracts §1).

    ``analyses`` is ranked, and the ordering rule is exact-before-folded
    (B-F3): when *byt* is looked up and both *byt* and *být* fold to it, the
    entry that matched the form **as written** comes first. A folded match is a
    weaker piece of evidence than an exact one and the order is the only place
    that difference survives — nothing else carries it, by design.
    """

    form: str
    matched_via: str
    analyses: tuple[Analysis, ...]

    def __post_init__(self) -> None:
        if self.matched_via not in MATCHED_VIA:
            raise ValueError(
                f"unknown matched_via {self.matched_via!r} — contracts §1 has "
                f"exactly {sorted(MATCHED_VIA)}"
            )

    @property
    def lemma(self) -> str | None:
        """The head-of-list lemma — what the ``lemma`` token feature gets."""
        return self.analyses[0].lemma if self.analyses else None

    @property
    def lemmas(self) -> tuple[str, ...]:
        """Every candidate lemma, ranked, deduplicated, order preserved.

        Deduplicated because kind-(a) and kind-(b) ambiguity meet here: three
        readings of *tržba* are one lemma repeated only if the candidates were
        built per reading, and the match-if-any gazetteer (LM-8) would then
        walk the same trie step three times for no gain.
        """
        seen: dict[str, None] = {}
        for analysis in self.analyses:
            seen.setdefault(analysis.lemma, None)
        return tuple(seen)


@dataclass(frozen=True)
class Generated:
    """One form produced by the paradigm engine (the generation direction).

    ``provenance`` is ``lexicon`` for curated/verified generation and
    ``provisional`` for the Q-7 narrow path (auto-validated world entities that
    no human has confirmed yet). ``statistical`` is not a legal value here:
    nothing generates forms statistically in v1.
    """

    form: str
    feats: frozenset[str]
    provenance: str = PROVENANCE_LEXICON

    def __post_init__(self) -> None:
        if self.provenance not in (PROVENANCE_LEXICON, PROVENANCE_PROVISIONAL):
            raise ValueError(
                f"unknown generated provenance {self.provenance!r} — generation "
                "is deterministic, so only 'lexicon' and 'provisional' (Q-7) "
                "are legal"
            )


__all__ = [
    "MATCHED_EXACT",
    "MATCHED_FOLDED",
    "MATCHED_VIA",
    "PROVENANCES",
    "PROVENANCE_LEXICON",
    "PROVENANCE_PROVISIONAL",
    "PROVENANCE_STATISTICAL",
    "Analysis",
    "Generated",
    "LookupResult",
    "fold",
]
