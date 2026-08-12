#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A TTR-M repo's lexicon area -> gazetteer lists (NLS-P4.T2, NL-17).

    uv run python tools/export_lexicon.py <lexicon-dir> -o <lists-dir>

**World-side, not part of the wheel** (NL-17). The suite is world-neutral: it
knows what a gazetteer list is and nothing about TTR-M. This exporter is the
bridge for the tatrman world specifically, so it lives in `services/nlp/tools`
where a deployment's own tooling lives, and the wheel stays installable by a
consumer who has never heard of a model graph.

**It reads the AUTHORED `ttr-lexicon/v1` files, not the compiled archive.** The
compiled archive is the fuzzy matcher's artifact and its reader is Kotlin
(`org.tatrman:ttr-lexicon`); reaching for it from Python would mean either a JVM
call or a second implementation of a packed format. The authored YAML is the same
vocabulary, in the form a human maintains, and reading it means the export diff
lines up with the lexicon diff that caused it.

**The mapping, and the one place it is deliberately lossy.**

===============  ==================  ===================================
`method:`        gazetteer mode      note
===============  ==================  ===================================
`EXACT`          ``exact``           byte-equal, no tokenization
`TOKENS`         ``ci``              token sequence, case-insensitive
`TYPOS(n)`       ``ci``              **the typo tolerance is dropped**
`match:` profile ``ci``              profiles are not expressible here
===============  ==================  ===================================

`TYPOS(n)` has no gazetteer equivalent and must not get one: NL-17 keeps every
scored, approximate match world-side, in `lex-matcher` and the glossary service.
What the gazetteer can carry is the *deterministic subset* — the term as written
— so that is what it exports, and every dropped tolerance is reported as an INFO
line and stamped into the generated file's header. A silent downgrade would be
the worst outcome available: the lists would look like they covered what the
fuzzy path covers, and nobody would find out until a user typed a word slightly
wrong.

**Entry kind is derived from the target, never authored** — the same rule the
Kotlin compiler follows (RV-38), so a list cannot disagree with the model graph
about what it declares:

    er.Customer                             -> entity_alias   entity=Customer
    md.measure.revenue                      -> attribute_alias attribute=revenue
    md.dimension.Account.class.expense      -> value_alias    attribute=Account
                                                              value=expense
    ground:something                        -> keyword
    anything else                           -> keyword, target preserved verbatim

The fallback is deliberate: an unrecognised target shape becomes a keyword with
its `target` kept as a feature rather than being dropped. A term the exporter did
not understand is still a term the analyst wrote down.

**Output is deterministic** — one file per (language, mode), entries sorted, so
the export is diffable in git and a re-run with no lexicon change produces no
diff at all.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

LEXICON_SCHEMA = "ttr-lexicon/v1"

#: `method:` -> gazetteer matching mode. See the module docstring on TYPOS.
METHOD_TO_MODE = {"EXACT": "exact", "TOKENS": "ci"}

#: What a term with no `method:` and no `defaults.method` gets. Matches
#: `LexiconValidator.DEFAULT_METHOD` on the Kotlin side.
DEFAULT_METHOD = "EXACT"

#: Ditto for `lang`. `cs|en` means "either", which is one list here, not two.
DEFAULT_LANG = "cs|en"

_TYPOS = re.compile(r"^TYPOS\(\d+\)$")

_ID_UNSAFE = re.compile(r"[^a-z0-9]+")


@dataclass
class Note:
    """Something the exporter had to decide, reported rather than buried."""

    level: str
    message: str

    def __str__(self) -> str:
        return f"{self.level} {self.message}"


@dataclass
class Entry:
    term: str
    features: dict[str, str]
    source_file: str

    def sort_key(self) -> tuple:
        return (self.term, tuple(sorted(self.features.items())))


