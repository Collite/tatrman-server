# SPDX-License-Identifier: Apache-2.0
"""NLS-P7.1 T2 — the records, and the one fold definition (contracts §1/§4)."""

from __future__ import annotations

import dataclasses

import pytest

from ttrnlp.gazetteer.annotate import fold_diacritics
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

# ── fold ─────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("raw", "folded"),
    [
        ("tržby", "trzby"),
        ("Kauflandu", "kauflandu"),
        ("letošním", "letosnim"),
        ("loňský", "lonsky"),
        ("ŘEDITELKA", "reditelka"),
        ("být", "byt"),
        ("byt", "byt"),  # the collision that makes exact-before-folded matter
        ("", ""),
        ("2025", "2025"),
    ],
)
def test_fold(raw: str, folded: str):
    assert fold(raw) == folded


def test_fold_is_idempotent():
    for word in ("tržby", "Kauflandu", "PŘÍJMY", "e-shop"):
        assert fold(fold(word)) == fold(word)


def test_fold_agrees_with_the_gazetteers_own_diacritics_fold():
    """The drift guard for the duplicate this module inherited.

    ``gazetteer.annotate.fold_diacritics`` is the same algorithm, written for
    the ``fold-diacritics`` matching mode before `ttrnlp.morph` existed. p7-1 is
    explicitly not allowed to touch `gazetteer/` (⚑LMP-D2), so the two live side
    by side for now — but a gazetteer list and the snapshot's fold index that
    disagreed about what *trzby* folds to would produce a lookup that hits and a
    list that misses, on the same token, with nothing to point at.
    """
    for word in ("tržby", "Kauflandu", "ŽĎÁR nad Sázavou", "Straße", "ﬁnance", ""):
        assert fold(word) == fold_diacritics(word)


# ── Analysis / LookupResult / Generated ──────────────────────────────────────


def _analysis(**kwargs) -> Analysis:
    base = {"lemma": "tržba", "upos": "NOUN", "feats": frozenset({"Case=Gen"})}
    return Analysis(**{**base, **kwargs})


def test_analysis_defaults_match_the_contract():
    analysis = _analysis()
    assert analysis.vzor is None
    assert analysis.flags == ()
    assert analysis.provenance == PROVENANCE_LEXICON
    assert analysis.rank == 0
    assert analysis.parts == ()


def test_records_are_frozen():
    analysis = _analysis()
    with pytest.raises(dataclasses.FrozenInstanceError):
        analysis.lemma = "jiná"  # type: ignore[misc]
    result = LookupResult(form="tržby", matched_via=MATCHED_EXACT, analyses=(analysis,))
    with pytest.raises(dataclasses.FrozenInstanceError):
        result.matched_via = MATCHED_FOLDED  # type: ignore[misc]


def test_a_kind_a_ambiguity_is_one_analysis_with_a_reading_set():
    """``tržby`` is gen sg / nom pl / acc pl of one lemma."""
    analysis = _analysis(
        feats=frozenset(
            {
                "Case=Gen|Number=Sing",
                "Case=Nom|Number=Plur",
                "Case=Acc|Number=Plur",
            }
        )
    )
    result = LookupResult(form="tržby", matched_via=MATCHED_EXACT, analyses=(analysis,))
    assert len(result.analyses) == 1
    assert len(analysis.feats) == 3
    assert result.lemmas == ("tržba",)


def test_a_kind_b_ambiguity_is_several_candidates_in_rank_order():
    """``má`` is *mít* or *můj*."""
    result = LookupResult(
        form="má",
        matched_via=MATCHED_EXACT,
        analyses=(
            Analysis(lemma="mít", upos="VERB", feats=frozenset(), rank=1),
            Analysis(lemma="můj", upos="DET", feats=frozenset(), rank=2),
        ),
    )
    assert result.lemma == "mít"
    assert result.lemmas == ("mít", "můj")


def test_lemmas_deduplicates_but_keeps_order():
    result = LookupResult(
        form="tržby",
        matched_via=MATCHED_EXACT,
        analyses=(
            _analysis(feats=frozenset({"Case=Gen"})),
            _analysis(feats=frozenset({"Case=Nom"})),
            Analysis(lemma="tržba-jiná", upos="NOUN", feats=frozenset()),
        ),
    )
    assert result.lemmas == ("tržba", "tržba-jiná")


def test_an_empty_result_has_no_head_lemma():
    result = LookupResult(form="xyz", matched_via=MATCHED_EXACT, analyses=())
    assert result.lemma is None
    assert result.lemmas == ()


def test_parts_carry_a_decomposition():
    analysis = Analysis(
        lemma="abych",
        upos="SCONJ",
        feats=frozenset({"Mood=Cnd|Number=Sing|Person=1"}),
        parts=("aby", "bych"),
    )
    assert analysis.parts == ("aby", "bych")


@pytest.mark.parametrize(
    "provenance",
    [PROVENANCE_LEXICON, PROVENANCE_STATISTICAL, PROVENANCE_PROVISIONAL],
)
def test_every_contract_provenance_is_accepted(provenance: str):
    assert _analysis(provenance=provenance).provenance == provenance


def test_an_unknown_provenance_is_refused():
    """The literals are the NC-free proof, so they are checked, not trusted."""
    with pytest.raises(ValueError, match="unknown provenance"):
        _analysis(provenance="guessed")


def test_an_unknown_matched_via_is_refused():
    with pytest.raises(ValueError, match="unknown matched_via"):
        LookupResult(form="x", matched_via="fuzzy", analyses=())


def test_generated_defaults_to_lexicon_and_allows_provisional():
    assert Generated(form="tržbami", feats=frozenset()).provenance == (
        PROVENANCE_LEXICON
    )
    assert Generated(
        form="Kauflandu", feats=frozenset(), provenance=PROVENANCE_PROVISIONAL
    ).provenance == PROVENANCE_PROVISIONAL


def test_generation_is_never_statistical():
    with pytest.raises(ValueError, match="unknown generated provenance"):
        Generated(form="x", feats=frozenset(), provenance=PROVENANCE_STATISTICAL)
