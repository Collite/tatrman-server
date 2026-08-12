# SPDX-License-Identifier: Apache-2.0
"""kaikki.org Wiktionary extract → a layer (D-F1-α, D-O1's verdict).

**Classify, do not believe** (D-F1-α). The importer never writes down a vzor
because a table looked plausible. It builds the table the extract gives, asks
which pattern *reproduces* it, and writes a compact entry only when one does.
A table no pattern reproduces becomes a full-form entry carrying the rejected
guess, so the compiler raises `LM-MORPH-005` and the studio has something to
show a human. That is why this importer is also the engine's conformance
harness: every run measures the paradigm tables against ~thousands of
independently-authored ones.

⚑ **The projection rule, which p8-1 flagged as a task in no list.** The engine
generates the *query-relevant* subset (GI-1): for a verb, infinitive + present
+ imperative + l-participle. Wiktionary's conjugation table also carries
transgressives, passives, adjectival participles and a verbal noun. Exact
equality both directions — what `engine.classify` requires — would therefore
reject every verb in the source, for a reason that is not a defect in either.

So the match here is one-directional and stated plainly:

    every cell the ENGINE generates must be present in the SOURCE table and
    agree with it exactly; the source may carry cells the engine does not.

For nouns this is *identical* to strict classification — the engine generates
all fourteen cells, so "the source covers our cells" means "the source has a
complete table", which is exactly the property D-O1 measured and exactly the
property an incomplete extract fails. Nothing is loosened where the strictness
was buying something. `engine.classify` itself is untouched: contracts §4
defines it as exact, and an importer's tolerance does not belong inside it.

**The target list is the point** (FI-1/GI-1). We import the query domain, not
the language: a 3,000-lemma lexicon that answers business questions beats a
70,000-lemma one that also inflects *pravěký*.
"""

from __future__ import annotations

import json
from collections.abc import Iterable, Iterator, Mapping, Sequence
from dataclasses import dataclass, field
from functools import cache
from itertools import combinations
from pathlib import Path

import yaml

from ttrmorph.engine import EngineError, generate, load
from ttrmorph.importers.sources import require_allowed

SOURCE = "kaikki"

#: The tag tables (LM-2 — no tag name appears in this module).
TAGS_PATH = (
    Path(__file__).resolve().parents[1] / "seed" / "data" / "cs" / "kaikki-tags.yaml"
)

#: kaikki writes a missing cell as a bare dash.
_EMPTY = {"", "-", "—"}

#: How many flags may be tried together. Two covers every attested combination
#: in the tables (`fleeting-e` + `palatal` on *matka*-shaped stems); three would
#: multiply the search by six for combinations no Czech word has.
MAX_FLAGS = 2


@dataclass
class KaikkiReport:
    """What a run saw. Committed with the importers report — this IS D-F1-α."""

    entries_read: int = 0
    entries_in_target: int = 0
    classified: int = 0
    full_form: int = 0
    no_table: int = 0
    skipped_pos: int = 0
    dropped_forms: int = 0
    #: (lemma, upos, rejected vzor or "") for the first few mismatches.
    samples: list[tuple[str, str, str]] = field(default_factory=list)

    @property
    def usable(self) -> int:
        return self.classified + self.full_form

    @property
    def reproduce_rate(self) -> float:
        return self.classified / self.usable if self.usable else 0.0


@dataclass(frozen=True)
class Tags:
    """The tag tables, loaded."""

    pos: Mapping[str, str]
    atoms: Mapping[str, str]
    ignore: frozenset[str]
    reject: frozenset[str]
    outside: frozenset[str]
    compare_ignore: frozenset[str]
    absent: Mapping[str, Sequence[Sequence[str]]]
    require: Mapping[str, Sequence[str]]
    gender_from_entry: frozenset[str]
    head_gender: Mapping[str, Sequence[str]]
    entry_gender: Mapping[str, Sequence[str]]
    defaults: Mapping[str, Sequence[str]]


