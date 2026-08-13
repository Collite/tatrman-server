# SPDX-License-Identifier: Apache-2.0
"""The snapshot + overlay loader (LM contracts §2/§3, NL-15 semantics).

**What a snapshot is.** A columnar text artifact published on the ``morph/v*``
tag lane and never baked into the wheel: a header block, one row per (form,
lemma, upos, reading-set), then a compiled ``#fold-index`` and the
``#ne-exceptions`` list. Lookup at query time is a hash-map hit — the paradigm
engine that produced these forms lives in `ttr-morph` and does not run here
(P-1: the runtime is deterministic and does no morphology of its own).

**The fold index is compiled in, not computed** (B-F4-α). Folding 60–150k forms
at every boot would be work the compiler already did, and — worse — work done
*twice*, by two implementations that could disagree about what *trzby* folds to.
The artifact carries the index; `ttr-morph` builds it with
`ttrnlp.morph.records.fold`, the same function this module uses for overlays.

**Fail-all-or-nothing** (NL-15). One broken overlay and *nothing* loads: a
service that comes up with three of four worlds' vocabulary looks healthy and
silently cannot answer the fourth world's questions. `LoadError` carries every
diagnostic from every source, and a refused reload leaves the previously loaded
state serving untouched — the caller swaps a pointer or does not.

**Content hash — the algorithm, spelled out**, because `ttr-morph`'s compiler
computes it and this module verifies it, and a drift between them would present
as a corrupt artifact nobody can explain::

    rows   = the data lines, verbatim as written (TAB-joined, 9 columns, no
             trailing newline of their own, header and sections excluded)
    digest = sha256( "".join(row + "\\n" for row in sorted(rows)) as UTF-8 )
    header = "#content-hash: sha256:<digest hex>"

``sorted`` is Python's default string ordering (codepoint order). Rows are
hashed **as written**, so the compiler owns row canonicalisation — the order of
``;``-packed readings inside a cell is part of the bytes, and a compiler that
emitted them in set order would produce a different artifact on every run. Only
data rows participate: ``#fold-index`` and ``#ne-exceptions`` are derived from
the rows, so hashing them too would only prove they were copied.

**The core may arrive as several files** (C-F3). Share-alike material compiles
into separable member files of its own — ``core-kaikki.morph.part`` beside
``cs.morph.snap`` — so that the CC BY-SA sources stay identifiable and
separately licensed instead of mixing into one file of blended provenance. All
of them are core: `load_morph` takes the leading run of snapshot-magic sources
as core members and the rest as world overlays. Members do not shadow each
other and are not ordered against each other, because the *compiler* already
merged them: precedence between layers is decided once, at compile time, in the
order the layers were compiled. Two deployments listing the member files in
different orders therefore cannot answer differently.

The structure below mirrors `packs/loader.py` — read, collect diagnostics, judge
once — deliberately, and just as deliberately does not import it: packs and
morph fail for different reasons in different code families, and a shared loader
would have to be told which of the two it was being at every step.
"""

from __future__ import annotations

import hashlib
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType

from ttrnlp.morph.diag import (
    LM_MORPH_001,
    LM_MORPH_002,
    LM_MORPH_003,
    Diagnostic,
    error,
    info,
)
from ttrnlp.morph.records import (
    MATCHED_EXACT,
    MATCHED_FOLDED,
    PROVENANCE_PROVISIONAL,
    PROVENANCES,
    Analysis,
    LookupResult,
    fold,
)

SNAPSHOT_MAGIC = "#morph-snapshot v1"
OVERLAY_MAGIC = "#morph-overlay v1"

FOLD_INDEX_SECTION = "#fold-index"
NE_EXCEPTIONS_SECTION = "#ne-exceptions"

#: The row shape, in order (contracts §2). The column-header line must say
#: exactly this: a snapshot whose columns moved would otherwise load with every
#: field shifted one place over and answer confidently with nonsense.
COLUMNS = (
    "form",
    "lemma",
    "upos",
    "feats",
    "vzor",
    "flags",
    "rank",
    "layer",
    "provenance",
)

