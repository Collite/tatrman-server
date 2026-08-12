# SPDX-License-Identifier: Apache-2.0
"""Rewrite the golden paradigm tables.

    uv run python tests/engine/golden/regen.py            # rewrite
    uv run python tests/engine/golden/regen.py --check    # CI: fail on drift

**These files are a frozen record, not an oracle.** The grammar was authored
into `seed/data/cs/vzory.yaml` from school tables and read back form by form;
this script freezes the result so that any later edit to the tables that
changes a single form has to change a golden file too, in a diff a reviewer can
read. That is the whole value: it does not prove the tables are right, it makes
them impossible to change quietly.

Which is also why a new golden must be *read* before it is committed. Adding a
case here and running the script proves only that the engine agrees with
itself.

`classify_as` records the answer `classify` deterministically returns when it
differs from the case's own (vzor, flags). Several patterns can be exactly
equivalent — a sub-vzor that only implies a flag generates precisely what its
parent generates with that flag written out — and `classify` returns the first
fit in table order. Recording that answer keeps the round-trip test exact
instead of loosening it to "something equivalent".
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "src"))

from ttrmorph.engine import classify, generate, load  # noqa: E402

HERE = Path(__file__).resolve().parent

#: (file stem, lemma, vzor, flags, note)
CASES: tuple[tuple[str, str, str, tuple[str, ...], str], ...] = (
    # ── nouns, one per school pattern ────────────────────────────────────────
    ("zena", "žena", "žena", (), "hard feminine"),
    ("ruze", "růže", "růže", (), "soft feminine"),
    (
        "pisen",
        "píseň",
        "píseň",
        ("fleeting-e",),
        "soft feminine, zero nominative — the citation form carries the "
        "fleeting vowel",
    ),
    ("kost", "kost", "kost", (), "i-declension feminine"),
    ("hrad", "hrad", "hrad", (), "hard masculine inanimate"),
    ("stroj", "stroj", "stroj", (), "soft masculine inanimate"),
    (
        "pan",
        "student",
        "pán",
        (),
        "hard masculine animate. The pattern is named after a lexeme whose "
        "vocative is irregularly short, so a regular representative carries "
        "the golden table",
    ),
    ("muz", "muž", "muž", (), "soft masculine animate"),
    ("predseda", "předseda", "předseda", (), "masculine animate in -a"),
    ("soudce", "soudce", "soudce", (), "masculine animate in -e"),
    ("mesto", "město", "město", (), "hard neuter"),
    ("more", "moře", "moře", (), "soft neuter"),
    ("kure", "kuře", "kuře", (), "neuter with the -et-/-at- extension"),
    ("staveni", "stavení", "stavení", (), "neuter in -i"),
    # ── adjectives ───────────────────────────────────────────────────────────
    ("mlady", "mladý", "mladý", (), "hard adjective, positive degree"),
    ("jarni", "jarní", "jarní", (), "soft adjective, positive degree"),
    # ── verbs: the query-relevant subset (GI-1) ──────────────────────────────
    ("delat", "dělat", "dělat", (), "class 5"),
    ("prosit", "prosit", "prosit", (), "class 4"),
    ("kupovat", "kupovat", "kupovat", (), "class 3, -ovat"),
    ("tisknout", "tisknout", "tisknout", (), "class 2, consonant stem"),
    ("minout", "minout", "minout", (), "class 2, vowel stem"),
    ("zacit", "začít", "začít", (), "class 1, -it with an -n- present"),
    # ── the flag cases ───────────────────────────────────────────────────────
    ("flag-fleeting-e", "pes", "pán", ("fleeting-e",), "vowel drops out"),
    ("flag-shorten", "dům", "hrad", ("shorten",), "stem vowel shortens"),
    (
        "flag-palatal-h",
        "Praha",
        "žena",
        ("palatal",),
        "h -> z before the dative/locative -e",
    ),
    (
        "flag-palatal-k",
        "matka",
        "žena",
        ("fleeting-e", "palatal"),
        "k -> c, and the genitive plural inserts the vowel back — two flags "
        "on one word, which is the case that pins the canonical order",
    ),
    (
        "flag-foreign-stem",
        "cyklus",
        "hrad-foreign",
        (),
        "the Latin nominative marker is not part of the stem. On the narrowing "
        "rather than on the bare pattern: the free locative doublet would "
        "otherwise produce a form nobody writes",
    ),
    ("flag-indeclinable", "atašé", "pán", ("indeclinable",), "no inflection"),
    (
        "flag-acronym",
        "ČEZ",
        "acronym-m",
        (),
        "declines AND stays invariant — both are written",
    ),
    # ── sub-vzory (B-O5) ─────────────────────────────────────────────────────
    (
        "sub-hrad-proper",
        "Kaufland",
        "hrad-proper",
        (),
        "proper inanimate: locative -u only, never the free doublet",
    ),
    (
        "sub-adj-ova",
        "Nováková",
        "adj-ova",
        (),
        "the adjectival surname is the hard adjective's feminine table",
    ),
    ("sub-muzeum-um", "muzeum", "muzeum-um", (), "Latin -um borrowings"),
    ("sub-pan-o", "Hugo", "pan-o", (), "animate in -o"),
    (
        "sub-predseda-name",
        "Kundera",
        "predseda-name",
        (),
        "-a surnames and given names",
    ),
    ("sub-zena-proper", "Ostrava", "zena-proper", (), "feminine place names"),
    (
        "sub-indeclinable-n",
        "taxi",
        "indeclinable-n",
        (),
        "neuter borrowing, no inflection",
    ),
    (
        "sub-indeclinable-m",
        "atašé",
        "indeclinable-m",
        (),
        "the same word as the bare `indeclinable` flag case, reached through "
        "the narrowing the guesser proposes instead",
    ),
    ("sub-ruze-proper", "Florencie", "ruze-proper", (), "soft feminine place names"),
)


def build(lemma: str, vzor: str, flags: tuple[str, ...], note: str) -> dict:
    table: dict[str, list[str]] = {}
    for form, feats in generate(lemma, vzor, flags):
        table.setdefault(feats, []).append(form)
    doc = {
        "note": note,
        "lemma": lemma,
        "vzor": vzor,
        "flags": list(load("cs").order_flags(flags)),
        "table": {feats: sorted(forms) for feats, forms in sorted(table.items())},
    }
    answer = classify(doc["table"])
    if answer is None:
        raise SystemExit(f"{lemma}: classify found no pattern — the tables changed")
    if list(answer[1]) != doc["flags"] or answer[0] != vzor:
        doc["classify_as"] = {"vzor": answer[0], "flags": list(answer[1])}
    return doc


def render(doc: dict) -> str:
    body = yaml.safe_dump(doc, allow_unicode=True, sort_keys=False, width=100)
    return (
        "# SPDX-License-Identifier: Apache-2.0\n"
        "#\n"
        "# NLS-P8.1 T3 — a golden paradigm table. Regenerate with\n"
        "# `uv run python tests/engine/golden/regen.py`; read the diff before\n"
        "# committing it.\n"
        f"{body}"
    )


def main(argv: list[str]) -> int:
    check = "--check" in argv
    stale: list[str] = []
    for stem, lemma, vzor, flags, note in CASES:
        path = HERE / f"{stem}.yaml"
        text = render(build(lemma, vzor, flags, note))
        if check:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                stale.append(path.name)
        else:
            path.write_text(text, encoding="utf-8")
    if check and stale:
        print("stale goldens: " + ", ".join(stale))
        return 1
    print("golden paradigm tables are current" if check else f"wrote {len(CASES)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
