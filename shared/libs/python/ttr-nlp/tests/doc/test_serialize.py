# SPDX-License-Identifier: Apache-2.0
"""NLS-P3.1.T4 — ``Document`` ⇄ proto, and the places it could lose something.

A serializer is tested by what survives the trip, so the round-trips here carry
the things most likely not to: the default annotation set (whose name is the
empty string), annotation ids (referenced from traces and logs), an integer
feature (the wire has only a double), a dict feature (the wire has no nested
maps), and Czech text (offsets are character indices, not bytes — *zákazníka* is
9 characters and 11 bytes, and a serializer that got that wrong would produce
spans that look almost right).

The failure mode being guarded against is not a crash. It is a feature quietly
missing on the far side, or a `dep_head` arriving as `3.0` so that a pack
matching `dep_head: 3` silently stops firing.
"""

from __future__ import annotations

import pytest

from tests.conftest import load_engines
from ttrnlp.doc import Document, build_document
from ttrnlp.doc.serialize import doc_from_proto, doc_to_proto

CZECH = "Zobraz všechny faktury od zákazníka Microsoft"


def annotations_of(doc: Document, name: str = "") -> list[tuple]:
    """Everything that must survive, in a comparable form."""
    return sorted(
        (a.start, a.end, a.type, a.id, dict(a.features))
        for a in doc.annset(name)
    )


def a_document() -> Document:
    """Three named sets, and every feature type the contract admits."""
    doc = Document(CZECH)
    doc.features["language"] = "cs"
    doc.features["pipeline"] = "query-patterns"
    doc.features["annotations_added"] = 7
    doc.features["degraded"] = False
    doc.features["engines"] = ["stanza", "nametag3"]

    default = doc.annset("")
    default.add(15, 22, "Token", {"text": "faktury", "lemma": "faktura", "dep_head": 3})
    default.add(26, 35, "Lookup", {"entity": "subjekt", "matching": "lemma"})

    ner = doc.annset("ner")
    ner.add(36, 45, "ORGANIZATION", {"text": "Microsoft", "cnec": "if"})

    patterns = doc.annset("patterns")
    patterns.add(
        15,
        45,
        "QueryPattern",
        {"query": "faktury_zakaznika", "nazev_zakaznika": "Microsoft"},
    )
    return doc


# ── the round trip ───────────────────────────────────────────────────────────


def test_a_document_survives_the_round_trip_whole():
    original = a_document()
    restored = doc_from_proto(doc_to_proto(original))

    assert restored.text == original.text
    assert restored.annset_names() == original.annset_names()
    for name in {"", *original.annset_names()}:
        assert annotations_of(restored, name) == annotations_of(original, name)


def test_document_features_survive_including_their_types():
    restored = doc_from_proto(doc_to_proto(a_document()))

    assert restored.features["language"] == "cs"
    assert restored.features["engines"] == ["stanza", "nametag3"]
    assert restored.features["degraded"] is False
    assert restored.features["annotations_added"] == 7
    assert isinstance(restored.features["annotations_added"], int)


def test_the_default_set_name_is_preserved():
    """gatenlp's default set is named with the empty string, which is exactly the
    value a "did the caller set this?" check gets wrong."""
    message = doc_to_proto(a_document())
    names = [s.name for s in message.annotation_sets]

    assert "" in names
    restored = doc_from_proto(message)
    assert len(list(restored.annset(""))) == 2


def test_annotation_ids_are_preserved_not_reassigned():
    """A QueryPattern is referred to by id in traces and logs. A client that
    renumbered it would make those references point at something else."""
    original = a_document()
    ids = {a.id for a in original.annset("")}

    restored = doc_from_proto(doc_to_proto(original))
    assert {a.id for a in restored.annset("")} == ids


