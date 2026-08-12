# SPDX-License-Identifier: Apache-2.0
"""NLS-P8.1 T4 — `generate`'s contract and the flag transforms.

The golden tables assert *what* the patterns produce. This file asserts the
properties the tables cannot: that flag order does not matter, that the errors
fire, and that the transforms do the specific thing their name claims on the
specific word they are named after.
"""

from __future__ import annotations

import pytest

from ttrmorph.engine import BadFlag, BadLemma, UnknownVzor, generate, load


def forms(*args, **kwargs) -> set[str]:
    return {form for form, _ in generate(*args, **kwargs)}


# ── the contract ─────────────────────────────────────────────────────────────


def test_an_unknown_vzor_is_refused():
    with pytest.raises(UnknownVzor):
        generate("tržba", "no-such-vzor")


def test_an_unknown_flag_is_refused():
    with pytest.raises(BadFlag):
        generate("tržba", "žena", ["make-it-nice"])


def test_a_lemma_that_cannot_carry_the_pattern_is_refused():
    """The third failure contracts §4 does not name, and the quiet one.

    A pattern that strips a citation ending the lemma does not have would
    otherwise build the paradigm on a wrong stem: every form wrong, no error,
    and the entry looks fine in the layer file.
    """
    with pytest.raises(BadLemma):
        generate("hrad", "žena")


def test_flag_order_does_not_change_the_paradigm():
    """Two flags on one word — the case that makes the rule visible.

    Application order is the tables' `flag_order`, not the order an analyst
    typed. A paradigm that depended on the typing would not be a function of
    (lemma, vzor, flags), and `classify` — which searches flag *sets* — could
    not be its inverse.
    """
    one = generate("matka", "žena", ["palatal", "fleeting-e"])
    other = generate("matka", "žena", ["fleeting-e", "palatal"])
    assert one == other


def test_generating_twice_gives_identical_sets():
    """T7. Nothing here may depend on iteration or on a previous call."""
    runs = [frozenset(generate("Kaufland", "hrad-proper")) for _ in range(5)]
    assert len(set(runs)) == 1


def test_a_repeated_flag_is_applied_once():
    assert generate("dům", "hrad", ["shorten", "shorten"]) == generate(
        "dům", "hrad", ["shorten"]
    )


def test_feats_are_canonically_ordered():
    """Atoms sorted, always.

    The feats string is compared — by `classify`, by the importers, by the
    snapshot's row identity — so two spellings of one feature set that never
    compare equal would be a bug that only surfaces on rows nobody looked at.
    """
    for _, feats in generate("tržba", "žena"):
        atoms = feats.split("|")
        assert atoms == sorted(atoms)


def test_the_vzor_constant_features_reach_every_slot():
    assert all("Gender=Fem" in feats for _, feats in generate("tržba", "žena"))


# ── the transforms, each on the word it is named after ───────────────────────


def test_fleeting_e_drops_the_vowel_when_an_ending_follows():
    produced = forms("pes", "pán", ["fleeting-e"])
    assert {"pes", "psa", "psovi", "pse", "psi"} <= produced
    assert "pesa" not in produced


def test_fleeting_e_inserts_the_vowel_where_no_ending_follows():
    """The same flag, the other direction.

    Whether the citation form happens to carry the vowel is an accident of
    which cell the dictionary shows. Two flag names for that accident would ask
    the analyst to know the answer before writing the entry.
    """
    produced = forms("matka", "žena", ["fleeting-e", "palatal"])
    assert "matek" in produced
    assert "matk" not in produced


def test_fleeting_e_does_not_fire_on_a_vowel_further_back():
    """The run of consonants after the vowel is capped for a reason.

    Without the cap the detector would find the `e` in the first syllable of a
    word like this one and produce a stem with no vowel at all.
    """
    assert "sester" in forms("sestra", "žena", ["fleeting-e", "palatal"])


def test_shorten_shortens_only_where_an_ending_follows():
    produced = forms("dům", "hrad", ["shorten"])
    assert "dům" in produced
    assert {"domu", "domem", "domy"} <= produced


def test_palatal_rewrites_the_stem_consonant_before_a_front_vowel():
    assert "matce" in forms("matka", "žena", ["fleeting-e", "palatal"])
    assert "Praze" in forms("Praha", "žena", ["palatal"])
    assert "vlci" in forms("vlk", "pán", ["palatal"])
    assert "hoši" in forms("hoch", "pán", ["palatal"])
    assert "bratři" in forms("bratr", "pán", ["palatal"])


def test_palatal_prefers_the_longer_cluster():
    """Two-character keys win over the consonant they end with.

    Otherwise the cluster is rewritten as if it were a bare velar and the
    animate plural of every -sky adjective comes out wrong.
    """
    assert "čeští" in forms("český", "mladý", ["palatal"])
    assert "českí" not in forms("český", "mladý", ["palatal"])


def test_foreign_stem_drops_the_citation_marker():
    produced = forms("cyklus", "hrad-foreign")
    assert {"cyklus", "cyklu", "cyklem"} <= produced
    assert "cyklusu" not in produced


def test_indeclinable_makes_every_slot_the_citation_form():
    assert forms("atašé", "pán", ["indeclinable"]) == {"atašé"}


def test_indeclinable_does_not_need_the_citation_ending_to_fit():
    """It never touches the stem, so the strip rule has nothing to say.

    This is what lets a borrowing sit on a native pattern purely for its
    feature frame.
    """
    assert forms("taxi", "město", ["indeclinable"]) == {"taxi"}


def test_acronym_keeps_the_bare_form_in_every_slot():
    """The difference between `acronym` and `indeclinable`.

    Real users write both "od ČEZ" and "od ČEZu", so the paradigm carries
    both — which is what makes an exported gazetteer list match either.
    """
    produced = generate("ČEZ", "acronym-m")
    slots = {feats for _, feats in produced}
    for feats in slots:
        assert ("ČEZ", feats) in produced
    assert ("ČEZu", "Animacy=Inan|Case=Gen|Gender=Masc|Number=Sing") in produced


# ── the spelling rules ───────────────────────────────────────────────────────


def test_a_soft_consonant_before_a_front_vowel_is_written_hard():
    assert {"písně", "písni", "písní"} <= forms("píseň", "píseň", ["fleeting-e"])


def test_a_hard_only_consonant_takes_the_plain_vowel():
    """The rule that lets the tables stay additive.

    The pattern's ending is written with the hook; after a consonant that
    cannot take it, the spelling rules turn it into the plain vowel. Without
    that step every pattern would need a second copy of half its endings.
    """
    assert "lese" in forms("les", "hrad")
    assert "matce" in forms("matka", "žena", ["fleeting-e", "palatal"])


def test_the_tables_are_loaded_once():
    assert load("cs") is load("cs")