@cache
def load_tags(path: Path | None = None) -> Tags:
    raw = yaml.safe_load((path or TAGS_PATH).read_text(encoding="utf-8"))
    return Tags(
        pos=raw["pos"],
        atoms=raw["atoms"],
        ignore=frozenset(raw.get("ignore", ())),
        reject=frozenset(raw.get("reject", ())),
        outside=frozenset(raw.get("outside", ())),
        compare_ignore=frozenset(raw.get("compare_ignore", ())),
        absent=raw.get("absent", {}),
        require=raw.get("require", {}),
        gender_from_entry=frozenset(raw.get("gender_from_entry", ())),
        head_gender=raw.get("head_gender", {}),
        entry_gender=raw.get("entry_gender", {}),
        defaults=raw.get("defaults", {}),
    )


def entry_atoms(entry: Mapping, upos: str, tags: Tags) -> list[str] | None:
    """The atoms the LEXEME carries, which its cells do not state.

    ``None`` means the entry does not say — and an entry that does not say its
    gender is one this importer must not guess at, because a wrong gender turns
    every cell into a mismatch and would be counted as the engine's failure.
    """
    if upos not in tags.gender_from_entry:
        return []
    own = set(entry.get("tags") or ())
    from_tags = [
        atom for tag, atoms in tags.entry_gender.items() if tag in own for atom in atoms
    ]
    if from_tags:
        return sorted(set(from_tags))
    for template in entry.get("head_templates") or ():
        arg = (template.get("args") or {}).get("1")
        if arg in tags.head_gender:
            return sorted(tags.head_gender[arg])
    return None


# ── reading the extract ──────────────────────────────────────────────────────


def entries(path: Path) -> Iterator[dict]:
    """Stream the JSONL. One line, one word sense-group."""
    require_allowed(SOURCE, path)
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                yield json.loads(line)


def table_of(
    entry: Mapping, tags: Tags, *, upos: str = ""
) -> tuple[dict[str, set[str]], int]:
    """The inflection table an entry carries: ``{feats: {form}}``, plus a count
    of forms dropped as outside the generated subset.

    A form is dropped, not skipped-with-a-shrug, in three cases: it carries a
    register marker (`reject` — it exists, but it is not the neutral cell), it
    realises a cell the engine does not generate (`outside`), or its tags carry
    something this table has no atom for. The third is the honest one to count:
    an unmapped tag means the table is *not* the table we think it is, and
    matching on it would be matching on a guess.
    """
    table: dict[str, set[str]] = {}
    dropped = 0
    lexeme = entry_atoms(entry, upos, tags)
    if lexeme is None:
        return {}, 0
    defaults = tags.defaults.get(upos, ())
    required = set(tags.require.get(upos, ()))

    for form in entry.get("forms") or ():
        text = (form.get("form") or "").strip()
        raw = set(form.get("tags") or ())
        if not text or text in _EMPTY:
            continue
        if raw & tags.ignore:
            continue
        if raw & tags.reject:
            dropped += 1
            continue
        if raw & tags.outside:
            dropped += 1
            continue
        atoms = [tags.atoms[tag] for tag in raw if tag in tags.atoms]
        if len(atoms) != len(raw) or not atoms:
            dropped += 1
            continue
        atoms.extend(lexeme)
        features = {atom.split("=", 1)[0] for atom in atoms}
        atoms.extend(
            atom for atom in defaults if atom.split("=", 1)[0] not in features
        )
        if not required <= features | {atom.split("=", 1)[0] for atom in atoms}:
            dropped += 1
            continue
        table.setdefault("|".join(sorted(set(atoms))), set()).add(text)
    return table, dropped


# ── the projected match ──────────────────────────────────────────────────────


def _flag_sets(names: Sequence[str]) -> list[tuple[str, ...]]:
    """Flag combinations to try, fewest first — the simplest answer wins."""
    out: list[tuple[str, ...]] = [()]
    for size in range(1, MAX_FLAGS + 1):
        out.extend(combinations(names, size))
    return out


