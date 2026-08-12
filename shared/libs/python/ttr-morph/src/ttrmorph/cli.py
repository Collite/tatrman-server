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
``morph/v*`` tag lane runs to cut an artifact.

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


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
