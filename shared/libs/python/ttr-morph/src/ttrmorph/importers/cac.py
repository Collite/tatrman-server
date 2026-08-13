# SPDX-License-Identifier: Apache-2.0
"""UD_Czech-CAC → attested forms and frequencies (contracts §11, plan §8).

CAC gives this effort two things and they are not the same thing:

*Attested forms.* Words the query domain needs that Wiktionary does not carry,
taken as full-form entries with `cac` provenance. A corpus row is evidence that
a form exists, not a paradigm — so nothing here classifies, and nothing here
guesses a vzor.

*Frequencies.* A ``lemma<TAB>count`` table the compiler turns into ranks. Plan
§8 draws the line this sits on: a frequency table is **ranking data**, not
training. Nothing is fitted, and the counts do not reach the artifact — the
compiler converts them to an order and drops them (NL-17: the suite ranks, it
does not score).

**Both read the train side only, and this module is where that is enforced.**
`sentences` refuses a sentence id outside the requested side and refuses to run
at all if the frozen manifest does not exist. The check is here rather than in
callers because a caller can be written wrong once per caller. A caller that
wants the test side has to ask for it by name *and* pass ``allow_test=True``,
which is what the eval harness does and what nothing else may do —
`tests/test_test_side_guard.py` asserts that the flag appears in exactly one
module.

**Why a second flag when ``side="test"`` is already explicit.** Because
``side`` is a variable in real code (`--split test` reaches it as a string), and
a string that arrives from an argument parser is not a decision anybody made
about this corpus. The flag cannot arrive that way: it is written in the source
of whichever module reads the test side, so reading it is a diff a reviewer sees.
"""

from __future__ import annotations

import re
from collections import Counter
from collections.abc import Iterable, Iterator, Sequence
from dataclasses import dataclass, field
from pathlib import Path

from ttrmorph.eval.split import SplitError, SplitManifest, load_manifest
from ttrmorph.importers.sources import require_allowed

SOURCE = "ud-czech-cac"

#: UD columns (CoNLL-U). Only the first six are read: this is a lexicon
#: importer, and syntax is somebody else's corpus.
_ID, _FORM, _LEMMA, _UPOS, _XPOS, _FEATS = range(6)

#: Rows that are not lexicon material. Punctuation has no paradigm, and a
#: multiword-token line (``8-9``) is a range, not a token.
SKIP_UPOS = frozenset({"PUNCT", "SYM", "X"})

#: A lemma is a word: letters, plus the hyphen and apostrophe that live inside
#: some of them. CAC anonymises figures as SGML-ish entities (``&camount;``,
#: ``&cyear;``, ``&clabel;``) and they are frequent enough to head the table —
#: 2,030 occurrences, which would make the placeholder the most common noun in
#: the language and rank a real word below it.
_WORD = re.compile(r"^[^\W\d_][\w\-\u2019']*$", re.UNICODE)


def is_word(lemma: str) -> bool:
    return bool(_WORD.match(lemma))


@dataclass(frozen=True)
class Attested:
    """One (form, lemma, upos, feats) actually observed in the train side."""

    form: str
    lemma: str
    upos: str
    feats: str
    count: int = 1


@dataclass
class CacReport:
    """What a run saw — printed, and committed with the importers report."""

    sentences_read: int = 0
    sentences_skipped: int = 0
    tokens: int = 0
    rows: int = 0
    lemmas: int = 0
    #: Target-list lemmas still missing after this run.
    missing: list[str] = field(default_factory=list)


def sentences(
    conllu_files: Sequence[Path],
    *,
    side: str = "train",
    manifest: SplitManifest | None = None,
    allow_test: bool = False,
) -> Iterator[tuple[str, list[list[str]]]]:
    """Yield ``(sent_id, token rows)`` for sentences on ``side`` — and no others.

    Args:
        allow_test: Required to read ``side="test"``. See the module note: the
            eval harness is the only module that may set it, and it sets it
            in its source rather than passing a variable through.

    Raises:
        SplitError: The frozen manifest does not exist (nothing derived from
            CAC may be read before the split is committed — LM-16/S-6), or the
            test side was asked for without the flag.
    """
    frozen = manifest or load_manifest()
    if side == "test" and not allow_test:
        raise SplitError(
            "the TEST side is the eval oracle and reading it anywhere but the "
            "eval harness makes every number it produces a measurement of "
            "memorization (LM-16/S-6, contracts §11). If you are the harness, "
            "pass allow_test=True in your source; if you are seeding a layer, "
            "you want side='train'"
        )
    allowed = frozen.side(side)

    for path in sorted(conllu_files):
        require_allowed(SOURCE, path)
        sent_id = ""
        rows: list[list[str]] = []
        with path.open(encoding="utf-8") as handle:
            for line in handle:
                line = line.rstrip("\n")
                if line.startswith("# sent_id"):
                    _, _, value = line.partition("=")
                    sent_id = value.strip()
                    continue
                if line.startswith("#"):
                    continue
                if not line:
                    if sent_id and sent_id in allowed:
                        yield sent_id, rows
                    sent_id, rows = "", []
                    continue
                cells = line.split("\t")
                index = cells[_ID]
                if len(cells) > _FEATS and "-" not in index and "." not in index:
                    rows.append(cells)
        if sent_id and sent_id in allowed:
            yield sent_id, rows


