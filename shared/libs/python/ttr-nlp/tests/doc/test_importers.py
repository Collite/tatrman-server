# SPDX-License-Identifier: Apache-2.0
"""NLS-P0.T3 — the importers, against canned backend results.

These are the tests that pin `build_document` to the one thing it must never get
wrong: producing a `Document` whose annotations say exactly what the engines
said, at exactly the offsets the engines said it at. Everything downstream — the
gazetteer, the rule engine, the QueryPattern contract — is offsets and features
on this document.
"""

from __future__ import annotations

from collections import Counter

import pytest

from ttrnlp.doc import Document, build_document


def _annset(doc):
    return doc.annset("")


def _by_type(doc, anntype):
    return sorted(_annset(doc).with_type(anntype), key=lambda a: (a.start, a.end))


# ── the primary hero ─────────────────────────────────────────────────────────


@pytest.fixture
def cs_doc(hero_cs_invoices):
    return build_document(
        hero_cs_invoices["text"],
        hero_cs_invoices["engines"],
        language=hero_cs_invoices["language"],
    )


def test_document_carries_the_text_verbatim(cs_doc, hero_cs_invoices):
    assert isinstance(cs_doc, Document)
    assert cs_doc.text == hero_cs_invoices["text"]


def test_language_is_a_document_feature(cs_doc):
    assert cs_doc.features["language"] == "cs"


def test_model_versions_are_recorded_per_engine(cs_doc):
    # S-1 model identity: which model said what has to survive into the document,
    # not just into the response envelope.
    assert cs_doc.features["model_versions"] == {
        "stanza": "stanza-1.10.0",
        "nametag3": "nametag3-czech-cnec2.0-240830",
    }


def test_every_token_becomes_an_annotation(cs_doc, hero_cs_invoices):
    stanza = hero_cs_invoices["engines"][0]
    assert len(_by_type(cs_doc, "Token")) == len(stanza["tokens"]) == 6


def test_token_offsets_are_exact(cs_doc, hero_cs_invoices):
    text = hero_cs_invoices["text"]
    for ann in _by_type(cs_doc, "Token"):
        assert text[ann.start : ann.end] == ann.features["text"]


def test_token_carries_lemma_upos_feats(cs_doc):
    faktury = next(a for a in _by_type(cs_doc, "Token") if a.start == 15)
    assert faktury.end == 22
    assert faktury.features["text"] == "faktury"
    # The Czech declension answer lives here: the surface is `faktury`, the
    # lemma is `faktura`, and lemma-mode gazetteers match on the latter.
    assert faktury.features["lemma"] == "faktura"
    assert faktury.features["upos"] == "NOUN"
    assert faktury.features["xpos"] == "NNFP4-----A----"
    assert faktury.features["feats"] == {
        "Case": "Acc",
        "Gender": "Fem",
        "Number": "Plur",
        "Polarity": "Pos",
    }


def test_token_carries_dependency_edges(cs_doc):
    zakaznika = next(a for a in _by_type(cs_doc, "Token") if a.start == 26)
    assert zakaznika.features["dep_head"] == 3
    assert zakaznika.features["dep_relation"] == "nmod"


def test_token_records_its_producing_engine(cs_doc):
    assert {a.features["engine"] for a in _by_type(cs_doc, "Token")} == {"stanza"}


def test_sentence_annotations(cs_doc, hero_cs_invoices):
    sentences = _by_type(cs_doc, "Sentence")
    assert len(sentences) == 1
    assert (sentences[0].start, sentences[0].end) == (0, len(hero_cs_invoices["text"]))


# ── NER: the annotation type IS the label ────────────────────────────────────


def test_ner_annotation_is_typed_by_its_label(cs_doc):
    orgs = _by_type(cs_doc, "ORGANIZATION")
    assert len(orgs) == 1
    assert (orgs[0].start, orgs[0].end) == (36, 45)
    assert orgs[0].features["text"] == "Microsoft"
    assert orgs[0].features["engine"] == "nametag3"


def test_ner_keeps_the_raw_cnec_tag(cs_doc):
    # The coarse universal label is what rules match on; the fine-grained CNEC
    # tag is what a human needs when a rule mysteriously does not fire.
    assert _by_type(cs_doc, "ORGANIZATION")[0].features["cnec"] == "if"


