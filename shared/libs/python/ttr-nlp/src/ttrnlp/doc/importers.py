# SPDX-License-Identifier: Apache-2.0
"""Engine results -> gatenlp ``Document``.

This is the front door of the suite. Everything after it — gazetteers, rules,
the QueryPattern contract, the wire format — is offsets and features on the
document this module builds, so its only real obligation is to say exactly what
the engines said, at exactly the offsets they said it at.

**Built from backend JSON, deliberately.** gatenlp ships ``lib_stanza`` and
``lib_spacy``, which would do much of this for us; both ``import stanza`` /
``import spacy`` at module top, which would drag a model stack into the
engine-free front (⚑NLS-D3). So the annotations are constructed here from the
uniform JSON the backends already return, and a test enforces that this stays
true.

**The uniform result shape.** All four adapters converge on one shape — natively
for Stanza and spaCy, via their vertical/CoNLL parsers for MorphoDiTa and
NameTag 3::

    {"engine": "stanza",
     "tokens":    [{"text","charStart","charEnd","lemma","upos","xpos",
                    "feats","depHead","depRelation"}],
     "entities":  [{"text","label","charStart","charEnd",
                    "normalizedValue","sourceEngine"}],
     "sentences": [{"charStart","charEnd"}],
     "modelVersion": "..."}

**What lands in the document.** One default annotation set holding:

===============  ==========================================================
``Token``        ``text``, ``lemma``, ``upos``, ``xpos``, ``feats`` (dict),
                 ``dep_head``, ``dep_relation``, ``engine``
``Sentence``     ``engine``
*label*          one annotation **typed by the NER label itself** —
                 ``ORGANIZATION``, ``ORG``, … — with ``text``, ``engine`` and,
                 for NameTag, the raw ``cnec`` tag
===============  ==========================================================

Token text is copied into a ``text`` feature rather than left implicit in the
span. GATE does the same (``Token.string``), and for the same reason: it lets
the DSL's ``text:`` shorthand be an ordinary feature constraint instead of a
special case in the matcher.

Feature names are snake_case (``dep_head``, not ``depHead``) — these are what
pack authors type, and they sit next to ``lemma`` and ``upos``.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from typing import Any

from ttrnlp.doc.labels import cnec_to_universal, parse_cnec
from ttrnlp.doc.model import Document

#: Keys that identify a bare engine result, to tell one apart from a mapping of
#: engine name -> result.
_RESULT_KEYS = frozenset({"tokens", "entities", "sentences"})

TOKEN_TYPE = "Token"
SENTENCE_TYPE = "Sentence"


def _normalise_results(
    engine_results: Iterable[Mapping[str, Any]] | Mapping[str, Any],
) -> list[tuple[str, Mapping[str, Any]]]:
    """Accept any of the three shapes callers actually hold.

    A sequence of results, a mapping of engine name to result, or a single
    result on its own. The mapping form is distinguished by the absence of the
    result keys at the top level, which is unambiguous for every real payload.
    """
    if isinstance(engine_results, Mapping):
        if _RESULT_KEYS & engine_results.keys():
            results: Iterable[Mapping[str, Any]] = [engine_results]
        else:
            return [
                (str(result.get("engine") or name), result)
                for name, result in engine_results.items()
            ]
    else:
        results = engine_results
    return [(str(r.get("engine") or ""), r) for r in results]


def _token_features(token: Mapping[str, Any], engine: str) -> dict[str, Any]:
    features: dict[str, Any] = {"text": token.get("text", ""), "engine": engine}
    for key in ("lemma", "upos", "xpos"):
        value = token.get(key) or ""
        if value:
            features[key] = value
    feats = token.get("feats") or {}
    if feats:
        features["feats"] = dict(feats)
    # `depHead` of 0 means "root", so it cannot be tested for truthiness. Gate
    # both dependency features on the relation, which is only present when a
    # parse actually ran.
    relation = token.get("depRelation") or ""
    if relation:
        features["dep_relation"] = relation
        features["dep_head"] = int(token.get("depHead", 0))
    return features


def _entity_type_and_features(
    entity: Mapping[str, Any], engine: str
) -> tuple[str, dict[str, Any]]:
    features: dict[str, Any] = {"text": entity.get("text", ""), "engine": engine}
    cnec = parse_cnec(str(entity.get("normalizedValue") or ""))

    if cnec is not None:
        # NameTag: derive the type from the tag rather than trusting `label`.
        # The tag is the engine's actual output; the label is an adapter's
        # interpretation of it, and a stale one would mistype the annotation so
        # that the rule wanting it simply never fires.
        anntype = cnec_to_universal(cnec)
        if cnec:
            features["cnec"] = cnec
        return anntype, features

    label = str(entity.get("label") or "")
    if not label:
        # An entity with neither tag nor label is a backend bug. Keep it, typed
        # coarsely — dropping something the engine found is the worse failure.
        return "MISC", features
    normalized = str(entity.get("normalizedValue") or "")
    if normalized:
        features["normalized_value"] = normalized
    return label, features


def build_document(
    text: str,
    engine_results: Iterable[Mapping[str, Any]] | Mapping[str, Any],
    *,
    language: str,
) -> Document:
    """Build a ``Document`` from one or more engine results.

    Args:
        text: The analysed text. Offsets in the results index into *this*
            string, as Python character indices — not bytes, which matters for
            Czech (``zákazníka`` is 9 characters and 11 bytes).
        engine_results: Results in the uniform shape above; a sequence, a
            mapping keyed by engine name, or a single result.
        language: Language code, recorded as a document feature.

    Returns:
        A ``Document`` whose default annotation set holds every token, sentence
        and entity the engines reported.
    """
    doc = Document(text)
    annset = doc.annset("")

    results = _normalise_results(engine_results)
    model_versions: dict[str, str] = {}

    for engine, result in results:
        model_version = str(result.get("modelVersion") or "")
        if engine and model_version:
            model_versions[engine] = model_version

        for token in result.get("tokens") or []:
            annset.add(
                int(token["charStart"]),
                int(token["charEnd"]),
                TOKEN_TYPE,
                _token_features(token, engine),
            )

        for sentence in result.get("sentences") or []:
            annset.add(
                int(sentence["charStart"]),
                int(sentence["charEnd"]),
                SENTENCE_TYPE,
                {"engine": engine},
            )

        for entity in result.get("entities") or []:
            anntype, features = _entity_type_and_features(entity, engine)
            annset.add(
                int(entity["charStart"]),
                int(entity["charEnd"]),
                anntype,
                features,
            )

    doc.features["language"] = language
    doc.features["engines"] = [engine for engine, _ in results if engine]
    doc.features["model_versions"] = model_versions
    return doc


__all__ = ["SENTENCE_TYPE", "TOKEN_TYPE", "build_document"]
