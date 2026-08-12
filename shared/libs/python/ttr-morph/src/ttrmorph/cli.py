# SPDX-License-Identifier: Apache-2.0
"""``ttr-morph`` — the editorial command line.

Stdlib ``argparse`` and nothing else, matching ``ttr-nlp``: a CLI framework
would be a dependency of the container image for no gain, and the two commands
in this repo should not answer ``--help`` in two different dialects.

Subcommands land as the arc does. The engine pair (``generate``/``classify``)
answers "what does this pattern do" and "which pattern is this" — the two
questions an analyst asks before assigning a vzor and the two an importer asks
per row. ``validate`` and ``compile`` are the layer-file lane: the first is what
a world repo runs against its own files (through the ``nlp-morph-tools`` image,
since this package stays off PyPI — contracts §10), the second is what the
``morph/v*`` tag lane runs to cut an artifact. ``eval`` measures what came out
of it — the named acceptance cases always, the corpus metrics when the oracle is
at hand — and ``expand-lists`` pushes the result back out to the gazetteer lists
(C-O2), which is the only direction morphology travels at build time.

``validate`` deliberately reports in the same shape as ``ttr-nlp validate`` —
same exit codes, same ``--json`` fields — so the DFP model-validator wraps both
identically instead of learning two dialects of the same answer.

Exit codes follow ``ttr-nlp``'s split, and for the same reason — whose mistake
it is::

    0   the question had an answer / the files are valid
    1   the question was well-formed and the answer is no (no pattern fits,
        or the files carry errors)
    2   the command could not be run as asked
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Sequence
from pathlib import Path

import yaml
from ttrnlp.packs.diag import SEVERITY_ERROR, Diagnostic

from ttrmorph import __version__
from ttrmorph.compile import compile_layers, read_frequencies, validate_layers
from ttrmorph.engine import EngineError, classify, generate, load
from ttrmorph.eval.cases import CaseError
from ttrmorph.eval.split import FROZEN_SEED, MANIFEST_PATH, SIDES, SplitError
from ttrmorph.eval.split import build as build_split
from ttrmorph.eval.split import render as render_split
from ttrmorph.export.expand import ExpandError
from ttrmorph.importers.sources import PoisonedSource

EXIT_OK = 0
EXIT_NO_ANSWER = 1
EXIT_USAGE = 2


def main(argv: Sequence[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        return args.run(args)
    except EngineError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE
    except (SplitError, PoisonedSource, CaseError, ExpandError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE
    except OSError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ttr-morph",
        description="Czech paradigm engine and lexicon toolchain (LM).",
    )
    parser.add_argument("--version", action="version", version=__version__)
    subs = parser.add_subparsers(dest="command", required=True)

    gen = subs.add_parser("generate", help="expand a lemma through a pattern")
    gen.add_argument("lemma")
    gen.add_argument("vzor")
    gen.add_argument("--flag", action="append", default=[], dest="flags")
    gen.add_argument("--lang", default="cs")
    gen.add_argument("--json", action="store_true", help="emit JSON, not a table")
    gen.set_defaults(run=_generate)

    cls = subs.add_parser("classify", help="find the pattern a table came from")
    cls.add_argument(
        "table",
        type=Path,
        help="YAML or JSON mapping of feats to form(s)",
    )
    cls.add_argument("--lang", default="cs")
    cls.set_defaults(run=_classify)

    lst = subs.add_parser("vzory", help="list the patterns and their hints")
    lst.add_argument("--lang", default="cs")
    lst.add_argument("--json", action="store_true")
    lst.set_defaults(run=_vzory)

    val = subs.add_parser(
        "validate",
        help="check layer files",
        description=(
            "Validate each layer file against the schema, the licence boundary "
            "and the paradigm engine. This is the same reader `compile` uses, "
            "so a layer that validates here compiles in CI."
        ),
    )
    val.add_argument("layers", nargs="+", metavar="LAYER")
    val.add_argument("--json", action="store_true", dest="as_json")
    val.set_defaults(run=_validate)

    com = subs.add_parser(
        "compile",
        help="compile layer files into a snapshot",
        description=(
            "Compile the layers, in precedence order, into a snapshot plus one "
            "separable member file per share-alike layer and a NOTICE."
        ),
    )
    com.add_argument("layers", nargs="+", metavar="LAYER")
    com.add_argument("-o", "--output", default="cs.morph.snap", type=Path)
    com.add_argument(
        "--snapshot-version",
        default="0.0.0",
        help="the morph/v* tag this artifact is cut as (goes in #version)",
    )
    com.add_argument("--lang", default="cs")
    com.add_argument(
        "--freq",
        type=Path,
        help="lemma<TAB>count table; ranks the rows (contracts §1)",
    )
    com.add_argument(
        "--overlay",
        action="store_true",
        help="compile a world overlay instead of a core snapshot",
    )
    com.add_argument("--world", default="", help="world id, with --overlay")
    com.add_argument("--json", action="store_true", dest="as_json")
    com.set_defaults(run=_compile)

    spl = subs.add_parser(
        "split",
        help="freeze the CAC train/dev/test partition (CEREMONY — read §11)",
        description=(
            "Partition UD_Czech-CAC sentence ids 80/10/10 and write the "
            "manifest. This happens ONCE, before any CAC-derived read, and the "
            "manifest is committed alone. Wave C's training task must use this "
            "same file — no re-split, ever (LM-16/S-6, contracts §11)."
        ),
    )
    spl.add_argument("--cac", type=Path, required=True, help="the extracted UD dir")
    spl.add_argument("--archive", type=Path, required=True, help="its .tar.gz, hashed")
    spl.add_argument("--release", required=True, help="the UD release tag, e.g. r2.18")
    spl.add_argument("--seed", type=int, default=FROZEN_SEED)
    spl.add_argument("-o", "--output", type=Path, default=None)
    spl.set_defaults(run=_split)

    kai = subs.add_parser(
        "import-kaikki", help="build a layer from a kaikki.org Wiktionary extract"
    )
    kai.add_argument("jsonl", type=Path)
    kai.add_argument("-o", "--output", type=Path, required=True)
    kai.add_argument("--lang", default="cs")
    kai.add_argument("--targets", type=Path, help="target-word list (YAML)")
    kai.add_argument(
        "--targets-from-freq",
        type=Path,
        help="also target the most frequent lemmas of this lemma<TAB>count table",
    )
    kai.add_argument("--top", type=int, default=3000)
    kai.add_argument(
        "--exclude",
        type=Path,
        action="append",
        default=[],
        help="a layer whose entries this importer must not compete with",
    )
    kai.add_argument("--report", type=Path, help="write the counts here too")
    kai.set_defaults(run=_import_kaikki)

    cac = subs.add_parser(
        "import-cac", help="attested forms + the frequency table, train side only"
    )
    cac.add_argument("cac", type=Path, help="the extracted UD dir")
    cac.add_argument(
        "-o",
        "--output",
        type=Path,
        help="the layer file; omit to emit only the frequency table",
    )
    cac.add_argument("--lang", default="cs")
    cac.add_argument("--freq", type=Path, help="write lemma<TAB>count here")
    cac.add_argument("--targets", type=Path)
    cac.add_argument("--targets-from-freq", type=Path)
    cac.add_argument("--top", type=int, default=3000)
    cac.add_argument("--exclude", type=Path, action="append", default=[])
    cac.add_argument("--min-count", type=int, default=2)
    cac.add_argument("--report", type=Path)
    cac.set_defaults(run=_import_cac)

    ev = subs.add_parser(
        "eval",
        help="measure a snapshot against the oracle and the named cases",
        description=(
            "Run the named acceptance cases (S-7) and the target-vocabulary "
            "coverage against a compiled snapshot; with --cac, also the "
            "contracts §11 corpus metrics over the TEST side of the frozen "
            "split. --gate compares everything against eval/baseline.json and "
            "exits non-zero on a regression."
        ),
    )
    ev.add_argument(
        "--snapshot",
        type=Path,
        action="append",
        required=True,
        help="the artifact; repeat for member files and overlays",
    )
    ev.add_argument("--cac", type=Path, help="the extracted UD dir (test side)")
    ev.add_argument("--split", default="test", choices=list(SIDES))
    ev.add_argument("--cases", type=Path, help="a cases dir (default: eval/cases)")
    ev.add_argument("-o", "--output", type=Path, help="write the report here")
    ev.add_argument("--json", action="store_true", dest="as_json")
    ev.add_argument("--gate", action="store_true", help="fail on a regression")
    ev.add_argument(
        "--baseline", type=Path, help="the gate reference (default: eval/baseline.json)"
    )
    ev.add_argument(
        "--write-baseline",
        action="store_true",
        help="rewrite the gate reference from this run — commit the diff",
    )
    ev.set_defaults(run=_eval)

    exp = subs.add_parser(
        "expand-lists",
        help="generation-expand gazetteer lists in place (C-O2)",
        description=(
            "Apply each list's morph decision: `expand` emits every form the "
            "snapshot holds for a term and drops the mode to `exact`; `lemma` "
            "leaves the terms alone and matches on the token lemma; `keep` "
            "does nothing. Re-run after every morph/v* bump."
        ),
    )
    exp.add_argument("lists", type=Path, metavar="LISTS-DIR")
    exp.add_argument("--config", type=Path, required=True, help="the morph config")
    exp.add_argument(
        "--snapshot",
        type=Path,
        action="append",
        default=[],
        help="override the config's snapshots",
    )
    exp.set_defaults(run=_expand_lists)

    return parser


def _generate(args) -> int:
    forms = generate(args.lemma, args.vzor, args.flags, lang=args.lang)
    # Sorted by feats then form: a paradigm printed in set order would be a
    # different paradigm every run, and this output gets pasted into reviews.
    rows = sorted(forms, key=lambda pair: (pair[1], pair[0]))
    if args.json:
        print(json.dumps([{"form": f, "feats": ft} for f, ft in rows], indent=2))
    else:
        width = max((len(feats) for _, feats in rows), default=0)
        for form, feats in rows:
            print(f"{feats:<{width}}  {form}")
    return EXIT_OK


def _classify(args) -> int:
    raw = yaml.safe_load(args.table.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        print("error: the table must be a mapping of feats to form(s)", file=sys.stderr)
        return EXIT_USAGE
    answer = classify(raw, lang=args.lang)
    if answer is None:
        print("no pattern reproduces this table exactly", file=sys.stderr)
        return EXIT_NO_ANSWER
    vzor, flags = answer
    print(json.dumps({"vzor": vzor, "flags": list(flags)}))
    return EXIT_OK


def _vzory(args) -> int:
    tables = load(args.lang)
    rows = [
        {
            "vzor": name,
            "upos": vzor.upos,
            "parent": vzor.parent,
            "slots": len(vzor.slots),
            "implied_flags": list(vzor.implied_flags),
            "hints": dict(vzor.hints),
        }
        for name, vzor in tables.vzory.items()
    ]
    if args.json:
        print(json.dumps(rows, indent=2, ensure_ascii=False))
    else:
        for row in rows:
            parent = f" < {row['parent']}" if row["parent"] else ""
            print(f"{row['vzor']}{parent}\t{row['upos']}\t{row['slots']} slots")
    return EXIT_OK


def _validate(args) -> int:
    missing = [path for path in args.layers if not Path(path).exists()]
    if missing:
        print(
            f"ttr-morph validate: no such file: {', '.join(missing)}",
            file=sys.stderr,
        )
        return EXIT_USAGE
    return _report(validate_layers(args.layers), args.layers, args.as_json)


def _compile(args) -> int:
    missing = [path for path in args.layers if not Path(path).exists()]
    if missing:
        print(
            f"ttr-morph compile: no such file: {', '.join(missing)}",
            file=sys.stderr,
        )
        return EXIT_USAGE
    if args.overlay and not args.world:
        print("ttr-morph compile: --overlay needs --world <id>", file=sys.stderr)
        return EXIT_USAGE

    frequencies = read_frequencies(args.freq) if args.freq else None
    result = compile_layers(
        args.layers,
        snapshot_version=args.snapshot_version,
        language=args.lang,
        frequencies=frequencies,
        output=args.output.name,
        world=args.world if args.overlay else "",
    )

    status = _report(list(result.diagnostics), args.layers, args.as_json)
    if not result.ok:
        # Nothing is written on an error. A half-written snapshot on disk is
        # the one artifact a later job will pick up and hash without asking.
        return status

    directory = args.output.parent
    directory.mkdir(parents=True, exist_ok=True)
    for name, text in sorted(result.outputs.items()):
        (directory / name).write_text(text, encoding="utf-8")
        if not args.as_json:
            print(f"wrote {directory / name}")
    return EXIT_OK


def _report(diagnostics: list[Diagnostic], paths: Sequence[str], as_json: bool) -> int:
    """One diagnostic per line then a summary — `ttr-nlp validate`'s shape."""
    if as_json:
        print(
            json.dumps(
                [
                    {
                        "source": d.source,
                        "pack": d.pack,
                        "severity": d.severity,
                        "code": d.code,
                        "message": d.message,
                    }
                    for d in diagnostics
                ],
                indent=2,
                ensure_ascii=False,
            )
        )
    else:
        for diagnostic in diagnostics:
            where = diagnostic.source or "?"
            print(
                f"{diagnostic.severity} {diagnostic.code} {where} — "
                f"{diagnostic.message}"
            )

    errors = sum(1 for d in diagnostics if d.severity == SEVERITY_ERROR)
    other = len(diagnostics) - errors
    if not as_json:
        scope = ", ".join(str(path) for path in paths)
        if errors:
            note = f" and {other} note(s)" if other else ""
            print(f"\n{errors} error(s){note} in {scope} — nothing was written.")
        else:
            note = f" ({other} note(s))" if other else ""
            print(f"OK — {scope}{note}.")
    return EXIT_NO_ANSWER if errors else EXIT_OK


