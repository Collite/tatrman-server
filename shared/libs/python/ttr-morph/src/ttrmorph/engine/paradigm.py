# SPDX-License-Identifier: Apache-2.0
"""`generate` and `classify` — the engine's two directions (contracts §4).

``generate`` is the one the artifact is built from: an entry says a citation
form, a pattern and some flags, and the compiler expands it into every form
that reaches the snapshot. ``classify`` is its inverse and it is what makes an
importer trustworthy — instead of believing a scraped inflection table, the
importer asks which (vzor, flags) *reproduce* it exactly, and a source table
that no pattern reproduces becomes a full-form entry carrying a diagnostic
rather than a silently wrong compact one (D-F1-alpha).

The same inverse is the enrichment loop's validation primitive at NLS-P9: a
guesser proposes a pattern, the engine generates it, and the proposal is
auto-validated only if the form somebody actually typed is in the result.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from itertools import combinations

from ttrmorph.engine.errors import BadFlag, BadLemma, EngineError, UnknownVzor
from ttrmorph.engine.tables import Tables, Vzor, load

#: The two flag KINDS that change the shape of the paradigm rather than a stem.
#: Matched against `Transform.kind`, never against the flag name — the tables
#: are free to name a flag whatever an analyst calls it, and the driver only
#: knows kinds.
_INVARIANT = "invariant"
_ADD_LEMMA = "add-lemma"


def generate(
    lemma: str,
    vzor: str,
    flags: Sequence[str] = (),
    *,
    lang: str = "cs",
) -> set[tuple[str, str]]:
    """Every (form, feats) the pattern produces for this citation form.

    Args:
        lemma: The citation form, as an analyst writes it in a layer file.
        vzor: A pattern or sub-pattern name from the tables.
        flags: Alternation flags. Order is irrelevant — they are applied in the
            tables' canonical order.
        lang: Which tables to use.

    Returns:
        A set of ``(form, feats)`` pairs. A set, not a mapping: several forms
        may legitimately fill one cell, and the same form fills several.

    Raises:
        UnknownVzor: No such pattern.
        BadFlag: A flag outside the declared inventory.
        BadLemma: The citation form cannot carry this pattern.
    """
    tables = load(lang)
    pattern = _vzor(tables, vzor)
    active = _flags(tables, pattern, flags)
    transforms = [tables.flags[name] for name in active]
    kinds = {transform.kind for transform in transforms}
    slots = pattern.slots

    if _INVARIANT in kinds:
        return {(lemma, slot.feats) for slot in slots}

    base = _stem(lemma, pattern)
    rewrites = [transform for transform in transforms if not transform.reshapes]
    out: set[tuple[str, str]] = set()
    for slot in slots:
        for ending in slot.endings:
            stem, tail = base, ending
            for transform in rewrites:
                stem, tail = transform.apply(stem, tail)
            out.add((tables.spell(stem + tail), slot.feats))
        if _ADD_LEMMA in kinds:
            out.add((lemma, slot.feats))
    return out


def classify(
    table: Mapping[str, str | Sequence[str]],
    *,
    lang: str = "cs",
) -> tuple[str, tuple[str, ...]] | None:
    """The (vzor, flags) whose paradigm reproduces ``table`` exactly, or None.

    Exactly means both directions: no form in the table that the pattern does
    not generate, and no generated form the table does not have. A subset match
    would accept a pattern that also produces forms nobody has ever written,
    and those forms would go straight into the artifact.

    The search is exhaustive over the patterns and over the flag subsets that
    could possibly apply to this stem, in table order, and returns the first
    fit — so the answer is a deterministic function of the input and of the
    file, not of dictionary iteration luck.
    """
    tables = load(lang)
    wanted = _as_pairs(table)
    if not wanted:
        return None

    for name, pattern in tables.vzory.items():
        for lemma in _lemma_candidates(table, pattern):
            for flags in _flag_subsets(tables, pattern, lemma):
                try:
                    produced = generate(lemma, name, flags, lang=lang)
                except EngineError:
                    continue
                if produced == wanted:
                    return name, flags
    return None


# ── internals ────────────────────────────────────────────────────────────────


def _vzor(tables: Tables, name: str) -> Vzor:
    pattern = tables.vzory.get(name)
    if pattern is None:
        raise UnknownVzor(
            f"no vzor {name!r} in the {tables.language} tables; known: "
            f"{sorted(tables.vzory)}"
        )
    return pattern


def _flags(tables: Tables, pattern: Vzor, flags: Sequence[str]) -> tuple[str, ...]:
    declared = tuple(flags) + pattern.implied_flags
    unknown = [f for f in declared if f not in tables.flags]
    if unknown:
        raise BadFlag(
            f"unknown flags {unknown!r}; the inventory is {list(tables.flag_order)}"
        )
    return tables.order_flags(declared)


def _stem(lemma: str, pattern: Vzor) -> str:
    """Strip the pattern's citation ending, or refuse.

    The empty suffix always matches, so a pattern whose citation form *is* its
    stem simply declares that. What cannot be allowed to pass is a lemma that
    does not end the way the pattern says it must: the paradigm would still be
    built, every form would be wrong, and nothing would say so.
    """
    for suffix in pattern.strip:
        if not suffix:
            return lemma
        if lemma.endswith(suffix) and len(lemma) > len(suffix):
            return lemma[: -len(suffix)]
    raise BadLemma(
        f"{lemma!r} cannot take vzor {pattern.name!r}: that pattern expects a "
        f"citation form ending in one of {list(pattern.strip)}"
    )


def _as_pairs(table: Mapping[str, str | Sequence[str]]) -> set[tuple[str, str]]:
    pairs: set[tuple[str, str]] = set()
    for feats, forms in table.items():
        for form in _forms(forms):
            pairs.add((form, feats))
    return pairs


def _forms(forms: str | Sequence[str]) -> tuple[str, ...]:
    if isinstance(forms, str):
        return (forms,)
    return tuple(forms)


def _lemma_candidates(
    table: Mapping[str, str | Sequence[str]], pattern: Vzor
) -> tuple[str, ...]:
    """What the citation form could be, if this pattern is the right one.

    `classify` is not told the lemma — contracts §4 gives it a table and
    nothing else. So each pattern supplies its own answer: the form sitting in
    the cell that pattern calls its citation slot. A table without that cell
    cannot be this pattern's, which makes the lookup the cheapest possible
    discriminator: a masculine table never even tries the feminine patterns,
    because their citation cell carries a gender the table does not have.
    """
    return _forms(table.get(pattern.lemma_feats, ()))


def _flag_subsets(
    tables: Tables, pattern: Vzor, lemma: str
) -> Iterable[tuple[str, ...]]:
    """Flag subsets worth trying for this stem, smallest first.

    Exhaustive over what could matter, not over the inventory: a flag whose
    transform cannot change this stem would produce the paradigm the empty set
    already produced, so trying it adds a duplicate search branch and nothing
    else. Smallest-first means the simplest explanation of a table wins, which
    is what an analyst reading the importer's output expects.
    """
    try:
        stem = _stem(lemma, pattern)
    except BadLemma:
        # No stem can be built, so every flag that rewrites one is out. An
        # invariant flag is still a live hypothesis — it never touches the
        # stem, which is exactly why an indeclinable borrowing does not have to
        # end the way its pattern's citation form does.
        return _invariant_subsets(tables, pattern)
    usable = [
        name
        for name in tables.flag_order
        if name not in pattern.implied_flags and tables.flags[name].applicable(stem)
    ]
    subsets: list[tuple[str, ...]] = []
    for size in range(len(usable) + 1):
        subsets.extend(combinations(usable, size))
    return subsets


def _invariant_subsets(tables: Tables, pattern: Vzor) -> tuple[tuple[str, ...], ...]:
    implied = {tables.flags[name].kind for name in pattern.implied_flags}
    if _INVARIANT in implied:
        return ((),)
    return tuple(
        (name,)
        for name in tables.flag_order
        if tables.flags[name].kind == _INVARIANT
        and name not in pattern.implied_flags
    )


__all__ = ["classify", "generate"]