#: What separates two *readings* inside the ``feats`` cell. The atoms of one
#: reading are ``|``-joined by the tagset (``Case=Nom|Number=Sing``), so kind-(a)
#: ambiguity — one form, several readings, one row — needs a separator of its
#: own or the cell cannot be parsed back. Named and exported because three
#: places pack or unpack this cell, in two wheels, and a `|` in any one of them
#: produces a value that still *looks* like feats: `Number=Plur|Person=3` and
#: `Number=Sing|Person=3` run together as one unreadable reading, which no
#: consumer can tell from a genuine six-atom one.
READING_SEP = ";"

#: The core's owner token in `load_morph`'s shadowing table. A sentinel object
#: rather than the string ``"core"``: overlay owners are positions, and a
#: sentinel cannot collide with one the way a string quietly could.
_CORE_OWNER = object()

#: Header keys every artifact must carry, plus the one each kind needs.
_REQUIRED_HEADERS = ("version", "language", "content-hash", "rows")
_REQUIRED_SNAPSHOT = ("layers",)
_REQUIRED_OVERLAY = ("world",)

#: The flag that carries a decomposition — ``parts:aby+bych``. Contracts §2
#: fixes nine columns and §1 gives `Analysis` a ``parts`` field, which leaves
#: decomposition nowhere of its own to sit. A tenth column would make every
#: already-published artifact unreadable; the flags cell is already a list and
#: already editorial-side data, so it carries this. `ttr-morph`'s compiler emits
#: the same spelling.
PARTS_FLAG = "parts:"

#: Prefixes stripped on a miss (contracts §5), longest first: *nejlepší* must
#: lose ``nej`` before anything considers ``ne``.
#:
#: ⚑ The task list says both prefixes add ``Polarity=Neg``. ``nej`` is the
#: superlative, not negation (*nejlepší* is "best", not "not good"), so it adds
#: ``Degree=Sup`` here and ``ne`` adds ``Polarity=Neg``. A doubly-prefixed
#: *nejnelepší*-shaped word picks up both, because the strip recurses.
_PREFIXES = (("nej", "Degree=Sup"), ("ne", "Polarity=Neg"))


class LoadError(Exception):
    """Nothing was loaded. Carries every diagnostic from every source.

    Named as contracts §5 names it. It is *not* `ttrnlp.packs.loader.LoadError`
    — packs and morph sources fail for unrelated reasons and report in
    different code families (``NLS-PACK-*`` vs ``LM-MORPH-*``) — so a caller
    loading both catches them separately, and one that catches the wrong name
    gets an unhandled exception rather than a silently empty state.
    """

    def __init__(self, diagnostics: Sequence[Diagnostic]):
        self.diagnostics: list[Diagnostic] = list(diagnostics)
        errors = [d for d in self.diagnostics if d.severity == "ERROR"]
        summary = "; ".join(d.message for d in errors[:3])
        more = f" (+{len(errors) - 3} more)" if len(errors) > 3 else ""
        super().__init__(
            f"{len(errors)} error(s) loading morph sources: {summary}{more}"
            if errors
            else "morph sources could not be loaded"
        )

    @property
    def codes(self) -> list[str]:
        return [d.code for d in self.diagnostics]


@dataclass(frozen=True)
class MorphManifest:
    """A source's header block, parsed (contracts §2)."""

    kind: str  # "snapshot" | "overlay"
    version: str
    language: str
    content_hash: str
    rows: int
    #: (layer id, licence) pairs — the licence boundary, readable at runtime so
    #: an operator can answer "what is in this artifact" without the tooling.
    layers: tuple[tuple[str, str], ...] = ()
    world: str = ""
    source: str = ""


@dataclass(frozen=True)
class MorphStats:
    """What `MorphState.stats()` reports — the eval harness reads this."""

    version: str
    language: str
    rows: int
    forms: int
    layers: tuple[tuple[str, str], ...]
    worlds: tuple[str, ...]
    fold_collisions: int
    content_hash: str