# ── the lexicon lane (P8.3) ──────────────────────────────────────────────────


def _split(args) -> int:
    """THE CEREMONY. Read contracts §11 before changing anything here."""
    files = sorted(Path(args.cac).glob("*.conllu"))
    if not files:
        print(f"ttr-morph split: no .conllu files in {args.cac}", file=sys.stderr)
        return EXIT_USAGE

    target = args.output or MANIFEST_PATH
    if Path(target).exists():
        # Not a convenience check. The manifest is shared with Wave C's
        # training task, and overwriting it silently re-partitions a corpus a
        # model is being evaluated against.
        print(
            f"error: {target} already exists — the split is frozen (LM-16/S-6, "
            "contracts §11). There is no re-split: not for a new UD release, "
            "not for a bigger corpus, not because a number would look better",
            file=sys.stderr,
        )
        return EXIT_USAGE

    manifest = build_split(
        files, release=args.release, archive=Path(args.archive), seed=args.seed
    )
    Path(target).parent.mkdir(parents=True, exist_ok=True)
    Path(target).write_text(render_split(manifest), encoding="utf-8")
    print(f"wrote {target}")
    print(f"  corpus  {manifest.corpus} {manifest.release}")
    print(f"  sha256  {manifest.sha256}")
    print(f"  seed    {manifest.seed}")
    for side, count in manifest.counts.items():
        print(f"  {side:<7} {count}")
    print(
        "\nCommit this file ALONE, message:\n"
        "  LM: freeze CAC split (shared with Wave C training — no re-split)"
    )
    return EXIT_OK