def test_ner_does_not_leak_into_the_token_type(cs_doc):
    assert all(a.features["engine"] == "stanza" for a in _by_type(cs_doc, "Token"))


# ── the CNEC → universal mapping, applied not assumed ────────────────────────


@pytest.mark.parametrize(
    ("cnec", "expected"),
    [
        ("if", "ORGANIZATION"),  # firm / institution
        ("io", "ORGANIZATION"),
        ("ps", "PERSON"),  # surname
        ("pf", "PERSON"),  # firstname
        ("gu", "LOCATION"),  # city
        ("gc", "LOCATION"),  # country
        ("th", "DATE"),  # time interval
        ("op", "MISC"),  # product/artifact — mapped, never dropped
        ("", "MISC"),  # no tag at all
        ("zz", "MISC"),  # unknown class letter
    ],
)
def test_cnec_class_letter_maps_to_the_universal_label(cnec, expected):
    # The importer must DERIVE the type from the CNEC tag rather than trust the
    # adapter's `label`, otherwise a stale or blank label silently mistypes the
    # annotation and the rule that wanted it never fires.
    result = {
        "engine": "nametag3",
        "tokens": [],
        "sentences": [],
        "entities": [
            {
                "text": "Microsoft",
                "label": "WRONG",
                "charStart": 0,
                "charEnd": 9,
                "normalizedValue": f"cnec:{cnec}" if cnec else "cnec:",
                "sourceEngine": "nametag3",
            }
        ],
    }
    doc = build_document("Microsoft", [result], language="cs")
    assert [a.type for a in _annset(doc)] == [expected]


def test_label_is_trusted_when_there_is_no_cnec_tag(sample_en_invoices):
    # spaCy has no CNEC tag and its OntoNotes label is authoritative. Nothing
    # gets remapped: an `ORG` stays an `ORG` (see the corpus README).
    doc = build_document(
        sample_en_invoices["text"],
        sample_en_invoices["engines"],
        language="en",
    )
    orgs = _by_type(doc, "ORG")
    assert len(orgs) == 1
    assert (orgs[0].start, orgs[0].end) == (32, 41)
    assert "cnec" not in orgs[0].features


# ── the secondary hero: no entities at all ───────────────────────────────────


def test_secondary_hero_has_tokens_but_no_entities(hero_cs_role):
    doc = build_document(
        hero_cs_role["text"], hero_cs_role["engines"], language="cs"
    )
    assert len(_by_type(doc, "Token")) == 4
    # An AnnotationSet iterates in offset order, not insertion order, so compare
    # the multiset of types rather than a sequence.
    assert Counter(a.type for a in _annset(doc)) == {"Token": 4, "Sentence": 1}
    zastupce = next(a for a in _by_type(doc, "Token") if a.start == 20)
    assert zastupce.features["lemma"] == "zástupce"


# ── merging several engines into one document ────────────────────────────────


def test_results_from_several_engines_land_in_one_annotation_set(cs_doc):
    types = {a.type for a in _annset(cs_doc)}
    assert types == {"Token", "Sentence", "ORGANIZATION"}
    assert len({a.id for a in _annset(cs_doc)}) == len(list(_annset(cs_doc)))


def test_engine_results_may_be_given_as_a_mapping(hero_cs_invoices):
    # Callers hold results keyed by engine as often as they hold a list; both
    # are accepted so nobody has to reshape a dict to call the importer.
    as_mapping = {e["engine"]: e for e in hero_cs_invoices["engines"]}
    doc = build_document(hero_cs_invoices["text"], as_mapping, language="cs")
    assert len(_by_type(doc, "Token")) == 6
    assert len(_by_type(doc, "ORGANIZATION")) == 1


def test_empty_engine_results_still_yield_a_document():
    doc = build_document("Zobraz faktury", [], language="cs")
    assert doc.text == "Zobraz faktury"
    assert len(list(_annset(doc))) == 0
    assert doc.features["duplicate_annotations_dropped"] == 0


# ── one annotation per (type, span) ──────────────────────────────────────────


DUPLICATED_TEXT = "alpha beta gamma"


