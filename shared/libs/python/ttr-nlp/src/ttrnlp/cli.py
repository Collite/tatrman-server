# SPDX-License-Identifier: Apache-2.0
"""``ttr-nlp`` — the command line (NL-15).

The command that matters is ``ttr-nlp validate``, and its whole point is that it
runs the *same* ``ttrnlp.packs.validate`` code path as the service boot and
``ReloadPacks``: a pack that validates on a laptop validates identically in the
cluster, and the DFP model-validator wraps this rather than reimplementing it.

The body lands at NLS-P2 together with the loader. The entry point exists from
P0 so the console script is wired, published and exercised from the first wheel
rather than appearing in a later release as a surprise.

Exit codes (contracts §9): 0 = no ERROR diagnostics, 1 = validation errors,
2 = usage or I/O failure.
"""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence

from ttrnlp import __version__

EXIT_OK = 0
EXIT_VALIDATION_ERRORS = 1
EXIT_USAGE = 2


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ttr-nlp",
        description="Tatrman NLP suite — rule-pack and gazetteer-list tooling.",
    )
    parser.add_argument("--version", action="version", version=f"ttr-nlp {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)

    validate = sub.add_parser(
        "validate",
        help="validate rule packs and gazetteer lists",
        description=(
            "Validate each PATH (a pack file, a pack directory, or a list "
            "directory) against the pack schema and the cross-checks."
        ),
    )
    validate.add_argument("paths", nargs="+", metavar="PATH")
    validate.add_argument(
        "--model",
        metavar="DIR",
        help="also cross-check QueryPattern query ids and parameter names "
        "against a TTR-M model (contracts §5)",
    )
    validate.add_argument(
        "--json",
        action="store_true",
        dest="as_json",
        help="emit diagnostics as JSON for machine consumption",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)

    if args.command == "validate":
        print(
            "ttr-nlp validate is not available yet — it arrives with the pack "
            "loader at NLS-P2.",
            file=sys.stderr,
        )
        return EXIT_USAGE

    return EXIT_USAGE  # pragma: no cover — argparse rejects unknown commands


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