def _normalise(feats: str, ignore: frozenset[str]) -> frozenset[str]:
    return frozenset(atom for atom in feats.split("|") if atom and atom not in ignore)


def _index(
    table: Mapping[str, set[str]], ignore: frozenset[str]
) -> dict[frozenset[str], set[str]]:
    indexed: dict[frozenset[str], set[str]] = {}
    for feats, forms in table.items():
        indexed.setdefault(_normalise(feats, ignore), set()).update(forms)
    return indexed


def covers(
    table: Mapping[str, set[str]],
    produced: Iterable[tuple[str, str]],
    *,
    ignore: frozenset[str] = frozenset(),
    absent: Sequence[frozenset[str]] = (),
) -> bool:
    """Does ``table`` agree with every cell of ``produced``?

    Per-cell *set* comparison, because a Czech paradigm cell legitimately holds
    two forms and a source that lists one of them is not describing the same
    paradigm.

    ⚑ **The two sides split the paradigm at different depths, in both
    directions**, and neither is wrong. Wiktionary collapses the oblique plural
    of an adjective into one genderless cell (*nových* for all three) where the
    engine writes three; the engine writes one masculine singular l-participle
    where Wiktionary writes an animate and an inanimate one (*jel* twice). So a
    cell matches against every source cell it is COMPARABLE with — one atom set
    containing the other — and all of them must agree. That is a real check
    rather than a relaxation: if the engine produced different forms per gender
    where the source has one, every one of them is compared against it and the
    disagreement is caught.
    """
    generated: dict[frozenset[str], set[str]] = {}
    for form, feats in produced:
        generated.setdefault(_normalise(feats, ignore), set()).add(form)
    if not generated:
        return False

    indexed = _index(table, ignore)
    for atoms, forms in generated.items():
        exact = indexed.get(atoms)
        if exact is not None:
            # Subset, not equality: a source cell may list a colloquial or
            # archaic variant beside the neutral form (*novýma* beside
            # *novými*, *dělati* beside *dělat*) with no tag to reject it by.
            # The engine generates the query-relevant subset (GI-1), so a form
            # it does not make is a form we chose not to model — not evidence
            # that the pattern is wrong.
            if not forms <= exact:
                return False
            continue
        # No cell at this depth — compare against every comparable one.
        matched = False
        for other, source_forms in indexed.items():
            if atoms <= other or other <= atoms:
                if not forms <= source_forms:
                    return False
                matched = True
        if not matched and not any(optional <= atoms for optional in absent):
            return False
    return True


def classify_projected(
    lemma: str,
    upos: str,
    table: Mapping[str, set[str]],
    *,
    lang: str = "cs",
) -> tuple[str, tuple[str, ...]] | None:
    """The (vzor, flags) whose generated cells the source table reproduces.

    Deterministic: patterns in file order, flag sets smallest first — so the
    same table always imports as the same entry, and a re-run diffs cleanly.
    """
    tables = load(lang)
    tag_tables = load_tags()
    tables_ignore = tag_tables.compare_ignore
    optional = [frozenset(group) for group in tag_tables.absent.get(upos, ())]
    flag_sets = _flag_sets(tables.flag_order)
    for name, vzor in tables.vzory.items():
        if vzor.upos != upos:
            continue
        for flags in flag_sets:
            try:
                produced = generate(lemma, name, flags, lang=lang)
            except EngineError:
                # `BadLemma` for the empty flag set does NOT mean this pattern
                # is out: an `invariant` flag never builds a stem, so an
                # indeclinable borrowing fails the strip and still belongs to
                # the pattern. (`engine.classify` had exactly this bug at
                # p8-1 and the fix is the same one — do not break early.)
                continue
            if covers(table, produced, ignore=tables_ignore, absent=optional):
                return name, tuple(tables.order_flags(flags))
    return None


# ── the import ───────────────────────────────────────────────────────────────