def _two_token_engines():
    """Two engines that both tokenized the same text — the realistic collision.

    A backend asked for entities still tokenizes on the way there, so a second
    result carrying the same tokens is a routing accident, not a malformed
    payload, and the importer has to survive one.
    """
    tokens = [
        {"text": "alpha", "charStart": 0, "charEnd": 5, "lemma": "alpha"},
        {"text": "beta", "charStart": 6, "charEnd": 10, "lemma": "beta"},
        {"text": "gamma", "charStart": 11, "charEnd": 16, "lemma": "gamma"},
    ]
    return [
        {"engine": "stanza", "tokens": tokens, "sentences": [], "entities": []},
        {"engine": "morphodita", "tokens": tokens, "sentences": [], "entities": []},
    ]


def test_a_second_engines_duplicate_layer_is_dropped_not_stacked():
    """Two `Token` annotations over one word are not a richer layer, just a longer
    one: nothing can tell them apart, and every consumer that enumerates the layer
    counts each word twice."""
    doc = build_document(DUPLICATED_TEXT, _two_token_engines(), language="en")
    assert [(a.start, a.end) for a in _by_type(doc, "Token")] == [
        (0, 5),
        (6, 10),
        (11, 16),
    ]
    assert doc.features["duplicate_annotations_dropped"] == 3


def test_the_first_engine_to_report_a_span_owns_it_whole():
    """Dropped whole rather than merged: a merged annotation would carry one
    engine's name over another engine's features, which is a quiet lie about
    where a value came from."""
    engines = _two_token_engines()
    engines[1]["tokens"] = [
        {**token, "lemma": "different", "upos": "NOUN"}
        for token in engines[1]["tokens"]
    ]
    doc = build_document(DUPLICATED_TEXT, engines, language="en")
    for token in _by_type(doc, "Token"):
        assert token.features["engine"] == "stanza"
        assert token.features["lemma"] != "different"
        assert "upos" not in token.features


def test_matching_is_indifferent_to_duplicate_spans_either_way():
    """Rule matching is NOT the reason the dedup exists — pinned so that nobody
    reasons their way to the opposite conclusion.

    It looks like it should be: `Ann` matches the next annotation in the visible
    list and will not skip a non-matching one, which is the boundary
    `rules/compiler.py` documents for mixed `input:` types. But it re-syncs to the
    current text offset first (`useoffset=True`), and that skips any annotation
    starting before it — so a duplicate at the same span is stepped straight over
    and three adjacent lemmas match the same way with one engine or three.
    """
    from ttrnlp.rules import build_pack
    from ttrnlp.rules.pipeline import run_phases

    pack = build_pack(
        "pack: adjacency\nversion: 1\nphases:\n"
        "  - phase: p\n    input: [Token]\n    control: appelt\n"
        "    rules:\n      - rule: R\n"
        "        lhs: [ { lemma: alpha }, { lemma: beta }, { lemma: gamma } ]\n"
        "        rhs: [ { add: { type: M } } ]\n"
    )
    one, two = _two_token_engines()[:1], _two_token_engines()
    for engines in (one, two):
        doc = build_document(DUPLICATED_TEXT, engines, language="en")
        report = run_phases(doc, [pack])
        assert report.firings == 1
        assert [(a.start, a.end) for a in _by_type(doc, "M")] == [(0, 16)]


def test_annotations_of_different_types_at_one_span_both_survive():
    """The dedup is per (type, span). A `Lookup` laid over a `Token`, or an
    entity exactly covering one, is not a duplicate of it."""
    engines = [
        {
            "engine": "spacy",
            "tokens": [{"text": "Microsoft", "charStart": 0, "charEnd": 9}],
            "sentences": [{"charStart": 0, "charEnd": 9}],
            "entities": [{"text": "Microsoft", "label": "ORG",
                          "charStart": 0, "charEnd": 9}],
        }
    ]
    doc = build_document("Microsoft", engines, language="en")
    assert Counter(a.type for a in _annset(doc)) == {
        "Token": 1,
        "Sentence": 1,
        "ORG": 1,
    }
    assert doc.features["duplicate_annotations_dropped"] == 0


# ── offsets are characters, and Czech is where that bites ────────────────────


def test_offsets_are_character_indices_not_bytes(cs_doc):
    zakaznika = next(a for a in _by_type(cs_doc, "Token") if a.start == 26)
    assert zakaznika.end - zakaznika.start == 9  # 9 characters
    assert len("zákazníka".encode()) == 11  # …and 11 bytes, which we do not use
    assert cs_doc.text[26:35] == "zákazníka"