@dataclass(frozen=True)
class _Row:
    """One parsed data row, before it becomes state."""

    form: str
    analysis: Analysis
    layer: str
    source: str
    line: int


@dataclass
class _Parsed:
    """One artifact, read but not yet judged."""

    manifest: MorphManifest | None = None
    rows: list[_Row] = field(default_factory=list)
    fold_index: dict[str, tuple[str, ...]] = field(default_factory=dict)
    ne_exceptions: frozenset[str] = frozenset()


# ── cells ────────────────────────────────────────────────────────────────────


def _feats(cell: str) -> frozenset[str]:
    """``;``-packed reading set — kind-(a) ambiguity travels in ONE row."""
    return frozenset(reading for reading in cell.split(READING_SEP) if reading)


def pack_feats(readings: Iterable[str]) -> str:
    """The inverse of `_feats`: a reading set as one cell.

    Sorted, because whoever writes this cell owns row canonicalisation and the
    content hash is taken over the bytes — a set iterated in whatever order
    Python chose today would hash differently tomorrow for identical input.
    """
    return READING_SEP.join(sorted(reading for reading in readings if reading))


def _flags(cell: str) -> tuple[str, ...]:
    """Alternation flags, minus the one that is really a decomposition."""
    return tuple(
        flag for flag in cell.split(",") if flag and not flag.startswith(PARTS_FLAG)
    )


def _parts(cell: str) -> tuple[str, ...]:
    for flag in cell.split(","):
        if flag.startswith(PARTS_FLAG):
            return tuple(p for p in flag[len(PARTS_FLAG) :].split("+") if p)
    return ()


def _layers(cell: str) -> tuple[tuple[str, str], ...]:
    """``core-hand=suite core-kaikki=CC-BY-SA-4.0`` -> pairs."""
    pairs = []
    for item in cell.split():
        layer, _, licence = item.partition("=")
        pairs.append((layer, licence))
    return tuple(pairs)


def content_hash(rows: Iterable[str]) -> str:
    """``sha256:<hex>`` over the sorted data rows. See the module docstring."""
    digest = hashlib.sha256()
    for row in sorted(rows):
        digest.update((row + "\n").encode("utf-8"))
    return f"sha256:{digest.hexdigest()}"


# ── parsing ──────────────────────────────────────────────────────────────────


def _parse(text: str, source: str, diagnostics: list[Diagnostic]) -> _Parsed:
    """Parse one artifact. Appends diagnostics; never raises.

    A `_Parsed` whose ``manifest`` is ``None`` could not be read as an artifact
    at all — the one failure that makes every later complaint about it noise.
    """
    lines = text.splitlines()
    magic = lines[0].strip() if lines else ""
    if magic not in (SNAPSHOT_MAGIC, OVERLAY_MAGIC):
        diagnostics.append(
            error(
                LM_MORPH_001,
                f"first line must be {SNAPSHOT_MAGIC!r} or {OVERLAY_MAGIC!r}, "
                f"found {magic!r} — the magic line is what says which artifact "
                "this is, and an overlay read as a core snapshot would reject "
                "its own provisional rows",
                source=source,
            )
        )
        return _Parsed()

    kind = "snapshot" if magic == SNAPSHOT_MAGIC else "overlay"
    headers: dict[str, str] = {}
    raw_rows: list[str] = []
    rows: list[_Row] = []
    fold_index: dict[str, tuple[str, ...]] = {}
    ne_exceptions: set[str] = set()
    section = "header"
    seen_columns = False

    for number, line in enumerate(lines[1:], start=2):
        if not line.strip():
            continue

        if line.startswith(FOLD_INDEX_SECTION):
            section = "fold"
            continue
        if line.startswith(NE_EXCEPTIONS_SECTION):
            section = "ne"
            continue
        if line.startswith("#"):
            if section == "header":
                key, _, value = line[1:].partition(":")
                headers[key.strip()] = value.strip()
            continue

        if section == "fold":
            folded, _, forms = line.partition("\t")
            fold_index[folded] = tuple(f for f in forms.split(",") if f)
            continue
        if section == "ne":
            ne_exceptions.add(line.strip())
            continue

        cells = line.split("\t")
        if not seen_columns:
            seen_columns = True
            if tuple(cell.strip() for cell in cells) != COLUMNS:
                diagnostics.append(
                    error(
                        LM_MORPH_001,
                        f"{source}:{number}: column header is {cells} — "
                        f"contracts §2 fixes the columns as {list(COLUMNS)}; a "
                        "shifted column loads every field one place over and "
                        "answers confidently with nonsense",
                        source=source,
                    )
                )
                return _Parsed()
            continue

        if len(cells) != len(COLUMNS):
            diagnostics.append(
                error(
                    LM_MORPH_001,
                    f"{source}:{number}: {len(cells)} columns, expected "
                    f"{len(COLUMNS)}",
                    source=source,
                )
            )
            continue

        # Structurally a row, so it counts towards ``#rows`` and the content
        # hash even if it is semantically rejected below. Otherwise one bad
        # provenance cell reports twice — once for what is wrong with it, and
        # once for a row count that only moved because we dropped it, which
        # reads as a corrupt artifact and sends the reader to the wrong place.
        raw_rows.append(line)

        row = _row(cells, kind, source, number, diagnostics)
        if row is not None:
            rows.append(row)

    manifest = _manifest(kind, headers, raw_rows, source, diagnostics)
    return _Parsed(
        manifest=manifest,
        rows=rows,
        fold_index=fold_index,
        ne_exceptions=frozenset(ne_exceptions),
    )