def do_import(
    path: Path,
    targets: Iterable[str],
    *,
    exclude: Iterable[tuple[str, str]] = (),
    lang: str = "cs",
    sample_limit: int = 25,
) -> tuple[list[dict], KaikkiReport]:
    """Import the target lemmas from a kaikki extract.

    Returns entry dicts ready for `emit.render_layer`, plus the report.
    """
    from ttrmorph.importers.emit import forms_entry, vzor_entry

    tags = load_tags()
    wanted = set(targets)
    taken = set(exclude)
    report = KaikkiReport()
    found: dict[tuple[str, str], dict] = {}
    best: dict[tuple[str, str], dict[str, set[str]]] = {}
    seen: set[tuple[str, str]] = set()

    for entry in entries(path):
        report.entries_read += 1
        word = entry.get("word") or ""
        if word not in wanted:
            continue
        upos = tags.pos.get(entry.get("pos") or "")
        if upos is None:
            report.skipped_pos += 1
            continue
        identity = (word, upos)
        if identity in taken:
            continue

        if identity not in seen:
            seen.add(identity)
            report.entries_in_target += 1

        table, dropped = table_of(entry, tags, upos=upos)
        # ⚑ One word and one part of speech can appear on several lines — the
        # extract splits by etymology, and only one of them usually carries the
        # inflection table. Taking the FIRST is how `velký` arrived as a
        # full-form entry while its own table sat two lines further down. Keep
        # the richest table instead.
        if len(table) <= len(best.get(identity, ())):
            continue
        best[identity] = table
        report.dropped_forms += dropped
        if len(table) < 2:
            continue

        answer = classify_projected(word, upos, table, lang=lang)
        if answer is not None:
            vzor, flags = answer
            found[identity] = vzor_entry(word, upos, vzor, flags, "wiktionary")
        else:
            found[identity] = forms_entry(
                word,
                upos,
                [(form, feats) for feats, forms in table.items() for form in forms],
                "wiktionary",
            )

    for identity, entry in found.items():
        if entry.get("vzor"):
            report.classified += 1
        else:
            report.full_form += 1
            if len(report.samples) < sample_limit:
                report.samples.append((identity[0], identity[1], ""))
    report.no_table = report.entries_in_target - len(found)

    return [found[key] for key in sorted(found)], report


def read_targets(path: Path) -> list[str]:
    """The authored target list (`seed/data/cs/target-words.yaml`).

    The file is ``groups: {name: [lemma, …]}`` with a language header. Walking
    the whole document for lists rather than reading ``groups`` alone was the
    first cut, and it silently returned ZERO lemmas — the lists are one level
    deeper than the walk went, so the entire import ran on frequency targets
    and the hero vocabulary was never asked for. Read the key the file has.
    """
    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return [str(item) for item in raw or ()]

    groups = raw.get("groups")
    if not isinstance(groups, dict):
        raise ValueError(
            f"{path}: expected a 'groups' mapping of name -> [lemma, …]; "
            f"found keys {sorted(raw)}"
        )
    words: list[str] = []
    for name, members in groups.items():
        if not isinstance(members, list):
            raise ValueError(f"{path}: group {name!r} is not a list")
        words.extend(str(item) for item in members)
    if not words:
        raise ValueError(f"{path}: the target list is empty")
    return words


def targets_from_frequencies(path: Path, top: int) -> list[str]:
    """The most frequent ``top`` lemmas from a ``lemma<TAB>count`` table.

    This is what makes the target list a *domain* rather than a wish: the
    authored list names the vocabulary the heroes need, and the frequency table
    supplies the words any Czech sentence around them is made of. Both are
    lists of lemmas; neither is training data (plan §8).
    """
    lemmas: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        lemma, _, _ = line.partition("\t")
        if lemma.strip():
            lemmas.append(lemma.strip())
        if len(lemmas) >= top:
            break
    return lemmas


__all__ = [
    "SOURCE",
    "entry_atoms",
    "KaikkiReport",
    "Tags",
    "classify_projected",
    "covers",
    "do_import",
    "entries",
    "load_tags",
    "read_targets",
    "table_of",
    "targets_from_frequencies",
]