@dataclass
class Export:
    lists: dict[tuple[str, str], list[Entry]] = field(default_factory=dict)
    notes: list[Note] = field(default_factory=list)

    def add(self, lang: str, mode: str, entry: Entry) -> None:
        self.lists.setdefault((lang, mode), []).append(entry)


def list_id(lang: str, mode: str) -> str:
    """`lexicon-cs-ci`. Ids must match `[a-z0-9-]+`, and `cs|en` does not."""
    lang_part = _ID_UNSAFE.sub("-", lang.lower()).strip("-") or "any"
    return f"lexicon-{lang_part}-{mode}"


def mode_for(method: str, export: Export, *, where: str) -> str:
    """The gazetteer mode for a lexicon `method:`, reporting what it costs."""
    if _TYPOS.match(method):
        export.notes.append(
            Note(
                "INFO",
                f"{where}: `{method}` has no gazetteer equivalent — exported as "
                "`ci` (the term as written). The typo tolerance stays with "
                "lex-matcher; NL-17 keeps approximate matching world-side.",
            )
        )
        return "ci"
    mode = METHOD_TO_MODE.get(method)
    if mode is None:
        export.notes.append(
            Note(
                "INFO",
                f"{where}: unknown method `{method}` — exported as `ci`",
            )
        )
        return "ci"
    return mode


def features_for(target: str) -> dict[str, str]:
    """Model refs and the derived `kind` — see the module docstring's table."""
    if target.startswith("ground:"):
        return {"kind": "keyword", "target": target}

    parts = target.split(".")
    if len(parts) == 2 and parts[0] == "er":
        return {"kind": "entity_alias", "entity": parts[1]}
    if len(parts) == 3 and parts[0] == "md" and parts[1] in ("measure", "attribute"):
        return {"kind": "attribute_alias", "attribute": parts[2]}
    if len(parts) == 3 and parts[0] == "md" and parts[1] == "dimension":
        return {"kind": "attribute_alias", "attribute": parts[2]}
    # md.dimension.<Dim>.class.<member> — a MEMBER ref, so a value alias.
    if len(parts) == 5 and parts[0] == "md" and parts[1] == "dimension":
        return {"kind": "value_alias", "attribute": parts[2], "value": parts[4]}
    return {"kind": "keyword", "target": target}


def read_lexicon(root: Path, export: Export) -> None:
    """Every `*.lex.yaml` under `root`, in path order."""
    for path in sorted(root.rglob("*.lex.yaml")):
        try:
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
        except (OSError, yaml.YAMLError) as exc:
            export.notes.append(Note("ERROR", f"{path}: {exc}"))
            continue
        if not isinstance(document, dict):
            export.notes.append(Note("ERROR", f"{path}: not a YAML mapping"))
            continue
        if document.get("schema") != LEXICON_SCHEMA:
            # Files outside the schema are ignored, not rejected — notes may live
            # beside a lexicon (lexicon-schemas.md §1).
            continue
        _read_entries(path, document, export)


def _read_entries(path: Path, document: dict[str, Any], export: Export) -> None:
    defaults = document.get("defaults") or {}
    default_lang = str(defaults.get("lang") or DEFAULT_LANG)
    default_method = str(defaults.get("method") or DEFAULT_METHOD)

    for index, entry in enumerate(document.get("entries") or []):
        if not isinstance(entry, dict):
            export.notes.append(Note("ERROR", f"{path}[{index}]: not a mapping"))
            continue
        target = str(entry.get("target") or "")
        if not target:
            export.notes.append(Note("ERROR", f"{path}[{index}]: no target"))
            continue
        features = features_for(target)

        for term in entry.get("terms") or []:
            if not isinstance(term, dict) or not term.get("text"):
                export.notes.append(
                    Note("ERROR", f"{path}[{index}]: a term has no text")
                )
                continue
            where = f"{path.name}[{index}] {term['text']!r}"
            if term.get("match") or (defaults.get("match") and not term.get("method")):
                export.notes.append(
                    Note(
                        "INFO",
                        f"{where}: a `match:` profile is not expressible as a "
                        "gazetteer mode — exported as `ci`",
                    )
                )
                mode = "ci"
            else:
                mode = mode_for(str(term.get("method") or default_method), export, where=where)
            export.add(
                str(term.get("lang") or default_lang),
                mode,
                Entry(
                    term=str(term["text"]),
                    features=dict(features),
                    source_file=str(path.name),
                ),
            )


