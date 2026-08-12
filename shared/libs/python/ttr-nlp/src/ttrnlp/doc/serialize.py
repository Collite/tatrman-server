# SPDX-License-Identifier: Apache-2.0
"""``Document`` ⇄ ``AnnotatedDocument`` (contracts §2.1).

The wire crossing, in one place. Everything upstream of here works on a gatenlp
``Document``; everything downstream reads a proto message; and the translation
lives in exactly one module so that the service, the wheel's client and any test
that compares the two are all looking at the same rules.

**bdocjson is not this** (S-3). gatenlp can serialise a document to its own JSON
and it would have been less code. It is also an internal debug format with no
schema anyone else has agreed to, no field numbers, and no story for the Kotlin
consumers. The proto shapes in contracts §2.1 are the contract; bdocjson stays a
debugging convenience.

**The feature domain is deliberately small** (P-2, and the ``FeatureValue``
oneof): string, number, bool, and a list of those. Features are data, not
objects. Two consequences are worth stating plainly rather than discovering:

*Integers arrive back as integers, but only because we make them.* The wire has
one numeric field, ``number_value``, a double — contracts §2.1 chose that over a
second int field, and rightly, because JSON, YAML and Python all blur the
distinction anyway. A ``dep_head`` of ``3`` would otherwise round-trip as
``3.0``, and a pack matching ``dep_head: 3`` would stop firing. So a float whose
value is integral comes back as an ``int``. That is lossy in one direction — a
feature genuinely meant to be ``3.0`` returns as ``3`` — and it is the right
trade: no feature in the suite is a float that must stay a float, while several
are small integers that must stay integers.

*A dict feature has nowhere to go.* The importers produce one — ``Token.feats``,
the morphological bundle. Rather than inventing a nested-map encoding the
contract does not have, it is flattened to ``feats.Case=Gen`` style keys, which
keeps every value a scalar and stays readable on the wire. Coming back, those
keys are re-nested. Anything else unexpected raises rather than being dropped
silently: a feature that vanishes between two services is the kind of bug that
takes a day to find.

*A LIST of dicts is JSON, and it is one-way* (⚑ NLS-P9.1). The morph annotator
writes one — ``Token.analyses``, the ranked readings (LM contracts §1/§5) — and
the dotted-key trick cannot reach it: flattening a list of records means either
parallel arrays keyed by field, which nothing else in the estate reads, or a
nested-map encoding, which §2.1 deliberately does not have. So each record is
``json.dumps``-ed and the feature travels as a list of strings, sorted keys, one
string per reading.

That is a real wart and it is the smallest one available. Two contracts meet
here and disagree: §2.1 says features are data and not objects (P-2), while LM
contracts §5 says the annotator writes a list of records. Refusing to serialise
would mean ``RunPipeline`` fails outright on every Czech morph request; dropping
the feature would be the silent loss this module exists to prevent; widening
``FeatureValue`` would reopen the proto and undo the one decision §2.1 argues
for at length. **Coming back, they stay strings** — the object-ness is exactly
what the wire's value domain does not carry, and a heuristic that re-parsed any
string starting with ``{`` would corrupt a genuine string feature that happened
to look like JSON. In-process consumers (the rule engine, the gazetteer, the
``lemma_any``/``upos_any`` helpers) never cross this boundary and see the
records themselves; ``lemma``, ``upos`` and ``lemmas`` carry the same answers as
scalars for everyone who does.

**Filtering lives here too** (``include_sets``/``include_types``, contracts
§2.2), because a filter applied in the servicer and a filter applied in the
client would be two subtly different filters within a release.
"""

from __future__ import annotations

import json
from collections.abc import Iterable, Mapping, Sequence
from typing import Any

from ttrnlp import proto
from ttrnlp.doc.model import Document

#: Separator for a flattened dict feature (`feats` -> `feats.Case`).
NESTED_SEPARATOR = "."