def _row(
    cells: list[str],
    kind: str,
    source: str,
    number: int,
    diagnostics: list[Diagnostic],
) -> _Row | None:
    form, lemma, upos, feats, vzor, flags, rank, layer, provenance = cells

    if not (form and lemma and upos):
        diagnostics.append(
            error(
                LM_MORPH_001,
                f"{source}:{number}: form, lemma and upos are required",
                source=source,
            )
        )
        return None
    if provenance not in PROVENANCES:
        diagnostics.append(
            error(
                LM_MORPH_001,
                f"{source}:{number}: unknown provenance {provenance!r} "
                f"(one of {sorted(PROVENANCES)})",
                source=source,
            )
        )
        return None
    if provenance == PROVENANCE_PROVISIONAL and kind == "snapshot":
        diagnostics.append(
            error(
                LM_MORPH_003,
                f"{source}:{number}: row {form!r} is provisional, and "
                "provisional rows are legal only in a world overlay — Q-7 is "
                "narrow on purpose: an unverified generated form must never "
                "reach the published core artifact",
                source=source,
            )
        )
        return None
    try:
        rank_value = int(rank) if rank else 0
    except ValueError:
        diagnostics.append(
            error(
                LM_MORPH_001,
                f"{source}:{number}: rank {rank!r} is not an integer",
                source=source,
            )
        )
        return None

    return _Row(
        form=form,
        analysis=Analysis(
            lemma=lemma,
            upos=upos,
            feats=_feats(feats),
            vzor=vzor or None,
            flags=_flags(flags),
            provenance=provenance,
            rank=rank_value,
            parts=_parts(flags),
        ),
        layer=layer,
        source=source,
        line=number,
    )


def _manifest(
    kind: str,
    headers: dict[str, str],
    raw_rows: list[str],
    source: str,
    diagnostics: list[Diagnostic],
) -> MorphManifest | None:
    required = _REQUIRED_HEADERS + (
        _REQUIRED_SNAPSHOT if kind == "snapshot" else _REQUIRED_OVERLAY
    )
    missing = [key for key in required if not headers.get(key)]
    if missing:
        diagnostics.append(
            error(LM_MORPH_001, f"{source}: header is missing {missing}", source=source)
        )
        return None

    try:
        declared = int(headers["rows"])
    except ValueError:
        diagnostics.append(
            error(
                LM_MORPH_001,
                f"{source}: #rows: {headers['rows']!r} is not an integer",
                source=source,
            )
        )
        return None
    if declared != len(raw_rows):
        diagnostics.append(
            error(
                LM_MORPH_001,
                f"{source}: header declares {declared} rows, found "
                f"{len(raw_rows)} — a truncated artifact is exactly what this "
                "count exists to catch",
                source=source,
            )
        )
        return None

    digest = content_hash(raw_rows)
    if headers["content-hash"] != digest:
        diagnostics.append(
            error(
                LM_MORPH_001,
                f"{source}: content-hash mismatch — header says "
                f"{headers['content-hash']}, the rows hash to {digest}",
                source=source,
            )
        )
        return None

    return MorphManifest(
        kind=kind,
        version=headers["version"],
        language=headers["language"],
        content_hash=digest,
        rows=len(raw_rows),
        layers=_layers(headers.get("layers", "")),
        world=headers.get("world", ""),
        source=source,
    )