def _identities(paths: Sequence[Path]) -> set[tuple[str, str]]:
    """(lemma, upos) already claimed by the layers an importer must not fight."""
    from ttrmorph.compile.layers import read_layer

    taken: set[tuple[str, str]] = set()
    for path in paths:
        layer, _ = read_layer(path)
        if layer is not None:
            taken |= {entry.identity for entry in layer.entries}
    return taken


def _target_lemmas(args) -> list[str]:
    from ttrmorph.importers.kaikki import read_targets, targets_from_frequencies

    lemmas: list[str] = []
    if getattr(args, "targets", None):
        lemmas.extend(read_targets(Path(args.targets)))
    if getattr(args, "targets_from_freq", None):
        lemmas.extend(
            targets_from_frequencies(Path(args.targets_from_freq), args.top)
        )
    return lemmas


def _import_kaikki(args) -> int:
    from ttrmorph.importers.emit import LayerHeader, render_layer
    from ttrmorph.importers.kaikki import do_import

    lemmas = _target_lemmas(args)
    if not lemmas:
        print("ttr-morph import-kaikki: no target lemmas", file=sys.stderr)
        return EXIT_USAGE

    entries, report = do_import(
        Path(args.jsonl), lemmas, exclude=_identities(args.exclude)
    )
    header = LayerHeader(
        layer="core-kaikki",
        language=args.lang,
        license="CC-BY-SA-4.0",
        attribution=KAIKKI_ATTRIBUTION,
        note=(
            "GENERATED — do not hand-edit; correct the source or the target "
            "list and re-run.\n"
            f"  ttr-morph import-kaikki {Path(args.jsonl).name} "
            f"-o {Path(args.output).name}\n"
            f"Targets: {len(set(lemmas))} lemmas. Of the entries found, "
            f"{report.classified} classified to a pattern and "
            f"{report.full_form} became full-form entries."
        ),
    )
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(render_layer(header, entries), encoding="utf-8")
    _print_kaikki(report, args.output)
    if args.report:
        Path(args.report).write_text(_kaikki_report(report), encoding="utf-8")
    return EXIT_OK


