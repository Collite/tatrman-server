# SPDX-License-Identifier: Apache-2.0
"""simplemma as a READ-ONLY second opinion (β(QA), NLS-P8.3 T7).

**simplemma never writes into a layer.** It is MIT-licensed and would be a
tempting shortcut — a lemma list for the whole language, free for the taking —
and taking it would replace the thing this effort is building with somebody
else's answer, whose provenance we would then have to explain in every
conversation the lexicon is the subject of. It is used here for exactly one
thing: to point at rows where our head-of-list lemma and its differ, so a human
can look at those rather than at 24,000.

A disagreement is not a defect on either side. simplemma is a dictionary lookup
with a fallback rule; we are a curated lexicon with an engine. When they
disagree, one of three things is true — we are wrong, it is wrong, or the token
is genuinely ambiguous and the ranking put a different reading first. This
script sorts them by frequency so the ones worth a human minute come first.

Read-only is a test, not a promise: `tests/test_qa_guard.py` asserts that
nothing outside this directory imports simplemma.

    uv run python eval/qa_simplemma.py --cac <dir> --snapshot dist/cs.morph.snap \\
        -o eval/qa-simplemma.md
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from ttrnlp.morph import load_morph  # noqa: E402

from ttrmorph.importers.cac import read  # noqa: E402

SAMPLE = 40


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cac", type=Path, required=True)
    parser.add_argument("--snapshot", type=Path, action="append", required=True)
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args(argv)

    try:
        import simplemma
    except ImportError:
        print("simplemma is a dev dependency: uv add --dev simplemma", file=sys.stderr)
        return 2

    state = load_morph([str(path) for path in args.snapshot])
    counts, _ = read(sorted(Path(args.cac).glob("*.conllu")), side="train")

    agree = disagree = theirs_only = 0
    samples: Counter[tuple[str, str, str, str]] = Counter()

    for (form, gold, _, _), count in counts.items():
        result = state.lookup(form)
        ours = result.lemma if result else None
        # simplemma always answers — it falls back to the input form — so
        # there is no "neither" bucket and no "only we answer" one. The third
        # outcome is OUR coverage gap, which is the interesting one: we import
        # the query domain, not the language (FI-1/GI-1).
        theirs = simplemma.lemmatize(form, lang="cs")
        if ours is None:
            theirs_only += count
        elif ours == theirs:
            agree += count
        else:
            disagree += count
            samples[(form, gold, ours, theirs)] += count

    total = agree + disagree + theirs_only
    lines = [
        "# QA — our head-of-list lemma vs simplemma (read-only)",
        "",
        "simplemma is a **second opinion, never a source**: nothing in this",
        "comparison writes into a layer, and a disagreement is a row for a human",
        "to look at rather than a defect on either side. Measured over the TRAIN",
        "side of the frozen split, weighted by token count.",
        "",
        "| outcome | tokens | share |",
        "|---|--:|--:|",
        f"| both answer, same lemma | {agree} | {agree / total:.1%} |",
        f"| both answer, different lemma | {disagree} | {disagree / total:.1%} |",
        f"| not in our lexicon | {theirs_only} | {theirs_only / total:.1%} |",
        "",
        "## The disagreements worth a minute",
        "",
        "Sorted by how often the form occurs. `gold` is CAC's own lemma, which",
        "is the tie-break — it is the oracle both sides are eventually measured",
        "against (contracts §11).",
        "",
        "| form | CAC | ours | simplemma | tokens |",
        "|---|---|---|---|--:|",
    ]
    lines += [
        f"| {form} | {gold} | {ours} | {theirs} | {count} |"
        for (form, gold, ours, theirs), count in samples.most_common(SAMPLE)
    ]
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {args.output}: {disagree} disagreeing tokens of {total}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