# ── state ────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class MorphState:
    """Everything loaded, immutable, replaced wholesale on reload.

    Held by the service and swapped by pointer assignment, exactly as the pack
    loader's `LoadedState` is: in-flight requests finish against the state they
    started on, and a refused reload cannot have half-applied. The two maps are
    `MappingProxyType`, so "immutable" is enforced rather than documented.
    """

    manifest: MorphManifest
    #: form -> ranked analyses, as written.
    exact: Mapping[str, tuple[Analysis, ...]]
    #: folded key -> the true forms that fold to it.
    folded: Mapping[str, tuple[str, ...]]
    ne_exceptions: frozenset[str]
    #: Further core member files beyond `manifest` — the separable share-alike
    #: parts (C-F3). Core, not overlays: they shadow nothing and are ranked
    #: against nothing, having been merged already by the compiler.
    members: tuple[MorphManifest, ...] = ()
    overlays: tuple[MorphManifest, ...] = ()
    diagnostics: tuple[Diagnostic, ...] = ()
    fold_collisions: int = 0

    def lookup(self, form: str) -> LookupResult | None:
        """Resolve one surface form (contracts §5).

        The order is: the form as written, then the fold index, then the
        ne-/nej- strip. Each step is a weaker claim than the one before it, and
        the result says which one answered (``matched_via``) so a consumer can
        tell a curated hit from a fallback.

        **Why the fold index is consulted even for a form that hit exactly.**
        *byt* (an apartment) and *být* (to be) fold to the same key. A query
        carrying no diacritics of its own — ``fold(form) == form.casefold()`` —
        is ambiguous *by construction*: the writer either meant the unaccented
        word or was typing without a Czech keyboard, and there is no evidence
        here to choose. So both readings come back, exact-form-derived first
        (B-F3). A query that *does* carry diacritics has already made the
        choice, and expanding it would push candidates the writer explicitly
        ruled out into a ranked list that nothing downstream re-ranks.
        """
        analyses = self.exact.get(form, ())
        key = fold(form)

        if analyses:
            extra: list[Analysis] = []
            if key == form.casefold():
                for other in self.folded.get(key, ()):
                    if other != form:
                        extra.extend(self.exact.get(other, ()))
            return LookupResult(
                form=form,
                matched_via=MATCHED_EXACT,
                analyses=tuple(analyses) + _rank(extra),
            )

        candidates: list[Analysis] = []
        for other in self.folded.get(key, ()):
            candidates.extend(self.exact.get(other, ()))
        if candidates:
            return LookupResult(
                form=form, matched_via=MATCHED_FOLDED, analyses=_rank(candidates)
            )

        return self._stripped(form)

    def _stripped(self, form: str) -> LookupResult | None:
        """The ne-/nej- retry (contracts §5), or None.

        Callers never pre-strip: *nemoc* is not *ne* + *moc*, only the lexicon
        knows that, and so the exceptions list lives inside the artifact and
        inside this call. Without it, "illness" resolves as "not power" — with
        a straight face, and with a lemma that will match a rule.
        """
        lowered = form.casefold()
        if lowered in self.ne_exceptions or fold(lowered) in self.ne_exceptions:
            return None
        for prefix, atom in _PREFIXES:
            if not lowered.startswith(prefix) or len(lowered) <= len(prefix) + 1:
                continue
            inner = self.lookup(form[len(prefix) :])
            if inner is None or not inner.analyses:
                continue
            return LookupResult(
                form=form,
                matched_via=inner.matched_via,
                analyses=tuple(_with_atom(a, atom) for a in inner.analyses),
            )
        return None

    def stats(self) -> MorphStats:
        return MorphStats(
            version=self.manifest.version,
            language=self.manifest.language,
            rows=self.manifest.rows
            + sum(m.rows for m in self.members)
            + sum(o.rows for o in self.overlays),
            forms=len(self.exact),
            layers=self.manifest.layers
            + tuple(pair for source in self.members for pair in source.layers)
            + tuple(pair for overlay in self.overlays for pair in overlay.layers),
            worlds=tuple(o.world for o in self.overlays if o.world),
            fold_collisions=self.fold_collisions,
            content_hash=self.manifest.content_hash,
        )