def test_czech_offsets_are_character_indices():
    original = a_document()
    restored = doc_from_proto(doc_to_proto(original))

    lookup = next(a for a in restored.annset("") if a.type == "Lookup")
    assert restored.text[lookup.start : lookup.end] == "zákazníka"
    # And the point of the test: 9 characters, 11 bytes.
    assert lookup.end - lookup.start == 9
    assert len("zákazníka".encode()) == 11


def test_the_real_hero_document_round_trips():
    """Built by the importers from actual engine output, rather than by hand."""
    fixture = load_engines("hero-cs-invoices")
    original = build_document(
        fixture["text"], fixture["engines"], language=fixture["language"]
    )
    restored = doc_from_proto(doc_to_proto(original))

    assert annotations_of(restored) == annotations_of(original)
    # `feats` is the dict feature the importers actually produce — the reason
    # flattening exists at all.
    assert restored.features["model_versions"] == original.features["model_versions"]


def test_serialising_twice_gives_the_same_bytes():
    """Sets in name order, annotations in (start, end, id) order. Wire payloads
    get diffed; gatenlp's iteration order is not something to depend on."""
    doc = a_document()
    once, twice = doc_to_proto(doc), doc_to_proto(doc)
    assert once.SerializeToString() == twice.SerializeToString()


# ── the value domain ─────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("faktura", "faktura"),
        (3, 3),
        (3.5, 3.5),
        (True, True),
        (False, False),
        (0, 0),
        (["a", "b"], ["a", "b"]),
        ([1, 2.5, "x", True], [1, 2.5, "x", True]),
        ([], []),
    ],
)
def test_each_admitted_feature_value_survives_with_its_type(value, expected):
    doc = Document("x")
    doc.annset("").add(0, 1, "T", {"v": value})

    restored = doc_from_proto(doc_to_proto(doc))
    (ann,) = restored.annset("")
    assert ann.features["v"] == expected
    assert type(ann.features["v"]) is type(expected)


def test_a_bool_does_not_come_back_as_a_number():
    """`bool` is a subclass of `int` in Python, so the order of the isinstance
    checks decides this. A `true` that returned as `1` would stop matching a
    `features: {flag: true}` constraint."""
    doc = Document("x")
    doc.annset("").add(0, 1, "T", {"flag": True})

    (ann,) = doc_from_proto(doc_to_proto(doc)).annset("")
    assert ann.features["flag"] is True


def test_an_integral_float_comes_back_as_an_int():
    """The wire has one numeric field, a double. `dep_head: 3` has to keep
    matching after a round trip, and it only does because integral doubles are
    narrowed on the way back. Documented as lossy in the other direction."""
    doc = Document("x")
    doc.annset("").add(0, 1, "T", {"dep_head": 3.0})

    (ann,) = doc_from_proto(doc_to_proto(doc)).annset("")
    assert ann.features["dep_head"] == 3
    assert isinstance(ann.features["dep_head"], int)


def test_a_dict_feature_is_flattened_and_re_nested():
    """`Token.feats` — the morphological bundle. The contract has no nested map,
    so it travels as dotted keys rather than as an invented encoding."""
    doc = Document("faktury")
    doc.annset("").add(0, 7, "Token", {"feats": {"Case": "Gen", "Number": "Sing"}})

    message = doc_to_proto(doc)
    wire_keys = set(message.annotation_sets[0].annotations[0].features)
    assert wire_keys == {"feats.Case", "feats.Number"}

    (ann,) = doc_from_proto(message).annset("")
    assert ann.features["feats"] == {"Case": "Gen", "Number": "Sing"}


def test_a_doubly_nested_feature_is_refused():
    doc = Document("x")
    doc.annset("").add(0, 1, "T", {"a": {"b": {"c": 1}}})

    with pytest.raises(TypeError, match="one level deep"):
        doc_to_proto(doc)


