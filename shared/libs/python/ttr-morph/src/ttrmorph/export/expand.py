# SPDX-License-Identifier: Apache-2.0
"""Generation-expanded gazetteer lists (LM-7, C-O2, contracts §4).

A gazetteer list says ``Kaufland`` once. Czech says *Kauflandu*, *Kauflandem*,
*Kauflandy*. There are exactly two ways for a deterministic longest-match trie
to survive that, and this module is the second one:

``matching: lemma``
    Match on the token's ``lemma`` feature and let the morphology layer do the
    declension. One entry, every form — **as long as the runtime has the morph
    snapshot loaded for that language.**

``matching: exact`` over pre-expanded terms
    Emit every form the paradigm produces as its own entry. Bigger file, no
    runtime morphology needed at all.

**Both are correct and the choice is per list, so it is written down** — in the
exporter's morph config, and again in the generated file's header, because six
months later "why does this list have 340 entries" has to be answerable from the
file. A pipeline that runs without the cs morph snapshot (the v1 default: morph
is opt-in per pipeline) can only match the expanded list; a deployment that has
the snapshot should prefer ``lemma``, where a lexicon fix reaches the gazetteer
without a re-export.

## Expansion reads the ARTIFACT, not the engine

The obvious implementation re-runs `ttrmorph.engine.generate` over the entry's
(vzor, flags). This one loads the compiled snapshot and inverts it. The
difference matters in exactly the case that will happen: a full-form entry has
no vzor at all — CAC-attested words, irregulars, anything the importer could not
classify — and an engine-driven expander would silently emit nothing for them
while reporting success. It also means the emitted terms are *the forms the
runtime will actually see*, which is the property the list needs, rather than
the forms the engine believes in.

## What is deliberately not expanded

**Multi-token terms.** *obchodní zástupce* declines in agreement across both
words, and the cross product of two paradigms is not the phrase's paradigm —
it emits *obchodního zástupce* and also *obchodním zástupce*, which is not
Czech. Phrase inflection is a decision about the phrase; until something makes
it, a multi-token term stays as written and is reported.

**Terms with no morphological answer.** Left as they are, counted, and named in
the report — an untouched term still matches itself, and a term the lexicon does
not know is a lexicon gap the enrichment loop should hear about (S-3), not a
silent omission in a list.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path

import yaml
from ttrnlp.morph import MorphState, load_morph

#: The mode an expanded list carries. Pre-expanded terms are surface forms, and
#: a surface form matched case-insensitively would make *Kaufland* match a
#: sentence-initial *kaufland* — which is a decision about capitalisation, not
#: about morphology, so expansion does not quietly make it.
EXPANDED_MODE = "exact"

#: The mode a list carries when it relies on runtime morphology instead.
LEMMA_MODE = "lemma"

#: Per-list decisions the config may record.
DECISIONS = ("expand", "lemma", "keep")

#: How a previous run's header is recognised, so it can be replaced rather
#: than stacked. Every line `header_for` emits sits under it.
MORPH_HEADER_MARK = "# MORPH:"

#: Feature names the expansion writes onto each emitted entry. `lemma` is what
#: makes an expanded entry traceable back to the one term an analyst wrote.
FEATURE_LEMMA = "lemma"
FEATURE_FEATS = "feats"
FEATURE_FORM_OF = "form_of"


class ExpandError(RuntimeError):
    """The config or a list file cannot be read."""


@dataclass(frozen=True)
class MorphConfig:
    """The per-list decision (contracts §4's `matching`), as authored.

    ``lists`` maps a list id to one of `DECISIONS`; ``default`` applies to any
    list the config does not name. ``keep`` means what the exporter already
    decided from the lexicon `method:` — the safe answer, and the default, so
    adding a config to a repo cannot change a list nobody asked about.
    """

    snapshots: tuple[str, ...] = ()
    default: str = "keep"
    lists: Mapping[str, str] = field(default_factory=dict)

    def decision(self, list_id: str) -> str:
        return self.lists.get(list_id, self.default)


@dataclass
class ExpandReport:
    """What one expansion run did — printed, and stamped into the header."""

    list_id: str = ""
    decision: str = "keep"
    terms: int = 0
    expanded_terms: int = 0
    emitted: int = 0
    multiword: list[str] = field(default_factory=list)
    unknown: list[str] = field(default_factory=list)

    @property
    def changed(self) -> bool:
        return self.decision != "keep"


def read_config(path: Path | str) -> MorphConfig:
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ExpandError(f"{path}: not a YAML mapping")
    lists = raw.get("lists") or {}
    if not isinstance(lists, dict):
        raise ExpandError(f"{path}: `lists:` must be a mapping of list id to decision")
    bad = {
        name: value for name, value in lists.items() if value not in DECISIONS
    }
    default = str(raw.get("default") or "keep")
    if default not in DECISIONS:
        bad["default"] = default
    if bad:
        raise ExpandError(
            f"{path}: unknown decision(s) {bad} — the choices are {DECISIONS}"
        )
    return MorphConfig(
        snapshots=tuple(str(item) for item in raw.get("snapshots") or ()),
        default=default,
        lists={str(name): str(value) for name, value in lists.items()},
    )


def load_state(snapshots: Sequence[str | Path]) -> MorphState:
    if not snapshots:
        raise ExpandError(
            "expansion needs a compiled snapshot: the forms come from the "
            "artifact rather than from the engine (see the module note)"
        )
    return load_morph([str(path) for path in snapshots])


# ── the index ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class FormIndex:
    """lemma -> the forms the artifact holds for it, with their feats.

    Built by inverting `MorphState.exact`, which is the only direction the
    runtime needs and therefore the only one the artifact stores. The cost is
    one pass over the snapshot per export; the alternative is a second index in
    the artifact that only a tool would ever read.
    """

    by_lemma: Mapping[str, tuple[tuple[str, str], ...]]

    def forms_for(self, lemma: str) -> tuple[tuple[str, str], ...]:
        return self.by_lemma.get(lemma, ())

    def __len__(self) -> int:
        return len(self.by_lemma)


def build_index(state: MorphState) -> FormIndex:
    grouped: dict[str, dict[tuple[str, str], None]] = {}
    for form, analyses in state.exact.items():
        for analysis in analyses:
            feats = "|".join(sorted(analysis.feats))
            grouped.setdefault(analysis.lemma, {}).setdefault((form, feats), None)
    return FormIndex(
        by_lemma={
            lemma: tuple(sorted(forms)) for lemma, forms in sorted(grouped.items())
        }
    )


def lemma_of(term: str, state: MorphState) -> str | None:
    """The lemma an expanded term hangs off, or None.

    The term is looked up as written, and the head analysis wins — the same
    answer the runtime would give the same string, which is the point. A term
    that resolves only through the fold index is still resolved: an analyst who
    typed *zakaznik* without diacritics meant *zákazník*, and the expansion
    emits the accented paradigm, which is what a user will type.
    """
    hit = state.lookup(term)
    if hit is None or not hit.analyses:
        return None
    return hit.analyses[0].lemma


# ── expansion ────────────────────────────────────────────────────────────────


def expand_entries(
    entries: Iterable[Mapping],
    state: MorphState,
    index: FormIndex,
    report: ExpandReport,
) -> list[dict]:
    """One entry per generated form, deduplicated, in a stable order.

    Every emitted entry keeps the original's features and adds `lemma` and
    `feats`. Nothing is dropped: a term that cannot be expanded is emitted
    unchanged, so an expanded list is a superset of the list it came from.
    """
    emitted: dict[tuple[str, tuple], dict] = {}

    for entry in authored(entries):
        term = str(entry.get("term") or "")
        features = dict(entry.get("features") or {})
        report.terms += 1

        if len(term.split()) > 1:
            report.multiword.append(term)
            _keep(emitted, term, features)
            continue

        lemma = lemma_of(term, state)
        forms = index.forms_for(lemma) if lemma else ()
        if not forms:
            report.unknown.append(term)
            _keep(emitted, term, features)
            continue

        report.expanded_terms += 1
        keys: dict[str, tuple[str, tuple]] = {}
        for form, feats in forms:
            keys[form] = _keep(
                emitted, form, features, {FEATURE_LEMMA: lemma, FEATURE_FEATS: feats}
            )
        # The authored term is marked wherever it ended up, and added if it is
        # not there at all — a term resolved through the fold index is not among
        # its own forms. Two reasons, and both have teeth: an analyst's own term
        # that stopped matching itself after an export ran would be the worst
        # possible outcome of an "improvement", and `authored()` needs the mark
        # to undo this expansion on the next run.
        if term in keys:
            emitted[keys[term]]["features"][FEATURE_FORM_OF] = lemma
        else:
            _keep(
                emitted, term, features, {FEATURE_LEMMA: lemma, FEATURE_FORM_OF: lemma}
            )

    report.emitted = len(emitted)
    return [emitted[key] for key in sorted(emitted)]


def authored(entries: Iterable[Mapping]) -> list[dict]:
    """The entries a human wrote, with any previous expansion undone.

    **Expansion is reversible, and that is what makes it safe to re-run.** C-O2
    fires this on every snapshot bump; without this step the second run would
    treat the first run's output as authored vocabulary, expand each of a
    paradigm's forms in turn, and grow the file a little every time — which is
    the kind of defect that is only ever noticed as "why is this list 40,000
    lines".

    A derived entry is identifiable by the features the expansion wrote onto it,
    which is the other reason they are there.
    """
    restored: list[dict] = []
    for entry in entries:
        features = dict(entry.get("features") or {})
        if FEATURE_FEATS in features and FEATURE_FORM_OF not in features:
            continue  # a generated form, and not the term that generated them
        # Including `feats`: the authored term is usually one of its own forms,
        # so its entry carries the expansion's features too, and leaving them
        # would make the next run's "authored" vocabulary carry a stale reading.
        for name in (FEATURE_LEMMA, FEATURE_FEATS, FEATURE_FORM_OF):
            features.pop(name, None)
        restored.append({**dict(entry), "features": features})
    return restored


def _keep(
    emitted: dict[tuple[str, tuple], dict],
    term: str,
    features: Mapping[str, str],
    extra: Mapping[str, str] | None = None,
) -> tuple[str, tuple]:
    """Record one entry, deduplicated, and hand back the key it landed on.

    The key includes the features because two lexicon targets can legitimately
    contribute the same term with different `kind`/model refs, and collapsing
    those would silently drop one of the two annotations a match should carry.
    """
    merged = {**features, **(extra or {})}
    key = (term, tuple(sorted(merged.items())))
    emitted.setdefault(key, {"term": term, "features": merged})
    return key


def expand_document(
    document: Mapping,
    state: MorphState,
    index: FormIndex,
    *,
    decision: str,
) -> tuple[dict, ExpandReport]:
    """Apply the per-list decision to one parsed `*.list.yaml`."""
    list_id = str(document.get("list") or "")
    report = ExpandReport(list_id=list_id, decision=decision)
    result = dict(document)

    if decision == "keep":
        report.terms = len(document.get("entries") or [])
        report.emitted = report.terms
        return result, report

    if decision == LEMMA_MODE:
        # No expansion: the runtime declines the tokens. Terms stay as authored
        # and only the mode changes, which is the whole diff a reviewer sees —
        # and a list switched BACK from `expand` loses the generated forms
        # rather than keeping them under a mode that would match them twice.
        result["entries"] = authored(document.get("entries") or [])
        result["matching"] = LEMMA_MODE
        report.terms = len(result["entries"])
        report.emitted = report.terms
        return result, report

    result["entries"] = expand_entries(
        document.get("entries") or [], state, index, report
    )
    result["matching"] = EXPANDED_MODE
    return result, report


def header_for(report: ExpandReport) -> str:
    """The comment block that explains the file's size to a human."""
    if report.decision == LEMMA_MODE:
        return (
            "# MORPH: `matching: lemma` — terms are matched against each token's\n"
            "# `lemma` feature, so this list needs the cs morph snapshot loaded in\n"
            "# the pipeline that uses it. A lexicon fix reaches this list without a\n"
            "# re-export (contracts §4, LM-8).\n"
            "#\n"
        )
    if report.decision != "expand":
        return ""
    lines = [
        "# MORPH: generation-expanded (LM-7, C-O2). Every form below came from the\n"
        f"# compiled morph snapshot: {report.expanded_terms} of {report.terms} "
        f"authored terms expanded to {report.emitted} entries. `matching: exact`,\n"
        "# so this list needs NO runtime morphology — that is what it is for.\n"
        "# Re-run the exporter after every `morph/v*` bump; the publish lane\n"
        "# dispatches it (C-O2) so these can never lag the lexicon.\n"
    ]
    if report.multiword:
        lines.append(
            f"# NOT expanded — {len(report.multiword)} multi-token term(s): a phrase\n"
            "# declines in agreement and the cross product of two paradigms is not\n"
            "# the phrase's paradigm. They match as written.\n"
        )
    if report.unknown:
        shown = ", ".join(report.unknown[:8])
        more = f" (+{len(report.unknown) - 8} more)" if len(report.unknown) > 8 else ""
        lines.append(
            f"# NOT expanded — {len(report.unknown)} term(s) the lexicon does not\n"
            f"# know: {shown}{more}. They match as written; the gap belongs in the\n"
            "# enrichment queue (S-3).\n"
        )
    return "".join(lines) + "#\n"


def render(document: Mapping, report: ExpandReport, *, preamble: str = "") -> str:
    body = yaml.safe_dump(
        dict(document), allow_unicode=True, sort_keys=False, default_flow_style=False
    )
    return preamble + header_for(report) + body


def expand_list_file(
    path: Path,
    state: MorphState,
    index: FormIndex,
    *,
    decision: str,
) -> ExpandReport:
    """Rewrite one `*.list.yaml` in place, preserving its leading comments."""
    text = Path(path).read_text(encoding="utf-8")
    preamble = _leading_comments(text)

    document = yaml.safe_load(text)
    if not isinstance(document, dict):
        raise ExpandError(f"{path}: not a gazetteer list")
    expanded, report = expand_document(document, state, index, decision=decision)
    Path(path).write_text(render(expanded, report, preamble=preamble), encoding="utf-8")
    return report


def expand_lists(
    lists_dir: Path,
    config: MorphConfig,
    *,
    snapshots: Sequence[str | Path] = (),
) -> list[ExpandReport]:
    """The C-O2 post-processor: every list in a directory, per the config."""
    paths = sorted(Path(lists_dir).glob("*.list.yaml"))
    if not paths:
        raise ExpandError(f"no *.list.yaml files under {lists_dir}")

    wanted = [
        path
        for path in paths
        if config.decision(_list_id(path)) != "keep"
    ]
    if not wanted:
        return [ExpandReport(list_id=_list_id(path)) for path in paths]

    state = load_state(list(snapshots) or list(config.snapshots))
    index = build_index(state)
    reports = []
    for path in paths:
        decision = config.decision(_list_id(path))
        if decision == "keep":
            reports.append(ExpandReport(list_id=_list_id(path)))
            continue
        reports.append(expand_list_file(path, state, index, decision=decision))
    return reports


def _list_id(path: Path) -> str:
    return path.name[: -len(".list.yaml")]


def _leading_comments(text: str) -> str:
    """The file's own header, kept.

    Leading only — a comment further down belongs to the entry under it, and
    both the SPDX line and the exporter's GENERATED banner are at the top. A
    naive "every comment line" filter would hoist an entry's note to the head of
    the file and silently drop it from where it meant something.
    """
    kept: list[str] = []
    for line in text.splitlines():
        if line.startswith(MORPH_HEADER_MARK):
            # Everything from here to the end of the block was written by a
            # previous run of this expander. Keeping it would stack a new
            # explanation on top of a stale one every time C-O2 fires.
            break
        if line.startswith("#") or not line.strip():
            kept.append(line)
            continue
        break
    header = "\n".join(kept).rstrip("\n")
    return (header + "\n") if header else ""


__all__ = [
    "DECISIONS",
    "EXPANDED_MODE",
    "FEATURE_FEATS",
    "FEATURE_FORM_OF",
    "FEATURE_LEMMA",
    "LEMMA_MODE",
    "ExpandError",
    "MORPH_HEADER_MARK",
    "ExpandReport",
    "FormIndex",
    "authored",
    "MorphConfig",
    "build_index",
    "expand_document",
    "expand_entries",
    "expand_list_file",
    "expand_lists",
    "header_for",
    "lemma_of",
    "load_state",
    "read_config",
    "render",
]