def _rank(analyses: Iterable[Analysis]) -> tuple[Analysis, ...]:
    """Rank order, deterministically.

    ``rank`` is precomputed at compile time (frequency order for core layers,
    flat for entity layers) and **lower sorts first**. Ties break on (lemma,
    upos) rather than on file order: two artifacts holding the same entries in a
    different order must answer identically, or a snapshot rebuild silently
    changes which lemma is the head of the list — and the head of the list is
    what every pre-morph consumer reads as ``lemma``.
    """
    return tuple(sorted(analyses, key=lambda a: (a.rank, a.lemma, a.upos)))


def _with_atom(analysis: Analysis, atom: str) -> Analysis:
    """Add a UD atom to every reading of an analysis.

    ``feats`` is a set of *readings*, each a ``|``-joined atom string, so the
    atom joins each of them — adding it as a reading of its own would say "or
    the word is merely negative", which is not a reading of anything.
    """
    feats = frozenset(
        f"{reading}|{atom}" if reading else atom for reading in analysis.feats
    ) or frozenset({atom})
    return Analysis(
        lemma=analysis.lemma,
        upos=analysis.upos,
        feats=feats,
        vzor=analysis.vzor,
        flags=analysis.flags,
        provenance=analysis.provenance,
        rank=analysis.rank,
        parts=analysis.parts,
    )


# ── loading ──────────────────────────────────────────────────────────────────


def _out_of_order(kind: str, index: int, seen: Sequence[_Parsed]) -> str:
    """Why source ``index`` may not be here, or ``""`` if it may.

    The first source is the core. After it, either kind is legal — another core
    member file or the first overlay — until an overlay appears, and from there
    on everything must be an overlay. The rule is one-directional so that a
    configuration cannot list a core member *after* a world: the world would
    then be shadowing entries the core had not contributed yet, and whether it
    shadowed them would depend on the order of a list nobody thinks of as
    ordered.
    """
    if index == 0:
        return "" if kind == "snapshot" else "the first source is the core snapshot"
    if kind == "snapshot" and any(
        p.manifest is not None and p.manifest.kind == "overlay" for p in seen
    ):
        return (
            "core member files come before every overlay — a core listed after "
            "a world would be shadowed by it or not depending on file order"
        )
    return ""


def _merge_members(members: Sequence[_Parsed]) -> _Parsed:
    """Fuse the core member files into one core (C-F3 separability).

    No precedence and no shadow diagnostics between members: the compiler
    decided precedence across layers before it routed rows to files, so two
    members holding the same (lemma, upos) would be a compiler bug rather than
    a configuration to arbitrate. The first member's manifest is the core's
    identity — it is the artifact the release is named after — and the others
    are reported through `MorphState.members`.
    """
    if not members:  # pragma: no cover — source 0 must be a snapshot
        return _Parsed()
    first = members[0]
    if len(members) == 1:
        return first

    fold_index: dict[str, tuple[str, ...]] = {}
    for member in members:
        for key, forms in member.fold_index.items():
            merged = dict.fromkeys(fold_index.get(key, ()) + forms)
            fold_index[key] = tuple(merged)

    return _Parsed(
        manifest=first.manifest,
        rows=[row for member in members for row in member.rows],
        fold_index=fold_index,
        ne_exceptions=frozenset().union(*(m.ne_exceptions for m in members)),
    )