class MorphUnavailable(RuntimeError):
    """`--morph` was asked for and `ttr-morph` is not importable."""


@dataclass
class Morph:
    """The generation-expansion plug (NLS-P8.4 T4) — and the only place that
    knows `ttr-morph` is optional.

    **Why it is optional.** `ttr-morph` is the editorial toolchain and it stays
    off PyPI (⚑LMP-D4), so a consumer running this exporter from an installed
    wheel cannot have it. The exporter is world-side tooling meant to run in a
    model repo (NL-17); making it hard-depend on an unpublishable package would
    make it useless in exactly the place it is for. So the import happens here,
    on demand, and its absence is an error message that names the two ways to
    get it rather than a traceback about a missing module.
    """

    config: object
    state: object
    index: object
    notes: list[Note] = field(default_factory=list)

    @classmethod
    def load(cls, config_path: Path, snapshots: Sequence[Path] = ()) -> Morph:
        try:
            from ttrmorph.export import expand
        except ImportError as exc:  # pragma: no cover - exercised by hand
            raise MorphUnavailable(
                "--morph needs the `ttr-morph` package, which is deliberately "
                "not on PyPI (contracts §10). Either run this exporter from a "
                "tatrman-server checkout, where it is a path dependency, or run "
                "it inside the `ghcr.io/collite/nlp-morph-tools` image, which "
                "is published for exactly this reason."
            ) from exc

        config = expand.read_config(config_path)
        state = expand.load_state(list(snapshots) or list(config.snapshots))
        return cls(config=config, state=state, index=expand.build_index(state))

    def apply(self, document: dict) -> tuple[dict, str]:
        from ttrmorph.export import expand

        decision = self.config.decision(str(document.get("list") or ""))
        expanded, report = expand.expand_document(
            document, self.state, self.index, decision=decision
        )
        if report.unknown:
            self.notes.append(
                Note(
                    "INFO",
                    f"{report.list_id}: {len(report.unknown)} term(s) the morph "
                    f"snapshot cannot analyse were left unexpanded "
                    f"({', '.join(report.unknown[:5])}) — they still match as "
                    "written, and the gap belongs in the enrichment queue",
                )
            )
        if report.multiword:
            self.notes.append(
                Note(
                    "INFO",
                    f"{report.list_id}: {len(report.multiword)} multi-token "
                    "term(s) were left unexpanded — a phrase declines in "
                    "agreement, which is not the cross product of its words'"
                    " paradigms",
                )
            )
        return expanded, expand.header_for(report)


def build_document(lang: str, mode: str, entries: list[Entry], *, origin: str) -> dict:
    """The list as data, before anything decides how it will be matched."""
    seen: dict[tuple, Entry] = {}
    for entry in sorted(entries, key=Entry.sort_key):
        seen.setdefault(entry.sort_key(), entry)

    return {
        "list": list_id(lang, mode),
        "version": 1,
        "matching": mode,
        "annotation": "Lookup",
        "source": {"world": "tatrman", "origin": origin},
        "entries": [
            {"term": entry.term, "features": entry.features}
            for entry in seen.values()
        ],
    }


