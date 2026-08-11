# SPDX-License-Identifier: Apache-2.0
"""Gazetteer-list interchange — one YAML file, one list (NL-17, contracts §4).

A list is a flat table of terms plus the features each term contributes, and
that is deliberately all it is. The interesting design decision behind this file
is what it *refuses* to carry: no scores, no thresholds, no edit distances. The
suite's gazetteer is a deterministic longest-match trie, and the approximate
line (the glossary service, `lex-matcher-core`, `fuzzy-common`) lives world-side
where a human can see its thresholds. A `score:` key here would quietly move
that decision into infrastructure nobody reviews.

Why an interchange format at all, rather than each world's own file shape: the
lists that matter are *generated* — a tatrman model's lexicon becomes lists
(NLS-P4's exporter), a DFP glossary manifest becomes lists — and hand-authored
lists are first-class next to them (C-F2-γ). One shape means one validator, one
loader, and a diff a reviewer can read either way.

The shape (contracts §4)::

    list: dfp-entity-aliases
    version: 1
    matching: lemma                 # exact | ci | lemma | fold-diacritics
    annotation: Lookup              # optional; emitted type
    source:
      world: dfp
      origin: "glossary@2026-08-01"
    entries:
      - term: faktura
        features: {kind: entity_alias, entity: faktura}

**`matching` is per list, not per entry.** One trie per list, one mode for that
trie: the mode decides what the trie is keyed on (surface text, casefolded text,
folded text, or the token's `lemma` feature), so a per-entry mode would mean a
trie per entry and no longest-match across the list at all. A world that needs
two modes over the same vocabulary writes two lists, which is also what its
diff should show.

**`source` is required.** A Lookup with no provenance is an annotation nobody
can trace back to a decision, and these files are generated: six months later
"which manifest put this alias here" has to be answerable from the document
alone. That is why every Lookup also carries `source` and `matching` (see
`annotate.py`), and why those two names are reserved — an entry that tried to
set them itself would overwrite its own provenance.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Literal

import yaml
from pydantic import BaseModel, ConfigDict, ValidationError, model_validator

from ttrnlp.packs.diag import (
    NLS_PACK_003,
    PackError,
    diagnostics_from_pydantic,
    error,
)

#: The four matching modes (contracts §4). Per list, never per entry.
MATCHING_MODES = ("exact", "ci", "lemma", "fold-diacritics")

MatchingMode = Literal["exact", "ci", "lemma", "fold-diacritics"]

#: Features the gazetteer stamps itself; an entry may not set them (T4).
RESERVED_FEATURES = ("source", "matching")

DEFAULT_ANNOTATION = "Lookup"

_ID = re.compile(r"^[a-z0-9-]+$")

#: Entry feature values. The same small domain the wire carries (contracts §2.1)
#: — features are data, not objects (P-2).
EntryValue = str | int | float | bool


class _Base(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ListSource(BaseModel):
    """Where the list came from. Both halves are required — see the module note."""

    model_config = ConfigDict(extra="forbid")

    #: `dfp` / `tatrman` / `hand` are the worlds that exist today (contracts §4).
    #: Not an enum: the suite is world-neutral OSS, and a deployment that grows a
    #: fifth world should author lists on the day it appears, not wait for a
    #: wheel release to admit its name.
    world: str
    origin: str

    @model_validator(mode="after")
    def _non_empty(self) -> ListSource:
        for field, value in (("world", self.world), ("origin", self.origin)):
            if not value.strip():
                raise ValueError(
                    f"source.{field} is empty — a Lookup that cannot be traced "
                    "back to what produced it is not provenance"
                )
        return self


class ListEntry(_Base):
    """One term and the features it contributes to a match."""

    term: str
    features: dict[str, EntryValue] | None = None

    @model_validator(mode="after")
    def _term_is_matchable(self) -> ListEntry:
        if not self.term.strip():
            raise ValueError("term is empty — nothing could ever match it")
        return self

    @model_validator(mode="after")
    def _no_reserved_features(self) -> ListEntry:
        """T4: `source` and `matching` are the gazetteer's to write.

        Rejected at load rather than resolved at match time. A precedence rule
        ("the entry wins", "the list wins") would be a coin toss the author
        cannot see the result of, and either way one of the two meanings of
        `source` is silently gone from the document.
        """
        clashing = sorted(set(self.features or {}) & set(RESERVED_FEATURES))
        if clashing:
            raise ValueError(
                f"features {clashing} are reserved: the gazetteer stamps `source` "
                "(the list id) and `matching` (the mode that fired) onto every "
                "Lookup, so an entry setting them would erase its own provenance"
            )
        return self


class GazetteerList(_Base):
    """A validated list file."""

    list: str
    version: int
    matching: MatchingMode
    annotation: str = DEFAULT_ANNOTATION
    source: ListSource
    entries: list[ListEntry]

    @model_validator(mode="after")
    def _well_formed(self) -> GazetteerList:
        if not _ID.match(self.list):
            raise ValueError(
                f"list id {self.list!r} must match [a-z0-9-]+ — ids appear in "
                "every Lookup's `source` feature and in pipeline config"
            )
        if self.version < 1:
            raise ValueError(f"version must be >= 1, found {self.version}")
        if not self.entries:
            raise ValueError(
                "entries is empty — an empty list is almost always a broken "
                "exporter run, and it would annotate nothing while looking loaded"
            )
        if not self.annotation.strip():
            raise ValueError("annotation is empty — it names the type to emit")
        return self

    @property
    def id(self) -> str:
        """The list id. ``list`` shadows the builtin at call sites; this reads."""
        return self.list


def load_list(path_or_str: str | Path, *, source: str = "") -> GazetteerList:
    """Parse and validate ONE gazetteer list.

    Args:
        path_or_str: A path to a ``*.list.yaml`` file, or the YAML text itself —
            the same two-way argument ``load_pack`` takes, for the same reason
            (tests and the loader hold different things).
        source: Where the list came from, for diagnostics. Defaults to the path.

    Returns:
        The validated ``GazetteerList``.

    Raises:
        PackError: With every ``NLS-PACK-003`` diagnostic found, each naming the
            JSON path of the offending node.
    """
    text, resolved_source = _read(path_or_str, source)

    try:
        document: Any = yaml.safe_load(text)
    except yaml.YAMLError as exc:
        raise PackError(
            [
                error(
                    NLS_PACK_003,
                    f"$: YAML is not well-formed: {exc}",
                    source=resolved_source,
                )
            ]
        ) from exc

    if not isinstance(document, dict):
        raise PackError(
            [
                error(
                    NLS_PACK_003,
                    "$: a gazetteer list must be a YAML mapping, found "
                    f"{type(document).__name__}",
                    source=resolved_source,
                )
            ]
        )

    # Best-effort id, so diagnostics about a broken list can still name it.
    list_id = document.get("list") if isinstance(document.get("list"), str) else ""

    try:
        return GazetteerList.model_validate(document)
    except ValidationError as exc:
        raise PackError(
            diagnostics_from_pydantic(
                exc, code=NLS_PACK_003, source=resolved_source, pack=list_id
            )
        ) from exc


def _read(path_or_str: str | Path, source: str) -> tuple[str, str]:
    if isinstance(path_or_str, Path):
        return path_or_str.read_text(encoding="utf-8"), source or str(path_or_str)
    if "\n" not in path_or_str:
        candidate = Path(path_or_str)
        if candidate.exists():
            return candidate.read_text(encoding="utf-8"), source or str(candidate)
    return path_or_str, source or "<string>"


__all__ = [
    "DEFAULT_ANNOTATION",
    "MATCHING_MODES",
    "RESERVED_FEATURES",
    "GazetteerList",
    "ListEntry",
    "ListSource",
    "MatchingMode",
    "load_list",
]