def load_morph(sources: Sequence[str | Path]) -> MorphState:
    """Load a core snapshot and any world overlays (contracts §5).

    Args:
        sources: The core snapshot first, then any further core member files
            (the separable share-alike parts), then overlay files. The core
            members are the leading run of snapshot-magic sources and their
            order carries no meaning — the compiler merged them. Overlay order
            does: a later overlay shadows an earlier one exactly as it shadows
            the core.

    Returns:
        An immutable `MorphState`. Any ``LM-MORPH-002`` shadow notices are on
        `MorphState.diagnostics` — the load succeeded, and the operator still
        needs to know that a world is overriding curated core vocabulary.

    Raises:
        LoadError: With every diagnostic from every source. Nothing is loaded —
            not even the sources that were fine (NL-15).
    """
    if not sources:
        raise LoadError([error(LM_MORPH_001, "no morph sources configured")])

    diagnostics: list[Diagnostic] = []
    parsed: list[_Parsed] = []

    for index, raw_source in enumerate(sources):
        name = str(raw_source)
        try:
            text = Path(raw_source).read_text(encoding="utf-8")
        except OSError as exc:
            # A missing snapshot is nearly always a volume that failed to
            # mount, and the service must come up saying so rather than crash
            # loop with the reason in logs nobody is reading yet.
            diagnostics.append(
                error(LM_MORPH_001, f"cannot read morph source: {exc}", source=name)
            )
            parsed.append(_Parsed())
            continue

        result = _parse(text, name, diagnostics)
        if result.manifest is not None:
            reason = _out_of_order(result.manifest.kind, index, parsed)
            if reason:
                diagnostics.append(
                    error(
                        LM_MORPH_001,
                        f"{name}: source {index} is a {result.manifest.kind} — "
                        f"{reason}",
                        source=name,
                    )
                )
        parsed.append(result)

    if any(d.severity == "ERROR" for d in diagnostics):
        raise LoadError(diagnostics)

    members = [p for p in parsed if p.manifest and p.manifest.kind == "snapshot"]
    core = _merge_members(members)
    if core.manifest is None:  # pragma: no cover — an ERROR would have been raised
        raise LoadError(
            diagnostics + [error(LM_MORPH_001, "core snapshot did not parse")]
        )

    #: (lemma, upos) -> who owns it. Entry identity is (lemma, upos), so a
    #: world shadowing *tržba* takes over every form of it, not the one form it
    #: happened to list — a half-shadowed paradigm would answer from two layers
    #: depending on which case the writer used.
    #: The owner is `_CORE_OWNER` or an overlay's *position* in the source list.
    #: Position rather than world id, because two sources may name the same
    #: world (a world's own overlay plus the studio's provisional one is the
    #: ordinary case) and shadowing has to be decided per file.
    owners: dict[tuple[str, str], object] = {
        (row.analysis.lemma, row.analysis.upos): _CORE_OWNER for row in core.rows
    }
    shadowed: set[tuple[str, str]] = set()
    overlay_manifests: list[MorphManifest] = []
    #: `(owner, row)`, so the shadowed rows can be dropped after every overlay
    #: has had its say — which overlay finally owns an identity is not known
    #: until the last one has been read.
    overlay_rows: list[tuple[object, _Row]] = []
    ne_exceptions = set(core.ne_exceptions)

    for position, overlay in enumerate(
        p for p in parsed if p.manifest is not None and p.manifest.kind == "overlay"
    ):
        assert overlay.manifest is not None
        overlay_manifests.append(overlay.manifest)
        ne_exceptions |= overlay.ne_exceptions
        for row in overlay.rows:
            identity = (row.analysis.lemma, row.analysis.upos)
            previous = owners.get(identity)
            if previous == _CORE_OWNER and identity not in shadowed:
                shadowed.add(identity)
                diagnostics.append(
                    info(
                        LM_MORPH_002,
                        f"{overlay.manifest.source}:{row.line}: world "
                        f"{overlay.manifest.world!r} shadows the core entry "
                        f"({row.analysis.lemma}, {row.analysis.upos}) via form "
                        f"{row.form!r} — the overlay now owns every form of it",
                        source=overlay.manifest.source,
                    )
                )
            elif previous is not None and previous not in (_CORE_OWNER, position):
                # ⚑ An overlay shadowing an *overlay*. Reported as loudly as the
                # core case and for a stronger reason: two worlds' vocabularies
                # colliding is not curation being overridden, it is one world's
                # private material about to answer for another, and `lookup()`
                # takes no world argument that could tell them apart.
                earlier = overlay_manifests[previous]  # type: ignore[index]
                diagnostics.append(
                    info(
                        LM_MORPH_002,
                        f"{overlay.manifest.source}:{row.line}: world "
                        f"{overlay.manifest.world!r} shadows "
                        f"({row.analysis.lemma}, {row.analysis.upos}) from "
                        f"{earlier.source} (world {earlier.world!r}) — later "
                        "overlays win whole lexemes, and one front answering "
                        "for two worlds is worth checking was intended",
                        source=overlay.manifest.source,
                    )
                )
            owners[identity] = position
        overlay_rows.extend((position, row) for row in overlay.rows)

    exact: dict[str, list[Analysis]] = {}
    for row in core.rows:
        if (row.analysis.lemma, row.analysis.upos) in shadowed:
            continue
        exact.setdefault(row.form, []).append(row.analysis)
    for owner, row in overlay_rows:
        # ⚑ Not unconditionally. The docstring's promise — "a later overlay
        # shadows an earlier one exactly as it shadows the core" — was only ever
        # kept against the core: `owners` recorded the later overlay, and then
        # every overlay row was appended anyway, so two worlds defining
        # `Kaufland/PROPN` merged into one answer set instead of the later
        # winning. Shadowing means the earlier rows are *gone*, the same way a
        # shadowed core lexeme is.
        if owners.get((row.analysis.lemma, row.analysis.upos)) != owner:
            continue
        exact.setdefault(row.form, []).append(row.analysis)

    folded: dict[str, list[str]] = {
        key: list(forms) for key, forms in core.fold_index.items()
    }
    for _, row in overlay_rows:
        # Overlays are folded at load. They are world-owned files a world may
        # hand-edit, so they carry no compiled index of their own — and asking
        # a world to keep one current would be asking for the one drift the
        # compiled index exists to prevent. Shadowed forms that get in here are
        # taken back out by the `live` filter below, which is what it is for.
        bucket = folded.setdefault(fold(row.form), [])
        if row.form not in bucket:
            bucket.append(row.form)

    # A form dropped by shadowing must not stay reachable through the index.
    live = set(exact)
    folded_live = {
        key: tuple(form for form in forms if form in live)
        for key, forms in folded.items()
    }
    folded_live = {key: forms for key, forms in folded_live.items() if forms}

    return MorphState(
        manifest=core.manifest,
        exact=MappingProxyType(
            {form: _rank(analyses) for form, analyses in exact.items()}
        ),
        folded=MappingProxyType(folded_live),
        ne_exceptions=frozenset(ne_exceptions),
        members=tuple(p.manifest for p in members[1:] if p.manifest is not None),
        overlays=tuple(overlay_manifests),
        diagnostics=tuple(diagnostics),
        fold_collisions=sum(1 for forms in folded_live.values() if len(forms) > 1),
    )


__all__ = [
    "COLUMNS",
    "FOLD_INDEX_SECTION",
    "NE_EXCEPTIONS_SECTION",
    "OVERLAY_MAGIC",
    "PARTS_FLAG",
    "READING_SEP",
    "SNAPSHOT_MAGIC",
    "LoadError",
    "MorphManifest",
    "MorphState",
    "MorphStats",
    "content_hash",
    "load_morph",
    "pack_feats",
]