def test_a_feature_the_contract_cannot_carry_is_refused_not_dropped():
    """A feature that vanishes between two services takes a day to find. An
    exception naming the feature takes a minute."""
    doc = Document("x")
    doc.annset("").add(0, 1, "T", {"when": object()})

    with pytest.raises(TypeError, match="cannot put a object"):
        doc_to_proto(doc)


def test_a_feature_value_with_no_kind_is_refused():
    """An empty oneof means the sender set a field this build does not know. The
    honest answer is to say so — a default would invent data."""
    from org.tatrman.nlp.v1 import nlp_pb2

    message = nlp_pb2.AnnotatedDocument(
        text="x",
        annotation_sets=[
            nlp_pb2.AnnotationSet(
                name="",
                annotations=[
                    nlp_pb2.Annotation(
                        id=1,
                        type="T",
                        char_start=0,
                        char_end=1,
                        features={"mystery": nlp_pb2.FeatureValue()},
                    )
                ],
            )
        ],
    )
    with pytest.raises(ValueError, match="carries no kind"):
        doc_from_proto(message)


def test_a_dotted_key_colliding_with_a_plain_one_is_refused():
    from org.tatrman.nlp.v1 import nlp_pb2

    message = nlp_pb2.AnnotatedDocument(
        text="x",
        document_features={
            "feats": nlp_pb2.FeatureValue(string_value="plain"),
            "feats.Case": nlp_pb2.FeatureValue(string_value="Gen"),
        },
    )
    with pytest.raises(ValueError, match="both a value and a nested map"):
        doc_from_proto(message)


# ── the filters (contracts §2.2) ─────────────────────────────────────────────


def test_include_sets_returns_only_those_sets():
    message = doc_to_proto(a_document(), include_sets=["patterns"])
    assert [s.name for s in message.annotation_sets] == ["patterns"]


def test_include_types_returns_only_those_types():
    message = doc_to_proto(a_document(), include_types=["QueryPattern"])
    kept = [(s.name, [a.type for a in s.annotations]) for s in message.annotation_sets]
    assert kept == [("patterns", ["QueryPattern"])]


def test_the_two_filters_compose():
    message = doc_to_proto(
        a_document(), include_sets=["", "patterns"], include_types=["Lookup"]
    )
    kept = {s.name: [a.type for a in s.annotations] for s in message.annotation_sets}
    assert kept == {"": ["Lookup"], "patterns": []}


def test_an_empty_filter_means_no_filter():
    """contracts §2.2's repeated-field convention: empty is "everything", not
    "nothing". The opposite reading would make an unset filter return an empty
    document, which looks exactly like a pipeline that matched nothing."""
    everything = doc_to_proto(a_document())
    assert doc_to_proto(a_document(), include_sets=[]) == everything
    assert doc_to_proto(a_document(), include_types=[]) == everything


def test_a_set_asked_for_by_name_is_returned_even_when_empty():
    """The caller named it, so its emptiness is the answer. Unnamed empty sets are
    dropped instead — the default set exists on every document, and emitting it
    empty on every filtered response is noise."""
    message = doc_to_proto(a_document(), include_sets=["ner"], include_types=["Nope"])
    kept = [(s.name, len(s.annotations)) for s in message.annotation_sets]
    assert kept == [("ner", 0)]


def test_filtering_does_not_disturb_document_features():
    """The filter is about annotations. `language` and `pipeline` are how a client
    knows what it is looking at."""
    message = doc_to_proto(a_document(), include_types=["QueryPattern"])
    assert message.document_features["language"].string_value == "cs"


# ── the missing-extra message ────────────────────────────────────────────────


def test_a_missing_grpc_extra_says_which_extra(monkeypatch):
    """`ModuleNotFoundError: org.tatrman.nlp.v1` names a generated file the reader
    has never heard of. This names the install command."""
    import sys

    monkeypatch.setitem(sys.modules, "org.tatrman.nlp.v1", None)

    with pytest.raises(ImportError, match=r"ttr-nlp\[grpc\]"):
        doc_to_proto(Document("x"))
