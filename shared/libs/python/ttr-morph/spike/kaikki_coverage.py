# SPDX-License-Identifier: Apache-2.0
"""D-O1 — does kaikki carry enough Czech inflection to seed the core lexicon?

    uv run python spike/kaikki_coverage.py <extract.jsonl> [...] --out report.md

A **spike**, and it stays one: it is not imported by the package, it has no
tests, and the importer NLS-P8.3 builds is not this code. What it produces is a
number that decides how much of the seeding budget goes to import and how much
to the LLM bootstrap — a poor hit rate re-weights the plan, it does not change
the design.

The question is narrower than "how big is the extract". A word is only useful
to this lexicon if it comes with an **inflection table**: a lemma with no forms
is a lemma the paradigm engine would have to guess a vzor for, which is exactly
what the enrichment loop already does and not what an import is for. So the
counted thing is entries carrying at least `MIN_FORMS` distinct forms with
inflectional tags, and the headline number is the hit rate on the target
vocabulary rather than the total.

Input is the per-language JSONL from kaikki.org — one word per line, with
``word``, ``pos``, ``senses`` and ``forms: [{form, tags[]}]``. Both editions
are worth measuring separately: the Czech Wiktionary knows Czech declension
better, the English one has broader coverage, and they are not the same corpus.

Licence: kaikki extracts are Wiktionary-derived, CC BY-SA. That is a
share-alike **member file** in this artifact (C-F3/S-2), never merged into a
suite-licensed layer. Nothing on the poison list (D-epsilon/Q-6) is touched
here.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

import yaml

TARGETS = (
    Path(__file__).resolve().parents[1]
    / "src"
    / "ttrmorph"
    / "seed"
    / "data"
    / "cs"
    / "target-words.yaml"
)

#: A form only counts if a tag says which cell it is. Wiktionary form lists mix
#: real paradigm cells with spelling variants, romanizations and headword
#: repeats; without this filter an entry with one "lowercase" alternative would
#: score as an inflection table.
INFLECTIONAL = frozenset(
    {
        "nominative",
        "genitive",
        "dative",
        "accusative",
        "vocative",
        "locative",
        "instrumental",
        "singular",
        "plural",
        "dual",
        "present",
        "past",
        "future",
        "imperative",
        "conditional",
        "participle",
        "transgressive",
        "first-person",
        "second-person",
        "third-person",
        "comparative",
        "superlative",
    }
)

#: Enough cells to be worth importing. Two forms is a headword plus a variant;
#: a real paradigm has a dozen.
MIN_FORMS = 4

#: The upos each kaikki `pos` maps to for reporting. Anything absent is
#: counted under `other` — the spike does not need a complete mapping, it needs
#: the four categories the core lexicon is made of.
POS_MAP = {
    "noun": "NOUN",
    "name": "PROPN",
    "adj": "ADJ",
    "verb": "VERB",
    "adv": "ADV",
    "pron": "PRON",
    "num": "NUM",
    "prep": "ADP",
    "conj": "CCONJ",
    "particle": "PART",
    "intj": "INTJ",
}


def inflected_forms(entry: dict) -> set[str]:
    out: set[str] = set()
    for form in entry.get("forms") or []:
        text = (form.get("form") or "").strip()
        tags = set(form.get("tags") or ())
        if not text or text == "-" or not (tags & INFLECTIONAL):
            continue
        out.add(text)
    return out


def scan(path: Path) -> dict:
    per_pos = Counter()
    per_pos_with_table = Counter()
    lemmas: dict[str, dict] = {}
    raw_nouns: dict[str, dict] = {}
    words = 0

    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            entry = json.loads(line)
            if entry.get("lang_code") != "cs":
                continue
            words += 1
            upos = POS_MAP.get(entry.get("pos", ""), "other")
            per_pos[upos] += 1
            forms = inflected_forms(entry)
            if len(forms) >= MIN_FORMS:
                per_pos_with_table[upos] += 1
            word = entry.get("word", "")
            best = lemmas.get(word)
            if best is None or len(forms) > len(best["forms"]):
                lemmas[word] = {"upos": upos, "forms": forms, "pos": entry.get("pos")}
            if upos == "NOUN" and len(forms) >= MIN_FORMS:
                kept = raw_nouns.get(word)
                if kept is None or len(entry.get("forms") or ()) > len(
                    kept.get("forms") or ()
                ):
                    raw_nouns[word] = entry

    return {
        "path": path,
        "entries": words,
        "per_pos": per_pos,
        "per_pos_with_table": per_pos_with_table,
        "lemmas": lemmas,
        "raw_nouns": raw_nouns,
    }


# ── the second question: can the engine REPRODUCE what kaikki carries? ───────
#
# Coverage says a source has a table. It does not say the table is one this
# lexicon can store compactly. `classify` is the importer's actual gate
# (D-F1-alpha): a table it reproduces becomes a four-line vzor entry, a table
# it does not becomes a full-form entry carrying LM-MORPH-005. The ratio
# between those two is the difference between a lexicon an analyst can read and
# a pile of forms, so it is measured here rather than discovered at p8-3.

CASE_TAGS = {
    "nominative": "Nom",
    "genitive": "Gen",
    "dative": "Dat",
    "accusative": "Acc",
    "vocative": "Voc",
    "locative": "Loc",
    "instrumental": "Ins",
}
NUMBER_TAGS = {"singular": "Sing", "plural": "Plur"}
GENDER_TAGS = {
    frozenset({"feminine"}): "Gender=Fem",
    frozenset({"neuter"}): "Gender=Neut",
    frozenset({"masculine", "animate"}): "Animacy=Anim|Gender=Masc",
    frozenset({"masculine", "inanimate"}): "Animacy=Inan|Gender=Masc",
}

#: The two editions say gender in two different places. The Czech Wiktionary
#: puts it in the entry's own tags; the English one puts it in the headword
#: template's first argument. Reading only one of them is how the first run of
#: this spike scored the English edition at zero usable entries.
HEAD_GENDER = {
    "f": "Gender=Fem",
    "n": "Gender=Neut",
    "m-an": "Animacy=Anim|Gender=Masc",
    "m-anml": "Animacy=Anim|Gender=Masc",
    "m-pr": "Animacy=Anim|Gender=Masc",
    "m-in": "Animacy=Inan|Gender=Masc",
}


def gender_of(entry: dict) -> str | None:
    direct = GENDER_TAGS.get(frozenset(entry.get("tags") or ()))
    if direct:
        return direct
    for template in entry.get("head_templates") or ():
        arg = (template.get("args") or {}).get("1")
        if arg in HEAD_GENDER:
            return HEAD_GENDER[arg]
    return None

#: How many noun entries the classify pass looks at. Taken at an even stride
#: across the sorted vocabulary rather than from the front: the first two
#: thousand words alphabetically are almost all proper names and rare
#: borrowings, and measuring those would answer a question nobody asked.
CLASSIFY_SAMPLE = 2000


def noun_table(entry: dict) -> tuple[dict[str, list[str]], str] | None:
    """A kaikki noun entry as a feats -> forms table, or None if unusable.

    Skipped rather than guessed at: an entry whose gender tags are missing or
    contradictory, and any single form carrying a register or dialect tag on
    top of its case and number. Those forms are real words but they are not
    cells of the school paradigm, and feeding them to an exact matcher would
    manufacture mismatches that say nothing about the source's quality.
    """
    gender = gender_of(entry)
    if gender is None:
        return None
    table: dict[str, list[str]] = {}
    for form in entry.get("forms") or []:
        text = (form.get("form") or "").strip()
        tags = set(form.get("tags") or ())
        cases = tags & set(CASE_TAGS)
        numbers = tags & set(NUMBER_TAGS)
        if not text or len(cases) != 1 or len(numbers) != 1:
            continue
        if tags - cases - numbers:
            continue  # a register/dialect marker rides along
        atoms = [
            f"Case={CASE_TAGS[cases.pop()]}",
            f"Number={NUMBER_TAGS[numbers.pop()]}",
            *gender.split("|"),
        ]
        feats = "|".join(sorted(atoms))
        table.setdefault(feats, [])
        if text not in table[feats]:
            table[feats].append(text)
    # The citation form is the headword, so kaikki does not repeat it in the
    # form list. Without it the table has a hole where every vzor keys.
    citation = "|".join(sorted([*gender.split("|"), "Case=Nom", "Number=Sing"]))
    table.setdefault(citation, [entry["word"]])
    return table, gender


def classify_pass(result: dict) -> dict:
    from ttrmorph.engine import classify  # noqa: PLC0415 — spike-only import

    fits: list[str] = []
    misses: list[tuple[str, int]] = []
    skipped = 0
    looked = 0
    words = sorted(result["raw_nouns"])
    stride = max(1, len(words) // CLASSIFY_SAMPLE)
    for word in words[::stride]:
        built = noun_table(result["raw_nouns"][word])
        if built is None:
            skipped += 1
            continue
        table, _ = built
        if len(table) < MIN_FORMS:
            skipped += 1
            continue
        looked += 1
        if classify(table) is not None:
            fits.append(word)
        else:
            misses.append((word, len(table)))
    # A complete noun paradigm is 14 cells. How many of the misses are short of
    # that says which problem this is: an incomplete source table is a
    # different fix (fill it, or accept a full-form entry) from a table the
    # vzor inventory genuinely cannot express.
    incomplete = sum(1 for _, cells in misses if cells < 14)
    return {
        "looked": looked,
        "fits": fits,
        "misses": misses,
        "skipped": skipped,
        "incomplete": incomplete,
    }


def targets() -> dict[str, list[str]]:
    doc = yaml.safe_load(TARGETS.read_text(encoding="utf-8"))
    return doc["groups"]


def measure(result: dict, groups: dict[str, list[str]]) -> dict:
    lemmas = result["lemmas"]
    hits: dict[str, list[str]] = defaultdict(list)
    absent: dict[str, list[str]] = defaultdict(list)
    tableless: dict[str, list[str]] = defaultdict(list)

    for group, words in groups.items():
        for word in words:
            entry = lemmas.get(word)
            if entry is None:
                absent[group].append(word)
            elif len(entry["forms"]) >= MIN_FORMS:
                hits[group].append(word)
            else:
                tableless[group].append(word)
    return {"hits": hits, "absent": absent, "tableless": tableless}


def render(results: list[dict], groups: dict[str, list[str]]) -> str:
    total_targets = sum(len(words) for words in groups.values())
    lines = [
        "# D-O1 — kaikki Czech coverage spike",
        "",
        "> NLS-P8.1 T1. **Gate: read before the kaikki importer (p8-3 T4) "
        "starts.** A poor hit rate re-weights seeding toward the LLM "
        "bootstrap; it does not change the design.",
        "",
        "Generated by `spike/kaikki_coverage.py`. A word counts as *covered* "
        f"only if its entry carries at least {MIN_FORMS} distinct forms with "
        "inflectional tags — a lemma with no paradigm is a lemma the engine "
        "would still have to guess a vzor for.",
        "",
        "## Sources",
        "",
        "| source | entries (cs) | with an inflection table |",
        "|---|--:|--:|",
    ]
    for result in results:
        with_table = sum(result["per_pos_with_table"].values())
        share = with_table / result["entries"] * 100 if result["entries"] else 0
        lines.append(
            f"| `{result['path'].name}` | {result['entries']:,} | "
            f"{with_table:,} ({share:.0f}%) |"
        )

    lines += ["", "## Tables by part of speech", ""]
    for result in results:
        lines += [
            f"### `{result['path'].name}`",
            "",
            "| upos | entries | with table | % |",
            "|---|--:|--:|--:|",
        ]
        for upos, count in result["per_pos"].most_common():
            table = result["per_pos_with_table"][upos]
            pct = table / count * 100 if count else 0
            lines.append(f"| {upos} | {count:,} | {table:,} | {pct:.0f}% |")
        lines.append("")

    lines += [
        "## Hit rate on the target vocabulary",
        "",
        f"`seed/data/cs/target-words.yaml` — {total_targets} lemmas.",
        "",
        "| source | covered | present, no table | absent |",
        "|---|--:|--:|--:|",
    ]
    measured = [(r, measure(r, groups)) for r in results]
    for result, scores in measured:
        covered = sum(len(v) for v in scores["hits"].values())
        tableless = sum(len(v) for v in scores["tableless"].values())
        absent = sum(len(v) for v in scores["absent"].values())
        pct = covered / total_targets * 100 if total_targets else 0
        lines.append(
            f"| `{result['path'].name}` | {covered} ({pct:.0f}%) | "
            f"{tableless} | {absent} |"
        )

    lines += ["", "### By group", "", "| group | size | " + " | ".join(
        f"`{r['path'].name}`" for r, _ in measured
    ) + " |", "|---|--:|" + "--:|" * len(measured)]
    for group, words in groups.items():
        cells = []
        for _, scores in measured:
            covered = len(scores["hits"].get(group, ()))
            cells.append(f"{covered}/{len(words)}")
        lines.append(f"| {group} | {len(words)} | " + " | ".join(cells) + " |")

    lines += [
        "",
        "## Can the engine reproduce what the source carries?",
        "",
        "Coverage says a source has a table; it does not say the table is one "
        "this lexicon can store compactly. `classify` is the importer's actual "
        "gate (D-F1-α) — a table it reproduces becomes a four-line vzor entry, "
        "one it does not becomes a full-form entry carrying `LM-MORPH-005`.",
        "",
        f"About {CLASSIFY_SAMPLE} noun entries per source, taken at an even "
        "stride across the sorted vocabulary — deterministic, and not the "
        "first N, which would be almost entirely proper names. Entries with "
        "missing or contradictory gender tags, and forms carrying a register "
        "marker on top of case and number, are skipped rather than guessed at.",
        "",
        "A complete noun paradigm is 14 cells; `short tables` counts the "
        "unreproduced ones that have fewer, which separates an incomplete "
        "source table from one the vzor inventory genuinely cannot express.",
        "",
        "| source | nouns tried | reproduced | skipped | short tables |",
        "|---|--:|--:|--:|--:|",
    ]
    passes = []
    for result in results:
        outcome = classify_pass(result)
        passes.append((result, outcome))
        fits = len(outcome["fits"])
        pct = fits / outcome["looked"] * 100 if outcome["looked"] else 0
        short = outcome["incomplete"]
        of = len(outcome["misses"])
        lines.append(
            f"| `{result['path'].name}` | {outcome['looked']:,} | "
            f"{fits:,} ({pct:.0f}%) | {outcome['skipped']:,} | "
            f"{short:,} of {of:,} misses |"
        )
    lines.append("")
    for result, outcome in passes:
        # Strided again: the first ten misses alphabetically say nothing except
        # that the alphabet starts with A.
        misses = [word for word, _ in outcome["misses"]]
        step = max(1, len(misses) // 10)
        sample = ", ".join(f"`{word}`" for word in misses[::step][:10])
        lines.append(
            f"- `{result['path'].name}` — 10 tables no pattern reproduces: "
            + (sample or "_none_")
        )
        complete = [word for word, cells in outcome["misses"] if cells >= 14]
        suffixes = Counter(word[-3:] for word in complete)
        shape = ", ".join(f"`-{suffix}` ({n})" for suffix, n in suffixes.most_common(8))
        if shape:
            lines.append(f"  - commonest endings among complete-table misses: {shape}")
    lines.append("")

    lines += ["## Sample misses", ""]
    for result, scores in measured:
        lines += [f"### `{result['path'].name}`", ""]
        absent = [w for words in scores["absent"].values() for w in words]
        tableless = [w for words in scores["tableless"].values() for w in words]
        lines.append(f"- **absent entirely** ({len(absent)}): "
                     + ", ".join(f"`{w}`" for w in absent[:10]))
        lines.append(f"- **present without a table** ({len(tableless)}): "
                     + ", ".join(f"`{w}`" for w in tableless[:10]))
        lines.append("")

    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("extracts", nargs="+", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument(
        "--verdict",
        type=Path,
        help=(
            "a hand-written file appended verbatim. The measurements are "
            "generated and the reading of them is not; keeping the judgement "
            "in its own file is what stops a re-run from deleting it."
        ),
    )
    args = parser.parse_args(argv)

    results = [scan(path) for path in args.extracts]
    report = render(results, targets())
    if args.verdict:
        report += "\n" + args.verdict.read_text(encoding="utf-8")
    if args.out:
        args.out.write_text(report, encoding="utf-8")
        print(f"wrote {args.out}")
    else:
        print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
