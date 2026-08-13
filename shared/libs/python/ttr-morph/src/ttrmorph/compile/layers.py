# SPDX-License-Identifier: Apache-2.0
"""Layer source files — the editorial truth (LM contracts §3).

A layer is a YAML file of entries an analyst edits: a lemma, a part of speech,
and either the *pattern* it inflects by or the forms themselves. It is not the
artifact. The artifact (`snapshot.py`) is generated from these and is nobody's
source of truth, which is the whole reason the compact form exists — a reviewer
diffing ``vzor: žena`` against ``vzor: růže`` is reading one decision, while a
reviewer diffing fourteen forms is reading fourteen consequences of it.

**Two provenance vocabularies, and they are not the same word twice.** An
*entry* carries editorial provenance (``wiktionary`` | ``cac`` | ``manual`` |
``llm``) — where the editorial decision came from, which is what the licence
boundary is enforced against. A *snapshot row* carries runtime provenance
(``lexicon`` | ``statistical`` | ``provisional``, contracts §1) — how much
weight a consumer may put on the answer. The compiler maps the first onto the
second; see `snapshot.py`.

**The licence boundary is checked here, not at review time** (C-F3, S-2). A
share-alike layer may hold only ``wiktionary``/``cac`` entries and must carry an
`Attribution` block, so that the CC BY-SA material stays a separable member file
with a NOTICE of its own and never mixes into suite-licensed output. Getting
that wrong is not a bug that shows up in a test run — it shows up in a licence
audit, months later — so it is `LM-MORPH-004` and it is an ERROR.

⚑ **Two keys contracts §3 does not list, added here because the tasks require
what they carry**: ``ne_exception`` (which entries the runtime's ne-/nej- strip
must not touch — *nemoc* is not *ne* + *moc*) and ``provisional`` (the Q-7
narrow path, legal only when compiling a world overlay). Both are facts about a
lexeme, so they sit on the entry rather than in a second list at the top of the
file that is one edit away from disagreeing with it.

⚑ **Layer-file errors report as `LM-MORPH-001`.** §9 defines exactly seven
codes and none of them is "this input file is malformed"; 001 is the closest —
"this cannot be read, so nothing downstream is trustworthy" — and minting an
eighth code would mean the loader, the proto and the studio all learn a code
only one tool can emit.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Literal

import yaml
from pydantic import BaseModel, ConfigDict, ValidationError, model_validator
from ttrnlp.morph.diag import (
    LM_MORPH_001,
    LM_MORPH_004,
    LM_MORPH_005,
    Diagnostic,
    error,
    info,
)
from ttrnlp.packs.diag import diagnostics_from_pydantic

from ttrmorph.engine import EngineError, generate, load

#: Suite-owned material: hand seed, domain layers, anything authored here.
LICENSE_SUITE = "suite"

#: Share-alike licences. A layer under one of these compiles to its own member
#: file and needs its own NOTICE section (contracts §10).
SHARE_ALIKE_LICENSES = frozenset({"CC-BY-SA-4.0"})

#: ``world:<id>`` — a world's own layer, compiled as an overlay.
WORLD_LICENSE_PREFIX = "world:"

#: Editorial provenance (contracts §3), on every entry.
EDITORIAL_PROVENANCES = ("wiktionary", "cac", "manual", "llm")

#: The only provenance a share-alike layer may carry. An entry a human wrote or
#: an LLM proposed is *ours*; filing it under a CC BY-SA layer would claim the
#: share-alike source said something it never said, and would drag suite work
#: under share-alike terms in the same move.
SHARE_ALIKE_PROVENANCES = frozenset({"wiktionary", "cac"})

#: ``subvzor:<name>`` in the flags list (contracts §4).
SUBVZOR_FLAG = "subvzor:"

_ID = re.compile(r"^[a-z0-9-]+$")

EditorialProvenance = Literal["wiktionary", "cac", "manual", "llm"]


class _Base(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Attribution(_Base):
    """What the NOTICE needs, per share-alike layer (contracts §10, S-2).

    Every field is required and none of them has a default. A NOTICE assembled
    from partial blocks is worse than no NOTICE: it looks like compliance and
    is not, and the missing half is exactly the half an auditor asks about.
    """

    #: Human-readable name of the upstream source.
    source: str
    #: Where a reader goes to see the original.
    url: str
    #: SPDX-ish licence id, matching the layer's own ``license``.
    license: str
    #: The licence text.
    license_url: str
    #: When the material was taken (``YYYY-MM-DD``). Share-alike attribution has
    #: to survive the upstream changing after we copied.
    extracted: str
    #: What we did to it — CC BY-SA requires that adaptations say so.
    transformation: str


class FormSpec(_Base):
    """One surface form and the reading it realises."""

    form: str
    #: UD atoms, ``|``-joined and already in the tagset's order.
    feats: str = ""


class Entry(_Base):
    """One lexeme (contracts §3). Identity is (lemma, upos)."""

    lemma: str
    upos: str

    #: The compact form: a pattern the engine expands.
    vzor: str | None = None
    flags: tuple[str, ...] = ()

    #: The explicit form: forms listed outright (irregulars, importer misses).
    forms: tuple[FormSpec, ...] = ()

    #: Decomposition (B-O4) — ``abych`` is ``(aby, bych)``.
    parts: tuple[str, ...] = ()

    #: Optional; the compiler fills it from the frequency table otherwise.
    rank: int | None = None

    provenance: EditorialProvenance

    #: The runtime's ne-/nej- strip must not touch this lexeme (⚑ above).
    ne_exception: bool = False

    #: Q-7: an unverified generated entry. Overlay compiles only.
    provisional: bool = False

    @model_validator(mode="before")
    @classmethod
    def _yaml_booleans(cls, data):
        """Catch the words YAML 1.1 turns into booleans, by name.

        ``on`` is a Czech pronoun and a YAML boolean, so ``lemma: on`` reaches
        pydantic as ``True`` and reports as "Input should be a valid string" —
        which is true and tells an analyst nothing. ``no``, ``off``, ``y`` and
        ``n`` have the same problem in other languages. The fix is a quote, and
        this says so.
        """
        if not isinstance(data, dict):
            return data
        for key in ("lemma", "upos"):
            if isinstance(data.get(key), bool):
                raise ValueError(
                    f"'{key}' is the boolean {data[key]} — YAML 1.1 reads "
                    "'on'/'off'/'yes'/'no'/'y'/'n' as booleans, and 'on' is a "
                    "Czech pronoun. Quote it: lemma: \"on\""
                )
        for form in data.get("forms") or ():
            if isinstance(form, dict) and isinstance(form.get("form"), bool):
                raise ValueError(
                    f"a form is the boolean {form['form']} — quote it (YAML 1.1 "
                    "reads 'on'/'off'/'yes'/'no'/'y'/'n' as booleans)"
                )
        return data

    @model_validator(mode="after")
    def _shape(self) -> Entry:
        if not self.vzor and not self.forms:
            raise ValueError(
                f"entry ({self.lemma}, {self.upos}) has neither 'vzor' nor "
                "'forms' — an entry that names no forms and no way to make "
                "them contributes nothing to the artifact"
            )
        return self

    @property
    def identity(self) -> tuple[str, str]:
        return (self.lemma, self.upos)


class Layer(_Base):
    """One layer file, parsed (contracts §3)."""

    layer: str
    version: int = 1
    language: str
    license: str = LICENSE_SUITE
    attribution: Attribution | None = None
    entries: tuple[Entry, ...] = ()

    @model_validator(mode="after")
    def _identity(self) -> Layer:
        if not _ID.match(self.layer):
            raise ValueError(
                f"layer id {self.layer!r} must match {_ID.pattern} — the id is "
                "a column value in every row it produces and a filename in the "
                "release assets"
            )
        if not (
            self.license == LICENSE_SUITE
            or self.license in SHARE_ALIKE_LICENSES
            or self.license.startswith(WORLD_LICENSE_PREFIX)
        ):
            raise ValueError(
                f"unknown license {self.license!r} — contracts §3 has "
                f"{LICENSE_SUITE!r}, {sorted(SHARE_ALIKE_LICENSES)} and "
                f"'{WORLD_LICENSE_PREFIX}<id>'"
            )
        return self

    @property
    def share_alike(self) -> bool:
        return self.license in SHARE_ALIKE_LICENSES

    @property
    def world(self) -> str:
        if self.license.startswith(WORLD_LICENSE_PREFIX):
            return self.license[len(WORLD_LICENSE_PREFIX) :]
        return ""


# ── reading ──────────────────────────────────────────────────────────────────


def read_layer(path: str | Path) -> tuple[Layer | None, list[Diagnostic]]:
    """Parse one layer file. Never raises; a ``None`` layer means unusable."""
    source = str(path)
    try:
        raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    except OSError as exc:
        return None, [error(LM_MORPH_001, f"cannot read layer: {exc}", source=source)]
    except yaml.YAMLError as exc:
        return None, [error(LM_MORPH_001, f"not valid YAML: {exc}", source=source)]

    if not isinstance(raw, dict):
        return None, [
            error(
                LM_MORPH_001,
                "a layer file is a mapping with 'layer', 'language' and "
                f"'entries', found {type(raw).__name__}",
                source=source,
            )
        ]

    try:
        layer = Layer.model_validate(raw)
    except ValidationError as exc:
        return None, diagnostics_from_pydantic(exc, code=LM_MORPH_001, source=source)

    return layer, _check(layer, source)


def _check(layer: Layer, source: str) -> list[Diagnostic]:
    """The checks the schema cannot express: licence boundary, duplicates, engine."""
    diagnostics: list[Diagnostic] = []

    if layer.share_alike:
        if layer.attribution is None:
            diagnostics.append(
                error(
                    LM_MORPH_004,
                    f"layer {layer.layer!r} is {layer.license} and carries no "
                    "'attribution' block — a share-alike layer with no NOTICE "
                    "material cannot be published at all (S-2), and finding "
                    "that out at release time means finding it out too late",
                    source=source,
                )
            )
        elif layer.attribution.license != layer.license:
            diagnostics.append(
                error(
                    LM_MORPH_004,
                    f"layer {layer.layer!r} declares license "
                    f"{layer.license!r} but its attribution block says "
                    f"{layer.attribution.license!r} — the NOTICE would name a "
                    "licence the artifact was not compiled under",
                    source=source,
                )
            )

    seen: dict[tuple[str, str], int] = {}
    for index, entry in enumerate(layer.entries):
        where = f"$.entries[{index}] ({entry.lemma}, {entry.upos})"

        if entry.identity in seen:
            diagnostics.append(
                error(
                    LM_MORPH_001,
                    f"{where}: already defined at "
                    f"$.entries[{seen[entry.identity]}] — entry identity is "
                    "(lemma, upos) and a file that defines one twice has no "
                    "meaning to merge",
                    source=source,
                )
            )
        seen.setdefault(entry.identity, index)

        if layer.share_alike and entry.provenance not in SHARE_ALIKE_PROVENANCES:
            diagnostics.append(
                error(
                    LM_MORPH_004,
                    f"{where}: provenance {entry.provenance!r} in a "
                    f"{layer.license} layer — only "
                    f"{sorted(SHARE_ALIKE_PROVENANCES)} may live here. Suite "
                    "work filed under a share-alike layer would both "
                    "misattribute it upstream and pull our own material under "
                    "share-alike terms",
                    source=source,
                )
            )

        diagnostics.extend(_check_entry(entry, where, source))

    return diagnostics


def _check_entry(entry: Entry, where: str, source: str) -> list[Diagnostic]:
    """Engine-side checks for one entry: the pattern exists, and it agrees."""
    diagnostics: list[Diagnostic] = []

    if entry.vzor and entry.forms:
        diagnostics.append(
            info(
                LM_MORPH_001,
                f"{where}: has both 'vzor' and 'forms'; the forms win "
                "(contracts §3) and the pattern is kept only as a spot-check",
                source=source,
            )
        )

    if not entry.vzor:
        return diagnostics

    try:
        vzor, flags = resolve_vzor(entry)
        produced = generate(entry.lemma, vzor, flags)
    except EngineError as exc:
        diagnostics.append(error(LM_MORPH_001, f"{where}: {exc}", source=source))
        return diagnostics

    if not entry.forms:
        return diagnostics

    unmade = unproduced(entry, produced)
    if unmade:
        missing = sorted(
            f"{form} ({feats or 'any reading'})" for form, feats in unmade
        )
        diagnostics.append(
            info(
                LM_MORPH_005,
                f"{where}: pattern {vzor!r} does not regenerate "
                f"{len(missing)} declared form(s): {', '.join(missing[:5])}"
                f"{'…' if len(missing) > 5 else ''} — the forms are what "
                "compiles, and the pattern is dropped from the row rather than "
                "recorded as this entry's paradigm, because a vzor that does "
                "not reproduce the forms is not the vzor this lexeme has",
                source=source,
            )
        )
    return diagnostics


def unproduced(
    entry: Entry, produced: set[tuple[str, str]]
) -> list[tuple[str, str]]:
    """The declared forms this entry's pattern does not make (contracts §3).

    ⚑ **An empty ``feats`` means "this form, whatever it realises"** — not
    "this form, tagged with the empty string". `FormSpec.feats` defaults to
    empty and the importers' spot-checks are written exactly that way: two or
    three surface forms, untagged, listed to show the pattern was chosen
    correctly. Compared as a bare ``(form, feats)`` pair against a paradigm
    whose every reading carries UD atoms, such a form can never match — so an
    entry read as a pattern that regenerates *none* of its own spot-checks, and
    both callers took the disagreement branch: `read_layer` reported
    `LM-MORPH-005` against a pattern that is in fact correct, and `_expand`
    dropped the vzor and emitted the one or two listed forms in place of the
    dozen the pattern makes. As an INFO on an otherwise green compile, that is
    a lexeme quietly losing its paradigm.

    Shared by the diagnostic and the routing decision because they are the same
    question. Answered separately, the artifact and the diagnostics that
    describe it drift.
    """
    forms = {form for form, _ in produced}
    missing = {
        (spec.form, spec.feats)
        for spec in entry.forms
        if (spec.form, spec.feats) not in produced
        if spec.feats or spec.form not in forms
    }
    return sorted(missing)


def resolve_vzor(entry: Entry) -> tuple[str, tuple[str, ...]]:
    """``(vzor, flags)`` for the engine, resolving ``subvzor:`` sugar.

    Contracts §4 lists ``subvzor:<name>`` among the flags, and the engine has no
    such flag: a sub-vzor changes which *slots* a pattern has, and flags are
    stem transforms, so the tables carry sub-vzory as patterns in their own
    right (``hrad-foreign`` has ``hrad`` as its parent). The sugar is accepted
    because an analyst reading contracts §3 will write it, and it is normalised
    away here — the emitted row names the sub-vzor in the ``vzor`` column, which
    is the complete answer and the one `classify` gives back.
    """
    sugar = [flag for flag in entry.flags if flag.startswith(SUBVZOR_FLAG)]
    subvzor = sugar[0][len(SUBVZOR_FLAG) :] if sugar else ""
    flags = tuple(flag for flag in entry.flags if not flag.startswith(SUBVZOR_FLAG))
    if not subvzor:
        return entry.vzor or "", flags

    tables = load()
    child = tables.vzory.get(subvzor)
    if child is not None and entry.vzor and child.parent not in (None, entry.vzor):
        raise EngineError(
            f"sub-pattern {subvzor!r} belongs to {child.parent!r}, not to the "
            f"declared vzor {entry.vzor!r}"
        )
    return subvzor, flags


# ── the validate lane ────────────────────────────────────────────────────────


def validate_layers(paths: list[str] | list[Path]) -> list[Diagnostic]:
    """Every diagnostic the compiler would raise about these files, raising none.

    This is `read_layer` over each path and nothing else, deliberately: a layer
    that validates on an analyst's laptop must compile in CI, and the only way
    to promise that is for both to run the same reader. Cross-layer checks
    (merge order, provisional-in-core) belong to a *compile*, because they
    depend on which layers are being compiled together and in what order — a
    single file cannot be judged on them.
    """
    diagnostics: list[Diagnostic] = []
    for path in paths:
        _, found = read_layer(path)
        diagnostics.extend(found)
    return diagnostics


__all__ = [
    "EDITORIAL_PROVENANCES",
    "LICENSE_SUITE",
    "SHARE_ALIKE_LICENSES",
    "SHARE_ALIKE_PROVENANCES",
    "SUBVZOR_FLAG",
    "WORLD_LICENSE_PREFIX",
    "Attribution",
    "Entry",
    "FormSpec",
    "Layer",
    "read_layer",
    "resolve_vzor",
    "unproduced",
    "validate_layers",
]