def _stubs():
    """The generated module, imported on use with an actionable failure.

    Not a module-level import: the core wheel is deliberately dependency-thin
    (⚑NLS-D3) and most consumers never serialise anything. One that does and has
    not asked for the extra should be told which extra, not handed a
    ModuleNotFoundError naming a generated file they have never heard of.

    Resolution — the consumer's stubs first, the wheel's bundled copy second —
    lives in `ttrnlp.proto`, which is also where the reason for that order is
    written down.
    """
    return proto.nlp_pb2()


# ── Python value -> FeatureValue ─────────────────────────────────────────────


def _to_value(value: Any, *, where: str):
    """One feature value, as the proto oneof."""
    nlp_pb2 = _stubs()

    # `bool` before `int`: bool IS an int in Python, and a bool arriving as
    # number_value would come back as 1/0 and stop matching `true`.
    if isinstance(value, bool):
        return nlp_pb2.FeatureValue(bool_value=value)
    if isinstance(value, str):
        return nlp_pb2.FeatureValue(string_value=value)
    if isinstance(value, int | float):
        return nlp_pb2.FeatureValue(number_value=float(value))
    if isinstance(value, Mapping):
        # Only reachable from INSIDE a list — `_flatten` has already dealt with
        # a dict feature at the top level. See the module docstring: one record
        # per string, sorted keys so two runs serialise identically and a golden
        # comparison downstream does not flap.
        return nlp_pb2.FeatureValue(
            string_value=json.dumps(value, sort_keys=True, ensure_ascii=False)
        )
    if isinstance(value, Sequence):
        return nlp_pb2.FeatureValue(
            list_value=nlp_pb2.FeatureValueList(
                items=[
                    _to_value(item, where=f"{where}[{i}]")
                    for i, item in enumerate(value)
                ]
            )
        )
    raise TypeError(
        f"{where}: cannot put a {type(value).__name__} on the wire — a feature "
        "value is a string, a number, a bool, or a list of those (contracts "
        "§2.1). Nested mappings are flattened with a dotted key; anything else "
        "needs a contract change, not a silent drop"
    )


def _flatten(features: Mapping[str, Any], *, where: str) -> dict[str, Any]:
    """Flatten one level of dict-valued features into dotted keys.

    One level, not arbitrary depth: ``Token.feats`` is the only dict the
    importers produce and it holds scalars. Recursing would invent a convention
    for something nothing generates, and the round-trip test would be the only
    thing exercising it.
    """
    flat: dict[str, Any] = {}
    for key, value in features.items():
        if isinstance(value, Mapping):
            for inner, inner_value in value.items():
                if isinstance(inner_value, Mapping):
                    raise TypeError(
                        f"{where}.{key}.{inner}: features nest one level deep at "
                        "most on the wire"
                    )
                flat[f"{key}{NESTED_SEPARATOR}{inner}"] = inner_value
        else:
            flat[key] = value
    return flat


def _features_to_proto(features: Mapping[str, Any], *, where: str) -> dict[str, Any]:
    return {
        key: _to_value(value, where=f"{where}.{key}")
        for key, value in _flatten(features, where=where).items()
    }


# ── FeatureValue -> Python value ─────────────────────────────────────────────


def _from_value(value: Any, *, where: str) -> Any:
    kind = value.WhichOneof("kind")
    if kind == "string_value":
        return value.string_value
    if kind == "bool_value":
        return value.bool_value
    if kind == "number_value":
        number = value.number_value
        # See the module docstring: integral doubles come back as ints, so a
        # pack matching `dep_head: 3` keeps firing across the wire.
        return int(number) if number.is_integer() else number
    if kind == "list_value":
        return [
            _from_value(item, where=f"{where}[{i}]")
            for i, item in enumerate(value.list_value.items)
        ]
    raise ValueError(
        f"{where}: FeatureValue carries no kind — an empty oneof means the "
        "sender wrote a field this build does not know about, and guessing "
        "which would be worse than saying so"
    )


