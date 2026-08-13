# SPDX-License-Identifier: Apache-2.0
"""The compiler — layer files in, publishable artifacts out (contracts §2).

`compile_layers` expands every vzor entry through the engine, merges the layers
in argument order, ranks, folds, hashes and renders. What comes back is a set of
*files*, not one file, and that is the load-bearing part of this module.

**Why more than one file: the C-F3 separability proof.** Suite-licensed layers
compile into the main body (``cs.morph.snap``). Each share-alike layer compiles
into a member file of its own (``core-kaikki.morph.part``) with its own header,
its own licence line and its own section in ``NOTICE-morph.md``. The CC BY-SA
material is then identifiable, extractable and separately licensed — an
aggregate of member files rather than one file of mixed provenance, which is the
difference between "we ship share-alike material" and "our artifact is a
share-alike adaptation".

The *merge* still happens once, across all layers, before anything is routed to
a file. Precedence is therefore decided at compile time in argument order, not
at load time by whichever member file happened to be listed first — so the
runtime never arbitrates between core members, and two deployments listing them
in different orders cannot answer differently.

**Ranks.** ``rank`` is precomputed here because ranking at lookup time would
mean carrying a frequency table into the runtime (contracts §1). Lower sorts
first. An explicit ``rank:`` on the entry wins; otherwise the frequency table
supplies one; otherwise 0.

⚑ **A lemma the frequency table has never seen ranks *last*, not 0.** ``0``
sorts ahead of the most frequent word in the language, so a hand-seeded entry
with no CAC evidence would silently become the head of the list — and the head
of the list is what every pre-morph consumer reads as ``lemma``. Absent lemmas
get ``len(table) + 1``. With no frequency table at all every row is 0, ties
break on (lemma, upos) in the loader, and nothing is claimed.

⚑ **When an entry carries both ``vzor`` and ``forms`` and they agree, the
pattern expands.** Contracts §3 says "forms win", and that sentence decides the
case where the two disagree — there, the forms are the truth and the pattern is
dropped from the row (`LM-MORPH-005`), because a vzor that does not reproduce
the forms is not this lexeme's vzor. Where they agree, "forms win" would mean
emitting the importer's three spot-check forms and discarding the other eleven
the pattern makes, which is a data loss no reading of §3 asks for.

The three format rulings from the NLS-P7.2 loader are obeyed rather than
restated: the content hash comes from `ttrnlp.morph.snapshot.content_hash` —
the *same function* the loader verifies with, for the same reason `fold` is
imported rather than reimplemented — decomposition rides the flags cell as
``parts:aby+bych``, and ``#ne-exceptions`` is one form per line.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path

from ttrnlp.morph.diag import (
    LM_MORPH_001,
    LM_MORPH_002,
    LM_MORPH_003,
    LM_MORPH_004,
    Diagnostic,
    error,
    info,
)
from ttrnlp.morph.records import PROVENANCE_LEXICON, PROVENANCE_PROVISIONAL
from ttrnlp.morph.snapshot import (
    COLUMNS,
    FOLD_INDEX_SECTION,
    NE_EXCEPTIONS_SECTION,
    OVERLAY_MAGIC,
    PARTS_FLAG,
    SNAPSHOT_MAGIC,
    content_hash,
    pack_feats,
)

from ttrmorph.compile.layers import (
    Entry,
    Layer,
    read_layer,
    resolve_vzor,
    unproduced,
)
from ttrmorph.compile.notice import render_notice
from ttrmorph.engine import EngineError, fold, generate, load

#: Extension of a share-alike member file (contracts §10 release assets).
PART_SUFFIX = ".morph.part"

#: Where the core artifact's NOTICE lands, beside the artifact it describes.
NOTICE_FILENAME = "NOTICE-morph.md"


def notice_filename(world: str = "") -> str:
    """The NOTICE's name for this compile — world-scoped for an overlay.

    ⚑ **An overlay must never write `NOTICE-morph.md`.** Both compiles emit
    into a directory the caller picks (`cli._compile` uses `args.output.parent`,
    and morph-studio's Q-7 emission uses the shared overlay directory), and the
    obvious way to build a release is to put the snapshot, its `.part` members
    and the overlays side by side. Under one filename the last compile wins:
    running `--overlay --world dfp -o dist/dfp.morph.overlay` overwrote the
    core artifact's real CC BY-SA attribution with a document reading "None.
    This artifact contains suite-licensed material only" — the `.part` files
    still sitting beside it, now with nothing attributing them. That is the
    licence boundary the whole aggregate design exists to hold, lost to a name
    collision.
    """
    return f"NOTICE-morph-{world}.md" if world else NOTICE_FILENAME

_SEVERITY_ERROR = "ERROR"


@dataclass(frozen=True)
class CompileResult:
    """Everything a compile produced, written by the caller.

    Nothing is written from inside the compiler: a compile that failed halfway
    must not leave a half-written snapshot on disk where the next job will
    happily hash it.
    """

    #: filename (relative to the output directory) -> file text.
    outputs: Mapping[str, str]
    diagnostics: tuple[Diagnostic, ...] = ()

    @property
    def ok(self) -> bool:
        return not any(d.severity == _SEVERITY_ERROR for d in self.diagnostics)


@dataclass
class _Emitted:
    """One entry's contribution, after expansion."""

    #: form -> the readings it realises in this entry.
    forms: dict[str, set[str]] = field(default_factory=dict)
    vzor: str = ""
    flags: tuple[str, ...] = ()


# ── frequencies ──────────────────────────────────────────────────────────────


def read_frequencies(path: str | Path) -> dict[str, int]:
    """``lemma<TAB>count`` -> ``lemma -> rank``, most frequent = 1.

    Counts become ranks here rather than travelling into the artifact, because
    the artifact's consumers must not be able to re-derive a threshold from
    them: the suite ranks, it does not score (NL-17). Ties break on the lemma so
    that two runs over the same table produce the same artifact.
    """
    counts: list[tuple[str, int]] = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        lemma, _, raw = line.partition("\t")
        try:
            counts.append((lemma.strip(), int(raw.strip())))
        except ValueError:
            continue
    counts.sort(key=lambda pair: (-pair[1], pair[0]))
    return {lemma: index for index, (lemma, _) in enumerate(counts, start=1)}


def _ranker(frequencies: Mapping[str, int] | None):
    if not frequencies:
        return lambda lemma: 0
    unseen = len(frequencies) + 1
    return lambda lemma: frequencies.get(lemma, unseen)


# ── cells ────────────────────────────────────────────────────────────────────


def _feats_cell(readings: Iterable[str]) -> str:
    """The ``;``-packed reading set — kind-(a) ambiguity in ONE row.

    The loader's own packer, for the reason `fold` and `content_hash` are also
    imported rather than reimplemented: a separator the compiler and the loader
    each spell for themselves is a separator they can come to spell differently.
    """
    return pack_feats(readings)


def _flags_cell(flags: Sequence[str], parts: Sequence[str]) -> str:
    ordered = list(flags)
    if parts:
        ordered.append(PARTS_FLAG + "+".join(parts))
    return ",".join(ordered)


def _row(cells: Sequence[str]) -> str:
    return "\t".join(cells)


# ── expansion ────────────────────────────────────────────────────────────────


def _expand(entry: Entry) -> _Emitted | None:
    """One entry -> its forms, or ``None`` if it cannot contribute.

    Reports nothing. Every entry reaching here came through `read_layer`, which
    ran the same engine call and already said what was wrong with it; a second
    diagnostic from here would make one broken entry look like two, and send
    the analyst looking for a second mistake that does not exist.
    """
    declared: dict[str, set[str]] = {}
    for spec in entry.forms:
        declared.setdefault(spec.form, set()).add(spec.feats)

    if not entry.vzor:
        return _Emitted(forms=declared)

    try:
        vzor, flags = resolve_vzor(entry)
        produced = generate(entry.lemma, vzor, flags)
    except EngineError:
        return _Emitted(forms=declared) if declared else None

    if declared and unproduced(entry, produced):
        # Disagreement: the declared forms are the truth and the pattern is
        # not this lexeme's. `layers.py` already reported LM-MORPH-005 — from
        # `unproduced`, the same call, so the row this emits and the diagnostic
        # that describes it cannot disagree about which forms the pattern makes.
        return _Emitted(forms=declared)

    expanded: dict[str, set[str]] = {}
    for form, feats in produced:
        expanded.setdefault(form, set()).add(feats)
    flag_order = load().order_flags(flags)
    return _Emitted(forms=expanded, vzor=vzor, flags=tuple(flag_order))


# ── the compile ──────────────────────────────────────────────────────────────


def compile_layers(
    paths: Sequence[str | Path],
    *,
    snapshot_version: str,
    language: str = "cs",
    frequencies: Mapping[str, int] | None = None,
    output: str = "cs.morph.snap",
    world: str = "",
) -> CompileResult:
    """Compile layer files into a snapshot (or a world overlay).

    Args:
        paths: Layer files, in precedence order — a later layer's entry
            replaces an earlier one with the same (lemma, upos).
        snapshot_version: The ``morph/v*`` tag this artifact is being cut as.
        language: The language every layer must declare.
        frequencies: ``lemma -> rank`` from `read_frequencies`, or None.
        output: Filename of the main body.
        world: When set, compile an overlay for this world instead of a core
            snapshot — provisional entries become legal and the fold index is
            left out (the loader folds overlays at load).

    Returns:
        A `CompileResult`. Check ``ok`` before writing anything: on an ERROR
        the outputs are still rendered from whatever was usable, which is what
        makes the diagnostics readable, and are not a publishable artifact.
    """
    diagnostics: list[Diagnostic] = []
    layers: list[Layer] = []
    sources: list[str] = []

    for path in paths:
        layer, found = read_layer(path)
        diagnostics.extend(found)
        if layer is None:
            continue
        if layer.world and not world:
            # A `world:<id>` layer in a core compile is the licence boundary
            # again, in the other direction: a world's vocabulary published
            # inside the core artifact would ship to every deployment, and the
            # world would have no way to retract it. Overlays are how a world
            # gets its own material; there is no second way.
            diagnostics.append(
                error(
                    LM_MORPH_004,
                    f"layer {layer.layer!r} is licensed to world "
                    f"{layer.world!r} and this is a core compile — compile it "
                    "with --overlay --world, or the core would carry one "
                    "world's vocabulary to every deployment",
                    source=str(path),
                )
            )
            continue
        if layer.language != language:
            diagnostics.append(
                error(
                    LM_MORPH_001,
                    f"layer {layer.layer!r} is {layer.language!r} and this "
                    f"compile is {language!r} — one artifact, one language",
                    source=str(path),
                )
            )
            continue
        layers.append(layer)
        sources.append(str(path))

    #: (lemma, upos) -> (layer index, entry). Later layers replace earlier ones.
    owners: dict[tuple[str, str], tuple[int, Entry]] = {}
    for index, layer in enumerate(layers):
        for entry in layer.entries:
            previous = owners.get(entry.identity)
            if previous is not None:
                diagnostics.append(
                    info(
                        LM_MORPH_002,
                        f"({entry.lemma}, {entry.upos}) in layer "
                        f"{layer.layer!r} replaces the entry from layer "
                        f"{layers[previous[0]].layer!r} — later layers win, and "
                        "they take the whole lexeme, not the forms they happen "
                        "to list",
                        source=sources[index],
                    )
                )
            owners[entry.identity] = (index, entry)

    rank_of = _ranker(frequencies)
    #: layer index -> its rows; kept apart so share-alike layers can be routed
    #: to member files of their own without a second pass.
    rows: dict[int, list[str]] = {index: [] for index in range(len(layers))}
    ne_exceptions: dict[int, set[str]] = {index: set() for index in range(len(layers))}

    for identity, (index, entry) in sorted(owners.items()):
        layer = layers[index]
        if entry.provisional and not world:
            diagnostics.append(
                error(
                    LM_MORPH_003,
                    f"({entry.lemma}, {entry.upos}) in layer {layer.layer!r} is "
                    "provisional, and provisional entries compile only into a "
                    "world overlay (Q-7 is narrow: an unverified generated form "
                    "may serve in a world, never in the published core)",
                    source=sources[index],
                )
            )
            continue

        emitted = _expand(entry)
        if emitted is None:
            continue

        provenance = (
            PROVENANCE_PROVISIONAL if entry.provisional else PROVENANCE_LEXICON
        )
        rank = entry.rank if entry.rank is not None else rank_of(entry.lemma)
        flags_cell = _flags_cell(emitted.flags, entry.parts)

        for form, readings in emitted.forms.items():
            rows[index].append(
                _row(
                    (
                        form,
                        identity[0],
                        identity[1],
                        _feats_cell(readings),
                        emitted.vzor,
                        flags_cell,
                        str(rank),
                        layer.layer,
                        provenance,
                    )
                )
            )
            if entry.ne_exception:
                # The whole paradigm, not the citation form: the loader tests
                # the *surface* form it was handed, and `nemocí` is exactly as
                # un-strippable as `nemoc`.
                ne_exceptions[index].add(form.casefold())

    outputs = _render(
        layers,
        rows,
        ne_exceptions,
        snapshot_version=snapshot_version,
        language=language,
        output=output,
        world=world,
    )
    share_alike = [layer for layer in layers if layer.share_alike]
    if share_alike or not world:
        # An overlay with no share-alike layer emits no NOTICE at all. There is
        # nothing for one to say — a world's overlay is the world's own material
        # under its own licence — and the "None, suite-licensed only" boilerplate
        # is not merely empty but wrong about a file that is neither.
        outputs[notice_filename(world)] = render_notice(
            share_alike, snapshot_version=snapshot_version, language=language
        )
    return CompileResult(outputs=outputs, diagnostics=tuple(diagnostics))


def _render(
    layers: Sequence[Layer],
    rows: Mapping[int, list[str]],
    ne_exceptions: Mapping[int, set[str]],
    *,
    snapshot_version: str,
    language: str,
    output: str,
    world: str,
) -> dict[str, str]:
    """Route rows to files and render each one (see the module note on C-F3)."""
    files: dict[str, str] = {}

    body_layers = [
        index
        for index, layer in enumerate(layers)
        if world or not layer.share_alike
    ]
    files[output] = _render_one(
        [layers[index] for index in body_layers],
        [row for index in body_layers for row in rows[index]],
        {form for index in body_layers for form in ne_exceptions[index]},
        snapshot_version=snapshot_version,
        language=language,
        world=world,
    )

    if world:
        # A world overlay is one world's file by definition; splitting it by
        # licence would mean a world could not hand its estate one artifact.
        return files

    for index, layer in enumerate(layers):
        if not layer.share_alike:
            continue
        files[layer.layer + PART_SUFFIX] = _render_one(
            [layer],
            rows[index],
            ne_exceptions[index],
            snapshot_version=snapshot_version,
            language=language,
            world="",
        )
    return files


def _render_one(
    layers: Sequence[Layer],
    rows: Sequence[str],
    ne_exceptions: set[str],
    *,
    snapshot_version: str,
    language: str,
    world: str,
) -> str:
    ordered = sorted(rows)
    lines = [OVERLAY_MAGIC if world else SNAPSHOT_MAGIC]
    lines.append(f"#language: {language}")
    lines.append(f"#version: {snapshot_version}")
    if world:
        lines.append(f"#world: {world}")
    lines.append(
        "#layers: " + " ".join(f"{layer.layer}={layer.license}" for layer in layers)
    )
    lines.append(f"#content-hash: {content_hash(ordered)}")
    lines.append(f"#rows: {len(ordered)}")
    lines.append(_row(COLUMNS))
    lines.extend(ordered)

    if not world:
        # Overlays carry no fold index: the loader folds them at load, because
        # a world hand-editing its own file cannot be asked to keep a derived
        # index current — that is the one drift a compiled index exists to
        # prevent (B-F4-α).
        lines.append(FOLD_INDEX_SECTION)
        lines.extend(_fold_index(ordered))

    if ne_exceptions:
        lines.append(NE_EXCEPTIONS_SECTION)
        lines.extend(sorted(ne_exceptions))

    return "\n".join(lines) + "\n"


def _fold_index(rows: Sequence[str]) -> list[str]:
    """``folded<TAB>form[,form…]`` over the forms in these rows.

    Every form, not only the colliding ones: the index is what a query with no
    diacritics is resolved through, so a form missing from it is a form that
    cannot be reached that way.
    """
    index: dict[str, set[str]] = {}
    for row in rows:
        form = row.split("\t", 1)[0]
        index.setdefault(fold(form), set()).add(form)
    return [
        f"{key}\t{','.join(sorted(forms))}" for key, forms in sorted(index.items())
    ]


__all__ = [
    "NOTICE_FILENAME",
    "PART_SUFFIX",
    "CompileResult",
    "compile_layers",
    "notice_filename",
    "read_frequencies",
]
