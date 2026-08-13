# SPDX-License-Identifier: Apache-2.0
"""Loading the paradigm tables — the only place that reads the data (LM-2).

Everything language-specific enters the engine here and is frozen on the way
in. A `Tables` is immutable and cached per language, so `generate` can be
called a hundred thousand times during a compile without re-parsing anything,
and two calls in the same process cannot see different tables.

The one structural transformation this module does is resolving sub-vzory. A
sub-vzor is a parent plus a narrowing (overridden slots, an implied flag, a
different citation ending, a restriction to part of the parent's table), and it
is resolved *at load time* into a complete pattern. Resolving at generate time
would mean the parent could be edited in a way that silently changes a child's
paradigm without anything failing; here, the merge happens once and the result
is what the golden tests assert against.
"""

from __future__ import annotations

import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from functools import cache
from importlib.resources import files
from types import MappingProxyType
from typing import Any

import yaml

from ttrmorph.engine.errors import EngineError
from ttrmorph.engine.transforms import Transform, build

#: Where the tables live. Under `seed/` because they are seed material, not
#: engine code — the driver has no language in it and a second language is a
#: second file (LM-2).
DATA_PACKAGE = "ttrmorph.seed"
DATA_PATH = ("data", "{lang}", "vzory.yaml")


@dataclass(frozen=True)
class Slot:
    """One cell of a paradigm: canonical UD feats and the endings that fill it.

    Several endings because the language has genuine doublets, and `generate`
    returns a set of (form, feats) pairs rather than a mapping from feats to
    one form. A slot that pretended to be single-valued would force the tables
    to pick a winner between two forms that native speakers both write.
    """

    feats: str
    endings: tuple[str, ...]


@dataclass(frozen=True)
class Vzor:
    """One fully resolved inflection pattern."""

    name: str
    upos: str
    strip: tuple[str, ...]
    slots: tuple[Slot, ...]
    lemma_feats: str
    implied_flags: tuple[str, ...]
    hints: Mapping[str, Any]
    parent: str | None = None


@dataclass(frozen=True)
class Tables:
    """Every pattern, flag and spelling rule for one language."""

    language: str
    version: int
    spelling: tuple[tuple[re.Pattern, str], ...]
    flag_order: tuple[str, ...]
    flags: Mapping[str, Transform]
    vzory: Mapping[str, Vzor]

    def spell(self, word: str) -> str:
        """Apply the orthographic rules to a joined stem+ending.

        This runs on every generated form, which is what lets the paradigm
        tables stay purely additive: what looks like irregularity in a school
        table is mostly rules about which vowel may follow which consonant, and
        those rules are regular and shared by every pattern.
        """
        for pattern, replacement in self.spelling:
            word = pattern.sub(replacement, word)
        return word

    def order_flags(self, flags: Sequence[str]) -> tuple[str, ...]:
        """Canonical application order, deduplicated.

        An entry that declares two flags generates the same paradigm whichever
        order the analyst typed them in. A paradigm that depended on that order
        would not be a function of (lemma, vzor, flags), and `classify` — which
        searches flag *sets* — could not be its inverse.
        """
        unknown = [f for f in flags if f not in self.flags]
        if unknown:
            raise EngineError(f"unknown flags {unknown!r}")
        return tuple(f for f in self.flag_order if f in set(flags))


def load(lang: str = "cs") -> Tables:
    """The tables for one language, parsed once per process."""
    return _load_cached(lang)


@cache
def _load_cached(lang: str) -> Tables:
    raw = yaml.safe_load(_read(lang))
    flags = {name: build(name, spec) for name, spec in raw["flags"].items()}
    spelling = tuple(
        (re.compile(rule["pattern"]), rule["replace"]) for rule in raw["spelling"]
    )
    flag_order = tuple(raw["flag_order"])
    missing = set(flags) - set(flag_order)
    if missing:
        raise EngineError(
            f"flags {sorted(missing)} are not in flag_order; every flag needs a "
            "position or its application order is whatever the file happens to "
            "say"
        )

    vzory: dict[str, Vzor] = {}
    raw_slots: dict[str, dict[str, list[str]]] = {}
    raw_specs: dict[str, Mapping] = {}

    for name, spec in raw["vzory"].items():
        raw_specs[name] = spec
        raw_slots[name] = {k: list(v) for k, v in spec["slots"].items()}
        always = spec.get("feats_always", "")
        vzory[name] = _resolve(name, spec, raw_slots[name], always)

    for name, spec in (raw.get("subvzory") or {}).items():
        parent = spec.get("parent")
        if parent not in raw_specs:
            raise EngineError(f"sub-vzor {name!r} names unknown parent {parent!r}")
        merged = dict(raw_slots[parent])
        merged.update({k: list(v) for k, v in (spec.get("slots") or {}).items()})
        inherited = dict(raw_specs[parent])
        inherited.update(spec)
        always = inherited.get("feats_always", "")
        keep = tuple(spec.get("only_feats") or ())
        vzory[name] = _resolve(name, inherited, merged, always, keep, parent)

    return Tables(
        language=raw["language"],
        version=int(raw["version"]),
        spelling=spelling,
        flag_order=flag_order,
        flags=MappingProxyType(flags),
        vzory=MappingProxyType(vzory),
    )


def _resolve(
    name: str,
    spec: Mapping,
    slots: Mapping[str, list[str]],
    always: str,
    only_feats: tuple[str, ...] = (),
    parent: str | None = None,
) -> Vzor:
    built: list[Slot] = []
    lemma_key = spec["lemma_slot"]
    lemma_feats = ""
    for key, endings in slots.items():
        feats = _merge_feats(key, always)
        if only_feats and not all(atom in feats.split("|") for atom in only_feats):
            continue
        built.append(Slot(feats, tuple(endings)))
        if key == lemma_key:
            lemma_feats = feats
    if not lemma_feats:
        raise EngineError(
            f"vzor {name!r} declares lemma_slot {lemma_key!r}, which is not one "
            "of its slots; without it the citation form has no place in its own "
            "paradigm and classify has nothing to key on"
        )
    strip = tuple((spec.get("stem") or {}).get("strip") or [""])
    return Vzor(
        name=name,
        upos=spec.get("upos", ""),
        strip=strip,
        slots=tuple(built),
        lemma_feats=lemma_feats,
        implied_flags=tuple(spec.get("implied_flags") or ()),
        hints=MappingProxyType(dict(spec.get("hints") or {})),
        parent=parent,
    )


def _merge_feats(key: str, always: str) -> str:
    """Slot key plus the vzor's constant features, canonically ordered.

    Sorted because the feats string is compared — by `classify`, by the
    importers, by the snapshot's row identity — and two spellings of the same
    feature set that never compare equal would be a bug that only shows up on
    the rows nobody looked at.
    """
    atoms = {atom for atom in (key.split("|") + always.split("|")) if atom}
    return "|".join(sorted(atoms))


def _read(lang: str) -> str:
    path = files(DATA_PACKAGE)
    for part in DATA_PATH:
        path = path / part.format(lang=lang)
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError as exc:
        raise EngineError(f"no paradigm tables for language {lang!r}") from exc


__all__ = ["DATA_PACKAGE", "Slot", "Tables", "Vzor", "load"]