def render_list(
    lang: str,
    mode: str,
    entries: list[Entry],
    *,
    origin: str,
    morph: Morph | None = None,
) -> str:
    """One `*.list.yaml`, sorted and header-stamped.

    ``morph`` is the generation-expansion plug point (NLS-P8.4 T4, C-O2): with
    it, a list whose decision is `expand` leaves here carrying every inflected
    form of every term and `matching: exact`, and one whose decision is `lemma`
    leaves with its terms untouched and the mode that asks the runtime to
    decline them. Without it nothing changes — the exporter has to keep working
    for someone who has only the wheel.
    """
    document = build_document(lang, mode, entries, origin=origin)
    morph_header = ""
    if morph is not None:
        document, morph_header = morph.apply(document)
        mode = document["matching"]

    body = yaml.safe_dump(
        document, allow_unicode=True, sort_keys=False, default_flow_style=False
    )
    header = (
        "# SPDX-License-Identifier: Apache-2.0\n"
        "#\n"
        "# GENERATED by services/nlp/tools/export_lexicon.py — do not edit.\n"
        f"# Source: the {origin} lexicon area, language {lang!r}.\n"
        "#\n"
    )
    if mode == "ci":
        header += (
            "# NOTE: terms authored with `TYPOS(n)` or a `match:` profile appear\n"
            "# here as written and WITHOUT their tolerance — the gazetteer is\n"
            "# deterministic longest-match (NL-17). Approximate matching for the\n"
            "# same vocabulary stays with lex-matcher, off the compiled archive.\n"
            "#\n"
        )
    return header + morph_header + body


def write_lists(
    export: Export, out_dir: Path, *, origin: str, morph: Morph | None = None
) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for (lang, mode), entries in sorted(export.lists.items()):
        path = out_dir / f"{list_id(lang, mode)}.list.yaml"
        path.write_text(
            render_list(lang, mode, entries, origin=origin, morph=morph),
            encoding="utf-8",
        )
        written.append(path)
    return written


def export_lexicon(
    lexicon_dir: Path, out_dir: Path, *, origin: str, morph: Morph | None = None
) -> tuple[list[Path], list[Note]]:
    export = Export()
    read_lexicon(lexicon_dir, export)
    written = write_lists(export, out_dir, origin=origin, morph=morph)
    if morph is not None:
        export.notes.extend(morph.notes)
    return written, export.notes


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="export_lexicon",
        description=(
            "Export a TTR-M repo's lexicon area to gazetteer lists (contracts §4). "
            "Deterministic: re-running with no lexicon change produces no diff."
        ),
    )
    parser.add_argument("lexicon", metavar="LEXICON-DIR", type=Path)
    parser.add_argument("-o", "--out", metavar="LISTS-DIR", type=Path, required=True)
    parser.add_argument(
        "--origin",
        default="",
        help="provenance recorded in every list (default: lexicon@<dir name>)",
    )
    parser.add_argument(
        "--morph",
        metavar="CONFIG",
        type=Path,
        help=(
            "the morph config: per list, whether to generation-expand every "
            "form (`expand`), match on the token lemma (`lemma`), or leave the "
            "mode the lexicon implied (`keep`). Needs ttr-morph — see --help "
            "output if it is missing."
        ),
    )
    parser.add_argument(
        "--snapshot",
        metavar="SNAP",
        type=Path,
        action="append",
        default=[],
        help="override the morph config's snapshots",
    )
    args = parser.parse_args(argv)

    if not args.lexicon.is_dir():
        print(f"no such lexicon directory: {args.lexicon}", file=sys.stderr)
        return 2

    origin = args.origin or f"lexicon@{args.lexicon.resolve().name}"
    morph = None
    if args.morph:
        try:
            morph = Morph.load(args.morph, args.snapshot)
        except MorphUnavailable as exc:
            print(exc, file=sys.stderr)
            return 2
    written, notes = export_lexicon(args.lexicon, args.out, origin=origin, morph=morph)

    for note in notes:
        print(note, file=sys.stderr)
    for path in written:
        print(path)

    if any(note.level == "ERROR" for note in notes):
        return 1
    if not written:
        print(f"no {LEXICON_SCHEMA} files under {args.lexicon}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