def _import_cac(args) -> int:
    from ttrmorph.importers.cac import attested_for, frequency_table, read
    from ttrmorph.importers.emit import LayerHeader, forms_entry, render_layer

    files = sorted(Path(args.cac).glob("*.conllu"))
    if not files:
        print(f"ttr-morph import-cac: no .conllu files in {args.cac}", file=sys.stderr)
        return EXIT_USAGE

    counts, report = read(files, side="train")
    if args.freq:
        Path(args.freq).parent.mkdir(parents=True, exist_ok=True)
        Path(args.freq).write_text(frequency_table(counts), encoding="utf-8")
        print(f"wrote {args.freq} ({report.lemmas} lemmas)")
    if not args.output:
        # Frequency-table-only run. The target list for the importers is built
        # from this table, so it has to exist before either importer runs.
        return EXIT_OK

    lemmas = _target_lemmas(args)
    taken = _identities(args.exclude)
    grouped = attested_for(counts, lemmas, min_count=args.min_count)
    entries = [
        forms_entry(
            lemma,
            upos,
            [(row.form, row.feats) for row in rows],
            "cac",
        )
        for (lemma, upos), rows in sorted(grouped.items())
        if (lemma, upos) not in taken
    ]
    header = LayerHeader(
        layer="core-cac",
        language=args.lang,
        license="CC-BY-SA-4.0",
        attribution=CAC_ATTRIBUTION,
        note=(
            "GENERATED — attested forms only, from the TRAIN side of the frozen "
            "split.\n"
            "Nothing here is classified: a corpus row is evidence that a form "
            "exists, not a paradigm.\n"
            f"  ttr-morph import-cac <dir> -o {Path(args.output).name} "
            f"--min-count {args.min_count}\n"
            f"{report.sentences_read} sentences, {report.tokens} tokens, "
            f"{len(entries)} entries."
        ),
    )
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(render_layer(header, entries), encoding="utf-8")
    print(
        f"wrote {args.output}: {len(entries)} entries from "
        f"{report.sentences_read} train-side sentences"
    )
    return EXIT_OK


