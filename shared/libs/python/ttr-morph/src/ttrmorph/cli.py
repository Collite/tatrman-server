# SPDX-License-Identifier: Apache-2.0
"""``ttr-morph`` — the editorial command line.

Stdlib ``argparse`` and nothing else, matching ``ttr-nlp``: a CLI framework
would be a dependency of the container image for no gain, and the two commands
in this repo should not answer ``--help`` in two different dialects.

Subcommands land as the arc does. Here at NLS-P8.1 the engine is what exists,
so the commands are the ones that answer "what does this pattern do" and "which
pattern is this" — the two questions an analyst asks before assigning a vzor,
and the two an importer asks per row.

Exit codes follow ``ttr-nlp``'s split, and for the same reason — whose mistake
it is::

    0   the question had an answer
    1   the question was well-formed and the answer is no (no pattern fits)
    2   the command could not be run as asked
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Sequence
from pathlib import Path

import yaml

from ttrmorph import __version__
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


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
