# SPDX-License-Identifier: Apache-2.0
"""NLS-P2.1.T1/T3/T4/T6 — the four matching modes, on hand-built documents.

Hand-built rather than engine-driven on purpose: each mode is a claim about what
key the trie is on, and the way to test that is to hold the tokens still and vary
only the key. A Czech token whose `lemma` is `faktura` and whose text is
`fakturami` proves lemma-mode matching in a way no amount of real engine output
does — with real output you cannot tell which of the two the trie used.

The mode matrix is the whole point of the file, so the modes appear in the test
names, and every one of them has a negative beside it: a mode that matched
*everything* would pass a positive-only suite.
"""

from __future__ import annotations

import pytest

from ttrnlp.doc.model import Document
from ttrnlp.gazetteer import build_gazetteer, load_list
from ttrnlp.packs.diag import NLS_PACK_003, PackError


def a_list(body: str, *, list_id: str = "fixture", matching: str = "ci") -> str:
    return (
        f"list: {list_id}\nversion: 1\nmatching: {matching}\n"
        "source: {world: hand, origin: test}\n" + body
    )


def gazetteer(*yaml_texts: str):
    return build_gazetteer([load_list(text) for text in yaml_texts])


def tokenized(text: str, *, lemmas: dict[str, str] | None = None) -> Document:
    """A document with one Token per whitespace-separated word.

    `lemmas` maps surface form to lemma, which is how the Czech cases below are
    written: the document says `fakturami`, the token says `faktura`.
    """
    doc = Document(text)
    annset = doc.annset("")
    offset = 0
    for word in text.split(" "):
        if word:
            features = {"text": word}
            if lemmas and word in lemmas:
                features["lemma"] = lemmas[word]
            annset.add(offset, offset + len(word), "Token", features)
        offset += len(word) + 1
    return doc


def lookups(doc: Document, anntype: str = "Lookup"):
    return sorted(
        (a.start, a.end, doc.text[a.start : a.end])
        for a in doc.annset("").with_type(anntype)
    )


def features_of(doc: Document, anntype: str = "Lookup") -> list[dict]:
    return [
        dict(a.features)
        for a in sorted(doc.annset("").with_type(anntype), key=lambda a: a.start)
    ]


# ── lemma: the Czech declension answer ───────────────────────────────────────

#: Every one of these inflects `faktura`; a surface-text list would need all four.
DECLINED = {
    "faktury": "faktura",
    "faktuře": "faktura",
    "fakturami": "faktura",
    "faktura": "faktura",
}


def test_lemma_mode_matches_every_inflected_form():
    doc = tokenized(" ".join(DECLINED), lemmas=DECLINED)
    added = gazetteer(
        a_list("entries: [{term: faktura}]", matching="lemma")
    ).annotate(doc)

    assert added == len(DECLINED)
    assert [text for _, _, text in lookups(doc)] == list(DECLINED)


def test_lemma_mode_ignores_the_surface_text():
    """The negative that makes the positive mean something: a term spelled like
    the *surface* form matches nothing, because the trie is on the lemma."""
    doc = tokenized("fakturami", lemmas=DECLINED)
    assert gazetteer(a_list("entries: [{term: fakturami}]", matching="lemma")).annotate(
        doc
    ) == 0


def test_lemma_mode_skips_tokens_with_no_lemma():
    """No lemma feature — an engine that only tokenized. gatenlp treats the token
    as absent rather than falling back to its text, which is the honest reading:
    lemma mode without a lemmatizer has nothing to match on, and silently using
    the surface form would make the default lane look like the option lane."""
    doc = tokenized("faktura")
    assert gazetteer(a_list("entries: [{term: faktura}]", matching="lemma")).annotate(
        doc
    ) == 0


# ── ci / exact / fold-diacritics ─────────────────────────────────────────────


@pytest.mark.parametrize("surface", ["Faktura", "FAKTURA", "faktura", "fAkTuRa"])
def test_ci_mode_matches_any_casing(surface):
    doc = tokenized(surface)
    assert gazetteer(a_list("entries: [{term: faktura}]")).annotate(doc) == 1


def test_ci_mode_still_needs_the_same_letters():
    doc = tokenized("Faktury")
    assert gazetteer(a_list("entries: [{term: faktura}]")).annotate(doc) == 0


def test_exact_mode_matches_only_the_byte_equal_term():
    """Codes and SKUs. The string trie reads raw text, so the tokenizer never
    gets a say in where `INV-2026/0042` begins and ends."""
    doc = Document("faktura INV-2026/0042 od zákazníka")
    added = gazetteer(
        a_list('entries: [{term: "INV-2026/0042"}]', matching="exact")
    ).annotate(doc)

    assert added == 1
    assert lookups(doc) == [(8, 21, "INV-2026/0042")]


