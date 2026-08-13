# SPDX-License-Identifier: Apache-2.0
"""The four metrics of contracts §11, and what each denominator means.

    coverage              tokens the lexicon answered / tokens seen
    lemma-in-set accuracy gold lemma among the returned analyses / answered
    head-of-list accuracy gold lemma == the FIRST analysis / answered
    fold-collision rate   answers reached through the fold index that carry
                          more than one lemma / answered

**The denominators are the whole argument, so they are stated rather than
chosen quietly.** Coverage is over *every* token, because the honest question
is "how much of running Czech does this artifact know" and the answer in v1 is
"the query domain, not the language" (FI-1/GI-1). The two accuracies are over
*answered* tokens, because they measure the analyses — a token with no entry
has no head of list to be wrong about, and folding an uncovered token into the
accuracy denominator would make the artifact look worse the more honest it was
about its own scope. Both are printed with the all-token denominator beside
them so nobody has to take that on trust.

**The reported coverage gap IS the Wave C headroom.** v1 runs with the
statistical seam unwired (`ttrnlp.morph.chain`, architecture §2): the seam
exists, defaults to ``None``, and nothing passes one. So every uncovered token
here is a token the Wave C lemmatizer would be asked to answer, and this number
is the brief for that work rather than a defect in this one.

## The UD lemma convention (the two questions NLS-P8.3 left open)

They turned out to be one question, and the shape of it was measured on the
TRAIN side rather than assumed: **CAC collapses number (and gender) in the lemma
of a personal pronoun or a possessive.** The gold column contains ``já`` and
``ty`` and ``on`` and never ``my``, ``vy`` or ``oni``; ``můj`` and ``tvůj`` and
``jeho`` and never ``náš``, ``váš`` or ``jejich``. Person survives, number does
not.

That is narrower than the reading NLS-P8.3 recorded ("every personal pronoun to
``já``, every possessive to ``jeho``"), and the difference matters: a scorer
built on the loose reading would accept *tebe* as an answer to a gold ``já``,
which is not a convention, it is a metric with its accuracy filed off.

Our seed keeps the citation form (*my*, *vy*, *oni*, *náš*), because the `lemma`
feature is what a rule pack matches on (`lemma_any("my")`) and an analyst
writing a rule for "we" would never write ``já``. Adopting the treebank
convention would make the lemma of a pronoun useless to the one consumer it has.

So neither side changes and the **scorer** carries the difference, on the gold
side only, as a declared and counted equivalence — `UD_CONVENTIONS` below. A run
prints how many tokens it scored that way, because an equivalence nobody can see
is indistinguishable from a metric that has been quietly loosened.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field

from ttrnlp.morph import MATCHED_FOLDED, PROVENANCE_LEXICON, Analysis, MorphState

#: The convention, pair by pair: gold lemma -> the citation forms we use for the
#: same person. Number and gender collapse; **person does not**, so a gold
#: ``já`` never accepts *ty*. Applied only when the gold upos is in
#: `CONVENTION_UPOS`, so a noun lemmatized ``já`` — there is no such thing —
#: could not be waved through by a table lookup.
UD_CONVENTIONS: Mapping[str, frozenset[str]] = {
    "já": frozenset({"já", "my"}),
    "ty": frozenset({"ty", "vy"}),
    "on": frozenset({"on", "ona", "ono", "oni", "ony"}),
    "můj": frozenset({"můj", "náš"}),
    "tvůj": frozenset({"tvůj", "váš"}),
    "jeho": frozenset({"jeho", "její", "jejich"}),
}

CONVENTION_UPOS = frozenset({"PRON", "DET"})


@dataclass(frozen=True)
class GoldToken:
    """One pretokenized token with its gold analysis (LM-9: CAC gives tokens)."""

    form: str
    lemma: str
    upos: str
    count: int = 1


@dataclass
class Counts:
    """The raw tallies. Every rate in the report is a division of two of these."""

    tokens: int = 0
    answered: int = 0
    in_set: int = 0
    head: int = 0
    folded: int = 0
    fold_ambiguous: int = 0
    #: Answered by a declared UD convention rather than by an equal lemma.
    by_convention: int = 0

    @property
    def coverage(self) -> float:
        return _ratio(self.answered, self.tokens)

    @property
    def lemma_in_set(self) -> float:
        return _ratio(self.in_set, self.answered)

    @property
    def head_of_list(self) -> float:
        return _ratio(self.head, self.answered)

    @property
    def fold_collision_rate(self) -> float:
        return _ratio(self.fold_ambiguous, self.answered)

    #: The same two accuracies over every token, uncovered included. Not the
    #: headline (see the module note) and always printed next to it.
    @property
    def lemma_in_set_all(self) -> float:
        return _ratio(self.in_set, self.tokens)

    @property
    def head_of_list_all(self) -> float:
        return _ratio(self.head, self.tokens)


@dataclass
class Metrics:
    """A whole run: the totals, the per-upos breakdown, and worked examples."""

    total: Counts = field(default_factory=Counts)
    per_upos: dict[str, Counts] = field(default_factory=dict)
    #: (form, gold lemma, our head lemma or None) for the commonest misses.
    head_misses: list[tuple[str, str, str | None, int]] = field(default_factory=list)
    uncovered: list[tuple[str, str, int]] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "tokens": self.total.tokens,
            "answered": self.total.answered,
            "coverage": round(self.total.coverage, 4),
            "lemma_in_set": round(self.total.lemma_in_set, 4),
            "head_of_list": round(self.total.head_of_list, 4),
            "fold_collision_rate": round(self.total.fold_collision_rate, 4),
            "by_convention": self.total.by_convention,
            "per_upos": {
                upos: {
                    "tokens": counts.tokens,
                    "coverage": round(counts.coverage, 4),
                    "lemma_in_set": round(counts.lemma_in_set, 4),
                    "head_of_list": round(counts.head_of_list, 4),
                }
                for upos, counts in sorted(self.per_upos.items())
            },
        }


def _ratio(part: int, whole: int) -> float:
    return part / whole if whole else 0.0


def accepted_lemmas(gold: GoldToken) -> frozenset[str]:
    """What counts as the gold lemma for this token — see the module note."""
    if gold.upos in CONVENTION_UPOS and gold.lemma in UD_CONVENTIONS:
        return UD_CONVENTIONS[gold.lemma]
    return frozenset({gold.lemma})


def lexicon_analyses(analyses: Sequence[Analysis]) -> tuple[Analysis, ...]:
    """The curated answers only.

    A world overlay may answer with ``provisional`` rows (Q-7) and the chain's
    guess always answers something. Neither is the lexicon, and counting either
    as coverage would report the artifact's scope as larger than it is — which
    is precisely the number Wave C is sized against.
    """
    return tuple(a for a in analyses if a.provenance == PROVENANCE_LEXICON)


def score(
    tokens: Iterable[GoldToken],
    state: MorphState,
    *,
    samples: int = 30,
) -> Metrics:
    """Score pretokenized gold tokens against a loaded snapshot.

    Tokens are weighted by ``count`` — the harness passes a counted table
    rather than a stream, so a run is reproducible from the counter and a
    frequent word carries the weight it carries in the corpus.
    """
    metrics = Metrics()
    miss_totals: dict[tuple[str, str, str | None], int] = {}
    uncovered_totals: dict[tuple[str, str], int] = {}

    for gold in tokens:
        bucket = metrics.per_upos.setdefault(gold.upos, Counts())
        weight = gold.count
        metrics.total.tokens += weight
        bucket.tokens += weight

        result = state.lookup(gold.form)
        analyses = lexicon_analyses(result.analyses) if result else ()
        if not analyses:
            key = (gold.form, gold.lemma)
            uncovered_totals[key] = uncovered_totals.get(key, 0) + weight
            continue

        metrics.total.answered += weight
        bucket.answered += weight

        if result is not None and result.matched_via == MATCHED_FOLDED:
            metrics.total.folded += weight
            bucket.folded += weight
            if len({a.lemma for a in analyses}) > 1:
                metrics.total.fold_ambiguous += weight
                bucket.fold_ambiguous += weight

        accepted = accepted_lemmas(gold)
        ours = [a.lemma for a in analyses]
        if set(ours) & accepted:
            metrics.total.in_set += weight
            bucket.in_set += weight
        if ours[0] in accepted:
            metrics.total.head += weight
            bucket.head += weight
            if ours[0] != gold.lemma:
                metrics.total.by_convention += weight
                bucket.by_convention += weight
        else:
            key = (gold.form, gold.lemma, ours[0])
            miss_totals[key] = miss_totals.get(key, 0) + weight

    metrics.head_misses = [
        (form, gold_lemma, ours, count)
        for (form, gold_lemma, ours), count in sorted(
            miss_totals.items(), key=lambda pair: (-pair[1], pair[0])
        )[:samples]
    ]
    metrics.uncovered = [
        (form, lemma, count)
        for (form, lemma), count in sorted(
            uncovered_totals.items(), key=lambda pair: (-pair[1], pair[0])
        )[:samples]
    ]
    return metrics


__all__ = [
    "CONVENTION_UPOS",
    "UD_CONVENTIONS",
    "Counts",
    "GoldToken",
    "Metrics",
    "accepted_lemmas",
    "lexicon_analyses",
    "score",
]