def lexical_rows(rows: Iterable[Sequence[str]]) -> Iterator[tuple[str, str, str, str]]:
    """``(form, lemma, upos, feats)`` for every row that is lexicon material.

    One definition of that judgement, shared by the seeding importer and the
    eval harness. If they disagreed about which rows count, the harness would be
    scoring the artifact against a corpus with a different shape from the one it
    was seeded from, and the difference would look like accuracy.
    """
    for cells in rows:
        upos = cells[_UPOS]
        if upos in SKIP_UPOS:
            continue
        form, lemma = cells[_FORM], cells[_LEMMA]
        if not form or not lemma or not is_word(lemma):
            continue
        feats = cells[_FEATS] if cells[_FEATS] != "_" else ""
        yield form, lemma, upos, _canonical(feats)


def read(
    conllu_files: Sequence[Path],
    *,
    side: str = "train",
    manifest: SplitManifest | None = None,
    allow_test: bool = False,
) -> tuple[dict[tuple[str, str, str, str], int], CacReport]:
    """Count every attested (form, lemma, upos, feats) on ``side``.

    Returns the raw counter and a report. Nothing is filtered to a target list
    here — the caller decides what it wants, and the same read serves both the
    attested-forms layer and the frequency table.
    """
    report = CacReport()
    counts: Counter[tuple[str, str, str, str]] = Counter()

    for _, rows in sentences(
        conllu_files, side=side, manifest=manifest, allow_test=allow_test
    ):
        report.sentences_read += 1
        report.tokens += len(rows)
        for key in lexical_rows(rows):
            counts[key] += 1

    report.rows = len(counts)
    report.lemmas = len({key[1] for key in counts})
    return dict(counts), report


def _canonical(feats: str) -> str:
    """UD atoms, sorted — the same canonical order `generate` emits.

    CAC writes them sorted already; asserting it here rather than trusting it
    keeps a CAC-derived full-form entry comparable with an engine-derived one,
    which is what lets `classify` be tried against a mixed table at all.
    """
    return "|".join(sorted(atom for atom in feats.split("|") if atom and atom != "_"))


def frequency_table(counts: dict[tuple[str, str, str, str], int]) -> str:
    """``lemma<TAB>count``, most frequent first — the compiler's rank input.

    Summed over forms and over parts of speech. The compiler ranks *lemmas*
    (contracts §1), and a lemma that is frequent as a noun and rare as a verb is
    still a frequent word to somebody typing it.
    """
    totals: Counter[str] = Counter()
    for (_, lemma, _, _), count in counts.items():
        totals[lemma] += count
    lines = [
        f"{lemma}\t{count}"
        for lemma, count in sorted(totals.items(), key=lambda pair: (-pair[1], pair[0]))
    ]
    return "\n".join(lines) + "\n"


def attested_for(
    counts: dict[tuple[str, str, str, str], int],
    lemmas: Sequence[str],
    *,
    min_count: int = 1,
) -> dict[tuple[str, str], list[Attested]]:
    """Group the attested rows for ``lemmas`` by (lemma, upos).

    ``min_count`` exists because a corpus contains typos and OCR: a form seen
    once, in one sentence, is evidence of a token, not of a word. The default
    keeps everything and the importer raises it — the decision belongs to the
    caller who knows whether it is seeding a lexicon or measuring coverage.
    """
    wanted = set(lemmas)
    grouped: dict[tuple[str, str], list[Attested]] = {}
    for (form, lemma, upos, feats), count in counts.items():
        if lemma not in wanted or count < min_count:
            continue
        grouped.setdefault((lemma, upos), []).append(
            Attested(form=form, lemma=lemma, upos=upos, feats=feats, count=count)
        )
    for rows in grouped.values():
        rows.sort(key=lambda row: (row.form, row.feats))
    return grouped


__all__ = [
    "SKIP_UPOS",
    "SOURCE",
    "is_word",
    "lexical_rows",
    "Attested",
    "CacReport",
    "SplitError",
    "attested_for",
    "frequency_table",
    "read",
    "sentences",
]