@pytest.mark.parametrize("variant", ["inv-2026/0042", "INV-2026/0043", "INV 2026/0042"])
def test_exact_mode_rejects_anything_but_the_term(variant):
    doc = Document(f"faktura {variant}")
    assert gazetteer(
        a_list('entries: [{term: "INV-2026/0042"}]', matching="exact")
    ).annotate(doc) == 0


def test_exact_mode_does_not_match_inside_a_longer_word():
    """gatenlp's string trie matches anywhere; the boundary guard is ours.

    Without it, the code `INV-1` would be annotated inside `XINV-12` — a false
    positive with no diagnostic, in the mode chosen *because* codes must match
    exactly.
    """
    doc = Document("XINV-12 and INV-1x and (INV-1) and INV-1.")
    added = gazetteer(a_list('entries: [{term: "INV-1"}]', matching="exact")).annotate(
        doc
    )

    assert added == 2
    assert [text for _, _, text in lookups(doc)] == ["INV-1", "INV-1"]
    # The two that matched are the flanked-by-punctuation ones.
    assert [start for start, _, _ in lookups(doc)] == [24, 35]


def test_exact_mode_needs_no_tokens_at_all():
    doc = Document("INV-1")
    assert not list(doc.annset("").with_type("Token"))
    assert gazetteer(a_list('entries: [{term: "INV-1"}]', matching="exact")).annotate(
        doc
    ) == 1


@pytest.mark.parametrize(
    ("term", "surface"),
    [
        ("zákazníka", "zakaznika"),  # accented list, unaccented text
        ("zakaznika", "zákazníka"),  # and the other way round
        ("zákazníka", "ZAKAZNIKA"),  # folding case as well
    ],
)
def test_fold_diacritics_matches_both_ways(term, surface):
    doc = tokenized(surface)
    assert gazetteer(
        a_list(f"entries: [{{term: {term}}}]", matching="fold-diacritics")
    ).annotate(doc) == 1


def test_fold_diacritics_is_not_a_free_for_all():
    """Folding accents is not folding letters: `zakazniku` is a different word."""
    doc = tokenized("zakazniku")
    assert gazetteer(
        a_list("entries: [{term: zákazníka}]", matching="fold-diacritics")
    ).annotate(doc) == 0


# ── multi-token terms and longest match ──────────────────────────────────────


def test_a_multi_token_term_becomes_one_lookup_over_both_tokens():
    doc = tokenized("najdi obchodní zástupce")
    added = gazetteer(a_list('entries: [{term: "obchodní zástupce"}]')).annotate(doc)

    assert added == 1
    assert lookups(doc) == [(6, 23, "obchodní zástupce")]


def test_the_longest_term_wins_at_the_same_start():
    """T1's longest-match case: both terms are in the list, only the longer one
    is annotated."""
    doc = tokenized("najdi obchodní zástupce")
    added = gazetteer(
        a_list('entries: [{term: "obchodní"}, {term: "obchodní zástupce"}]')
    ).annotate(doc)

    assert added == 1
    assert lookups(doc) == [(6, 23, "obchodní zástupce")]


def test_a_term_covered_by_a_longer_match_is_not_annotated_either():
    """The half of longest-match that is easy to miss (`skip_longest`).

    `zástupce` starts *after* `obchodní zástupce` does, so per-position longest
    match alone would still annotate it — leaving a Lookup inside a Lookup, which
    breaks every adjacency in a phase whose `input:` is `[Lookup]`.
    """
    doc = tokenized("najdi obchodní zástupce")
    added = gazetteer(
        a_list('entries: [{term: "obchodní zástupce"}, {term: "zástupce"}]')
    ).annotate(doc)

    assert added == 1
    assert lookups(doc) == [(6, 23, "obchodní zástupce")]


def test_a_term_after_a_match_is_still_found():
    """...and the guard above must not swallow what follows the match."""
    doc = tokenized("obchodní zástupce a faktura")
    added = gazetteer(
        a_list('entries: [{term: "obchodní zástupce"}, {term: faktura}]')
    ).annotate(doc)

    assert added == 2
    assert lookups(doc) == [(0, 17, "obchodní zástupce"), (20, 27, "faktura")]


# ── provenance (T4) ──────────────────────────────────────────────────────────