def _print_kaikki(report, output) -> None:
    print(f"wrote {output}")
    print(f"  entries read      {report.entries_read}")
    print(f"  in target list    {report.entries_in_target}")
    print(f"  classified        {report.classified}")
    print(f"  full-form         {report.full_form}")
    print(f"  no usable table   {report.no_table}")
    print(f"  forms dropped     {report.dropped_forms}")
    print(f"  reproduce rate    {report.reproduce_rate:.0%}")


def _kaikki_report(report) -> str:
    lines = [
        "# kaikki import — D-F1-alpha conformance run",
        "",
        "| measure | value |",
        "|---|--:|",
        f"| entries read | {report.entries_read} |",
        f"| entries in the target list | {report.entries_in_target} |",
        f"| classified to a pattern | {report.classified} |",
        f"| full-form (no pattern reproduced) | {report.full_form} |",
        f"| no usable table | {report.no_table} |",
        f"| forms dropped (register / outside / unmapped) | {report.dropped_forms} |",
        f"| reproduce rate | {report.reproduce_rate:.1%} |",
        "",
        "## Sample mismatches",
        "",
    ]
    lines += [f"- `{lemma}` ({upos})" for lemma, upos, _ in report.samples]
    return "\n".join(lines) + "\n"


# ── the eval lane (P8.4) ─────────────────────────────────────────────────────


