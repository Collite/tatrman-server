#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Stamp the test artifacts' derived header fields, or check they are current.

The fixture snapshot and overlay in this directory are **hand-authored**: the
rows are the specification and a human edits them. Three things in them are
*derived* from those rows and must not be — ``#rows``, ``#content-hash`` and the
``#fold-index`` section. Recomputing them by hand after every edit is the kind
of chore that ends with a checked-in artifact whose hash no longer matches its
own body, which the loader then correctly refuses, from a fixture, in CI.

So, from the wheel directory::

    uv run python tests/fixtures/morph/regen.py          # rewrite derived parts
    uv run python tests/fixtures/morph/regen.py --check  # exit 1 if stale

(``uv run`` because the loader's diagnostics share the suite's `Diagnostic`
shape, which lives behind the wheel's own dependencies.)

The ``--check`` form is the p7-2 verify gate and runs as a test as well.

This is deliberately *not* the compiler. `ttr-morph` (NLS-P8) compiles layer
files into real snapshots; this only fixes up what a human cannot be asked to
compute. It shares the two functions that matter — `content_hash` and `fold` —
by importing them, so a fixture can never disagree with the loader about either.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# The wheel's src layout is on sys.path under pytest; running this by hand from
# a checkout needs the same thing said explicitly.
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "src"))

from ttrnlp.morph.records import fold  # noqa: E402
from ttrnlp.morph.snapshot import (  # noqa: E402
    FOLD_INDEX_SECTION,
    NE_EXCEPTIONS_SECTION,
    SNAPSHOT_MAGIC,
    content_hash,
)

HERE = Path(__file__).parent
ARTIFACTS = ("cs-test.morph.snap", "world-test.morph.overlay")


def _split(text: str) -> tuple[list[str], list[str], list[str], list[str]]:
    """(header lines, column line + rows, fold lines, ne lines)."""
    header: list[str] = []
    rows: list[str] = []
    fold_lines: list[str] = []
    ne_lines: list[str] = []
    section = "header"

    for line in text.splitlines():
        if line.startswith(FOLD_INDEX_SECTION):
            section = "fold"
            continue
        if line.startswith(NE_EXCEPTIONS_SECTION):
            section = "ne"
            continue
        if section == "fold":
            if line.strip():
                fold_lines.append(line)
            continue
        if section == "ne":
            if line.strip():
                ne_lines.append(line)
            continue
        if line.startswith("#") and section == "header" and not rows:
            header.append(line)
            continue
        if line.strip():
            rows.append(line)
    return header, rows, fold_lines, ne_lines


def _render(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    header, rows_with_columns, _, ne_lines = _split(text)
    columns, *rows = rows_with_columns
    is_snapshot = header[0].strip() == SNAPSHOT_MAGIC

    digest = content_hash(rows)
    out: list[str] = []
    for line in header:
        key, _, _value = line[1:].partition(":")
        if key.strip() == "content-hash":
            out.append(f"#content-hash: {digest}")
        elif key.strip() == "rows":
            out.append(f"#rows: {len(rows)}")
        else:
            out.append(line)
    out.append(columns)
    out.extend(rows)

    if is_snapshot:
        # The fold index is compiled INTO the artifact (B-F4-α) — the loader
        # reads it rather than computing it, so it has to be built here.
        index: dict[str, list[str]] = {}
        for row in rows:
            form = row.split("\t")[0]
            bucket = index.setdefault(fold(form), [])
            if form not in bucket:
                bucket.append(form)
        out.append("")
        out.append(FOLD_INDEX_SECTION)
        for key in sorted(index):
            out.append(f"{key}\t{','.join(index[key])}")

    if ne_lines:
        out.append("")
        out.append(NE_EXCEPTIONS_SECTION)
        out.extend(ne_lines)

    return "\n".join(out) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify the derived fields are current instead of rewriting them",
    )
    args = parser.parse_args()

    stale = []
    for name in ARTIFACTS:
        path = HERE / name
        rendered = _render(path)
        if args.check:
            if path.read_text(encoding="utf-8") != rendered:
                stale.append(name)
        else:
            path.write_text(rendered, encoding="utf-8")
            print(f"stamped {name}")

    if stale:
        print(
            "stale (run `python tests/fixtures/morph/regen.py`): "
            + ", ".join(stale),
            file=sys.stderr,
        )
        return 1
    if args.check:
        print("morph fixtures are current")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