def test_a_lookup_carries_its_entry_features_and_its_provenance():
    doc = tokenized("faktura")
    gazetteer(
        a_list(
            "entries: [{term: faktura, features: "
            "{kind: entity_alias, entity: faktura}}]",
            list_id="dfp-entity-aliases",
        )
    ).annotate(doc)

    (features,) = features_of(doc)
    assert features == {
        "kind": "entity_alias",
        "entity": "faktura",
        "source": "dfp-entity-aliases",
        "matching": "ci",
    }


def test_provenance_records_the_mode_that_actually_fired():
    """Two lists over the same term, different modes: each Lookup says which."""
    doc = tokenized("Faktura", lemmas={"Faktura": "faktura"})
    gazetteer(
        a_list("entries: [{term: faktura}]", list_id="by-lemma", matching="lemma"),
        a_list("entries: [{term: faktura}]", list_id="by-case", matching="ci"),
    ).annotate(doc)

    assert [(f["source"], f["matching"]) for f in features_of(doc)] == [
        ("by-lemma", "lemma"),
        ("by-case", "ci"),
    ]


def test_a_list_emits_its_own_annotation_type():
    doc = tokenized("kg")
    gazetteer(
        a_list("annotation: Unit\nentries: [{term: kg, features: {si: true}}]")
    ).annotate(doc)

    assert lookups(doc, "Unit") == [(0, 2, "kg")]
    assert not lookups(doc, "Lookup")


# ── the Gazetteer object itself ──────────────────────────────────────────────


def test_lists_run_in_the_order_they_are_named():
    """contracts §7: a pipeline names its gazetteer step as an ordered list of
    ids, and the order is the pack author's — not the config file's."""
    built = gazetteer(
        a_list("entries: [{term: faktura}]", list_id="first"),
        a_list("entries: [{term: faktura}]", list_id="second"),
    )
    assert built.list_ids == ("first", "second")

    doc = tokenized("faktura")
    built.annotate(doc, lists=["second", "first"])
    assert [f["source"] for f in features_of(doc)] == ["second", "first"]


def test_a_subset_of_lists_can_be_run():
    built = gazetteer(
        a_list("entries: [{term: faktura}]", list_id="wanted"),
        a_list("entries: [{term: faktura}]", list_id="unwanted"),
    )
    doc = tokenized("faktura")
    assert built.annotate(doc, lists=["wanted"]) == 1
    assert [f["source"] for f in features_of(doc)] == ["wanted"]


def test_two_lists_sharing_an_id_are_rejected():
    with pytest.raises(PackError) as raised:
        gazetteer(
            a_list("entries: [{term: faktura}]", list_id="same"),
            a_list("entries: [{term: zákazník}]", list_id="same"),
        )
    assert raised.value.codes == [NLS_PACK_003]
    assert "duplicate list id" in str(raised.value)


# ── determinism, and the NL-17 guard (T6) ────────────────────────────────────


def test_the_same_document_and_lists_produce_the_same_annotations_twice():
    """Same input twice ⇒ identical annotations, ids aside. A trie built from a
    dict-ordered structure, or a set anywhere in the scan, would show up here."""

    def run():
        doc = tokenized(
            "faktura obchodní zástupce zakaznika INV-1",
            lemmas={"faktura": "faktura"},
        )
        gazetteer(
            a_list("entries: [{term: faktura}]", list_id="l1", matching="lemma"),
            a_list('entries: [{term: "obchodní zástupce"}]', list_id="l2"),
            a_list(
                "entries: [{term: zákazníka}]",
                list_id="l3",
                matching="fold-diacritics",
            ),
            a_list('entries: [{term: "INV-1"}]', list_id="l4", matching="exact"),
        ).annotate(doc)
        return [
            (a.start, a.end, a.type, dict(a.features))
            for a in sorted(doc.annset(""), key=lambda a: (a.start, a.type))
        ]

    first, second = run(), run()
    assert first == second
    # All four modes actually fired — otherwise this asserts that two empty
    # documents are equal.
    assert {f["matching"] for *_, f in first if "matching" in f} == {
        "lemma",
        "ci",
        "fold-diacritics",
        "exact",
    }


def test_no_lookup_carries_a_score():
    """NL-17, asserted rather than assumed: the deterministic gazetteer must not
    grow a confidence field, because that is the line between it and the fuzzy
    matchers that stay world-side."""
    doc = tokenized("faktura obchodní zástupce")
    gazetteer(
        a_list(
            "entries: [{term: faktura, features: {kind: k}}, "
            '{term: "obchodní zástupce"}]'
        )
    ).annotate(doc)

    for features in features_of(doc):
        assert not {"score", "confidence", "distance", "similarity"} & set(features)