def _eval(args) -> int:
    from ttrmorph.eval import harness

    missing = [path for path in args.snapshot if not Path(path).exists()]
    if missing:
        print(f"ttr-morph eval: no such snapshot: {missing}", file=sys.stderr)
        return EXIT_USAGE

    state = harness.load_state(args.snapshot)
    report = harness.build_report(
        state, cac=args.cac, cases_dir=args.cases, side=args.split
    )

    if args.as_json:
        print(json.dumps(report.as_dict(), indent=2, ensure_ascii=False))
    else:
        print(
            f"cases {report.cases_passed}/{len(report.cases)} · "
            f"targets {report.targets_covered}/{report.targets_total} · "
            f"{report.rows} rows, {report.forms} forms"
        )
        if report.metrics is not None:
            total = report.metrics.total
            print(
                f"corpus: coverage {total.coverage:.1%} · "
                f"lemma-in-set {total.lemma_in_set:.1%} · "
                f"head-of-list {total.head_of_list:.1%} · "
                f"fold-collision {total.fold_collision_rate:.1%}"
            )

    output = args.output or harness.REPORT_PATH
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_text(harness.render(report), encoding="utf-8")
    if not args.as_json:
        print(f"wrote {output}")

    if args.write_baseline:
        target = args.baseline or harness.BASELINE_PATH
        Path(target).write_text(
            json.dumps(harness.baseline_from(report), indent=2, ensure_ascii=False)
            + "\n",
            encoding="utf-8",
        )
        print(f"wrote {target} — read the numbers before committing them")

    if not args.gate:
        # Without --gate a failing case is still a failure: the cases are the
        # acceptance criteria, not a statistic, and a run that printed "2/3"
        # and exited 0 would be a run somebody scripted around.
        return EXIT_OK if report.cases_passed == len(report.cases) else EXIT_NO_ANSWER

    failures = harness.check(report, harness.read_baseline(args.baseline))
    for failure in failures:
        print(f"GATE {failure}", file=sys.stderr)
    if failures:
        print(f"\n{len(failures)} gate failure(s).", file=sys.stderr)
        return EXIT_NO_ANSWER
    print("gate: OK")
    return EXIT_OK


def _expand_lists(args) -> int:
    from ttrmorph.export.expand import expand_lists, read_config

    if not args.lists.is_dir():
        print(
            f"ttr-morph expand-lists: no such directory: {args.lists}",
            file=sys.stderr,
        )
        return EXIT_USAGE

    config = read_config(args.config)
    reports = expand_lists(args.lists, config, snapshots=args.snapshot)
    for report in reports:
        print(
            f"{report.list_id}\t{report.decision}\t"
            f"{report.terms} term(s) -> {report.emitted} entr(ies)"
            + (f"\t{len(report.unknown)} unknown" if report.unknown else "")
        )
    return EXIT_OK


#: Attribution blocks, S-2 (contracts §10). Written here rather than in a data
#: file because they are claims about what WE did to somebody else's material,
#: and the importer is what did it.
KAIKKI_ATTRIBUTION = {
    "source": "Wiktionary (English edition), via kaikki.org",
    "url": "https://kaikki.org/dictionary/Czech/",
    "license": "CC-BY-SA-4.0",
    "license_url": "https://creativecommons.org/licenses/by-sa/4.0/",
    "extracted": "2026-08-12",
    "transformation": (
        "Inflection tables were read from the extract, restricted to the cells "
        "this project generates, and matched against the Tatrman paradigm "
        "engine. Where a pattern reproduces the table exactly the entry is "
        "carried as a (vzor, flags) pair and its forms are regenerated; "
        "otherwise the extracted forms are carried verbatim. Entries are "
        "restricted to a target vocabulary."
    ),
}

CAC_ATTRIBUTION = {
    "source": "UD_Czech-CAC (Universal Dependencies)",
    "url": "https://github.com/UniversalDependencies/UD_Czech-CAC",
    "license": "CC-BY-SA-4.0",
    "license_url": "https://creativecommons.org/licenses/by-sa/4.0/",
    "extracted": "2026-08-12",
    "transformation": (
        "Attested (form, lemma, upos, feats) rows were counted over the train "
        "side of the frozen split manifest and carried as full-form entries "
        "for target-vocabulary lemmas. No sentence text is reproduced."
    ),
}


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
