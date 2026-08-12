# SPDX-License-Identifier: Apache-2.0
"""The flag transforms — six kinds, all parameterized from the tables (LM-2).

A transform takes the stem and the ending a slot is about to join and returns
the pair it should actually join. That signature is the reason the driver has
no language in it: every alternation Czech school grammar calls a "vowel that
drops out" or "a hard consonant softening" is a rewrite of the stem *given the
ending*, and which rewrite happens is a lookup in the tables.

Two of the six are not stem rewrites at all and are handled by the generator
rather than here, because they change the shape of the paradigm rather than the
shape of a stem:

``invariant``
    every slot yields the citation form;
``add-lemma``
    every slot yields its inflected form *and* the citation form.

They still declare a `kind` in the tables so that the inventory is one list,
and `Transform.reshapes` is how the generator asks which ones it must handle.
"""

from __future__ import annotations

import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field

from ttrmorph.engine.errors import EngineError

#: Kinds the generator implements itself (see the module docstring).
RESHAPING_KINDS = frozenset({"invariant", "add-lemma"})


@dataclass(frozen=True)
class Transform:
    """One flag, compiled from its table entry."""

    name: str
    kind: str
    _apply: object = field(repr=False, default=None)
    _applicable: object = field(repr=False, default=None)

    @property
    def reshapes(self) -> bool:
        return self.kind in RESHAPING_KINDS

    def apply(self, stem: str, ending: str) -> tuple[str, str]:
        if self._apply is None:
            return stem, ending
        return self._apply(stem, ending)

    def applicable(self, stem: str) -> bool:
        """Could this flag change anything for this stem?

        Only `classify` asks. Searching every subset of the whole inventory is
        the honest reading of "find the (vzor, flags) that reproduce this
        table", but most flags cannot possibly apply to most stems, and the
        subsets that survive this question are few enough that the search stays
        exact while running in milliseconds.
        """
        if self._applicable is None:
            return True
        return self._applicable(stem)


def build(name: str, spec: Mapping) -> Transform:
    """Compile one flag's table entry into a `Transform`."""
    kind = spec.get("kind")
    builder = _BUILDERS.get(kind)
    if builder is None:
        raise EngineError(
            f"flag {name!r} declares unknown kind {kind!r}; the driver "
            f"implements {sorted(_BUILDERS)} and a seventh needs a driver "
            "change, which is the point of the list being closed"
        )
    return builder(name, spec)


# ── stem-allomorph ───────────────────────────────────────────────────────────


def _stem_allomorph(name: str, spec: Mapping) -> Transform:
    """A stem with two shapes, chosen by whether an ending follows.

    One flag covers both directions of the same alternation. Whether the
    citation form happens to carry the vowel is an accident of which slot the
    dictionary shows, and asking an analyst to classify that accident into two
    different flag names would be asking them to know the answer already.
    """
    detect = re.compile(spec["detect"])
    short_repl = spec["short"]
    insert = re.compile(spec["insert"])
    long_repl = spec["long"]

    def shapes(stem: str) -> tuple[str, str]:
        if detect.match(stem):
            return stem, detect.sub(short_repl, stem)
        return insert.sub(long_repl, stem), stem

    def apply(stem: str, ending: str) -> tuple[str, str]:
        long_form, short_form = shapes(stem)
        return (long_form if ending == "" else short_form), ending

    def applicable(stem: str) -> bool:
        long_form, short_form = shapes(stem)
        return long_form != short_form

    return Transform(name, spec["kind"], apply, applicable)


# ── stem-vowel-map ───────────────────────────────────────────────────────────


def _stem_vowel_map(name: str, spec: Mapping) -> Transform:
    """Rewrite the last mapped vowel of the stem.

    "Last" rather than "first" because the alternating vowel is the one in the
    syllable the ending attaches to; a prefix that happens to carry a long
    vowel is not part of the alternation.
    """
    mapping = dict(spec["map"])
    keys = _longest_first(mapping)
    only_with_ending = spec.get("when") == "ending-nonempty"
    only_without_ending = spec.get("when") == "ending-empty"

    def rewrite(stem: str) -> str:
        for i in range(len(stem), -1, -1):
            for key in keys:
                if stem.startswith(key, i):
                    return stem[:i] + mapping[key] + stem[i + len(key) :]
        return stem

    def apply(stem: str, ending: str) -> tuple[str, str]:
        if only_with_ending and ending == "":
            return stem, ending
        if only_without_ending and ending != "":
            return stem, ending
        return rewrite(stem), ending

    return Transform(name, spec["kind"], apply, lambda s: rewrite(s) != s)


# ── stem-final-map ───────────────────────────────────────────────────────────


def _stem_final_map(name: str, spec: Mapping) -> Transform:
    """Rewrite the stem-final consonant when the ending starts with a trigger.

    Triggered by the ending rather than by the slot, which is why one flag
    serves alternations that a school table presents as unrelated: the same
    rewrite happens before the dative of one gender and the nominative plural
    of another because both endings begin with the same vowel.

    The resulting spelling — whether the vowel keeps its hook — is not this
    transform's business. That is orthography, and it is in the spelling rules
    where every junction in the language goes through it once.
    """
    mapping = dict(spec["map"])
    keys = _longest_first(mapping)
    triggers = tuple(spec["triggers"])

    def rewrite(stem: str) -> str:
        for key in keys:
            if stem.endswith(key):
                return stem[: -len(key)] + mapping[key]
        return stem

    def apply(stem: str, ending: str) -> tuple[str, str]:
        if not ending.startswith(triggers):
            return stem, ending
        return rewrite(stem), ending

    return Transform(name, spec["kind"], apply, lambda s: rewrite(s) != s)


# ── stem-strip ───────────────────────────────────────────────────────────────


def _stem_strip(name: str, spec: Mapping) -> Transform:
    """Drop a citation-only suffix from the stem wherever an ending follows."""
    suffixes = _by_length(spec["suffixes"])
    only_with_ending = spec.get("when") == "ending-nonempty"

    def rewrite(stem: str) -> str:
        for suffix in suffixes:
            if stem.endswith(suffix) and len(stem) > len(suffix):
                return stem[: -len(suffix)]
        return stem

    def apply(stem: str, ending: str) -> tuple[str, str]:
        if only_with_ending and ending == "":
            return stem, ending
        return rewrite(stem), ending

    return Transform(name, spec["kind"], apply, lambda s: rewrite(s) != s)


# ── the two the generator handles ────────────────────────────────────────────


def _reshaping(name: str, spec: Mapping) -> Transform:
    return Transform(name, spec["kind"], None, None)


_BUILDERS = {
    "stem-allomorph": _stem_allomorph,
    "stem-vowel-map": _stem_vowel_map,
    "stem-final-map": _stem_final_map,
    "stem-strip": _stem_strip,
    "invariant": _reshaping,
    "add-lemma": _reshaping,
}


def _longest_first(mapping: Mapping[str, str]) -> tuple[str, ...]:
    return _by_length(mapping.keys())


def _by_length(values) -> tuple[str, ...]:
    """Longest key first, then declaration order.

    A two-character key must win over a one-character key that is its tail, or
    the digraph is rewritten as if it were the single consonant it ends with.
    """
    ordered: Sequence[str] = list(values)
    return tuple(sorted(ordered, key=lambda k: (-len(k), ordered.index(k))))


__all__ = ["RESHAPING_KINDS", "Transform", "build"]