def _features_from_proto(features: Mapping[str, Any], *, where: str) -> dict[str, Any]:
    """Read features back, re-nesting the dotted keys `_flatten` produced."""
    out: dict[str, Any] = {}
    for key, value in features.items():
        parsed = _from_value(value, where=f"{where}.{key}")
        if NESTED_SEPARATOR in key:
            outer, inner = key.split(NESTED_SEPARATOR, 1)
            nested = out.setdefault(outer, {})
            if not isinstance(nested, dict):
                raise ValueError(
                    f"{where}: {outer!r} is both a value and a nested map — the "
                    "sender used a dotted key that collides with a plain one"
                )
            nested[inner] = parsed
        else:
            if isinstance(out.get(key), dict):
                raise ValueError(
                    f"{where}: {key!r} is both a value and a nested map — the "
                    "sender used a dotted key that collides with a plain one"
                )
            out[key] = parsed
    return out


# ── the two public functions ─────────────────────────────────────────────────


def doc_to_proto(
    doc: Document,
    *,
    include_sets: Iterable[str] | None = None,
    include_types: Iterable[str] | None = None,
):
    """A ``Document`` as an ``AnnotatedDocument``.

    Args:
        doc: The document to serialise.
        include_sets: Annotation-set names to include. ``None`` or empty means
            every set — the contracts §2.2 convention, where an empty repeated
            field is "no filter" rather than "nothing".
        include_types: Annotation types to include, same convention.

    Returns:
        An ``AnnotatedDocument``. Sets come out in name order and annotations in
        ``(start, end, id)`` order, so two runs over one document produce equal
        messages — gatenlp's own iteration order is not something to depend on
        for a wire payload people will diff.

    Raises:
        ImportError: If the ``[grpc]`` extra is not installed.
        TypeError: If a feature value has no place in the contract's value
            domain.
    """
    nlp_pb2 = _stubs()

    wanted_sets = set(include_sets or ())
    wanted_types = set(include_types or ())

    sets = []
    # `annset_names()` returns a list and omits the default set until something
    # has been added to it. `""` is added unconditionally so a document whose only
    # annotations are in the default set is not serialised as empty.
    for name in sorted({*doc.annset_names(), ""}):
        if wanted_sets and name not in wanted_sets:
            continue
        annset = doc.annset(name)
        annotations = []
        for ann in sorted(annset, key=lambda a: (a.start, a.end, a.id)):
            if wanted_types and ann.type not in wanted_types:
                continue
            annotations.append(
                nlp_pb2.Annotation(
                    id=ann.id,
                    type=ann.type,
                    char_start=ann.start,
                    char_end=ann.end,
                    features=_features_to_proto(
                        dict(ann.features), where=f"{name or '<default>'}[{ann.id}]"
                    ),
                )
            )
        # An empty set is kept when it was asked for by name, and skipped
        # otherwise: the default set exists on every document, and emitting it
        # empty on every filtered response is noise.
        if annotations or name in wanted_sets:
            sets.append(nlp_pb2.AnnotationSet(name=name, annotations=annotations))

    return nlp_pb2.AnnotatedDocument(
        text=doc.text,
        annotation_sets=sets,
        document_features=_features_to_proto(
            dict(doc.features), where="document_features"
        ),
    )


def doc_from_proto(message) -> Document:
    """An ``AnnotatedDocument`` back into a ``Document``.

    Annotation ids are preserved, not reassigned: a ``QueryPattern`` the rules
    produced is referred to by id in traces and logs, and a client that renumbered
    it would make those references point at something else.
    """
    doc = Document(message.text)
    doc.features.update(
        _features_from_proto(message.document_features, where="document_features")
    )

    for annset_proto in message.annotation_sets:
        annset = doc.annset(annset_proto.name)
        for ann in annset_proto.annotations:
            annset.add(
                ann.char_start,
                ann.char_end,
                ann.type,
                _features_from_proto(
                    ann.features,
                    where=f"{annset_proto.name or '<default>'}[{ann.id}]",
                ),
                annid=ann.id,
            )
    return doc


__all__ = ["doc_from_proto", "doc_to_proto"]
