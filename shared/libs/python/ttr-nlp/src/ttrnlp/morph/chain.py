# SPDX-License-Identifier: Apache-2.0
"""The LM-6 fallback chain: lexicon -> statistical seam -> fold/stem guess.

Three legs, in strictly decreasing order of authority:

1. **The lexicon.** A curated entry answered. This always wins, and when it
   answers nothing else runs and nothing is reported — that is the whole point
   of owning the vocabulary.
2. **The statistical seam.** A CAC-trained lemmatizer, arriving in Wave C as an
   NLS engine backend. In v1 the parameter exists, defaults to ``None``, and
   **nothing in the wheel passes it** — `annotate_morph` deliberately has no way
   to supply one. A seam with a caller is not a seam, it is a dependency, and
   the point of shipping it unwired is that the shape is proven before the model
   exists.
3. **The fold/stem guess.** Strip a known ending and look the remainder up; if
   that fails, hand back the stem itself as the lemma. Weak, and marked so.

**Every non-lexicon answer is provenance-marked AND reported.** The mark is what
lets a consumer tell a curated answer from a guess; the report is what makes the
lexicon grow. Without the report the enrichment loop has no input at all and the
same word is guessed at wrongly forever — which is the failure mode of every
morphology layer that was never allowed to learn what it did not know.

The guess always answers. A word with no analysis at all would leave the token
with no lemma, and everything downstream (gazetteer lists keyed on lemma, rules
matching on lemma) would simply not see it — a silent miss rather than a marked
one. A marked guess is visible: the annotator writes ``morph_provenance`` and
the queue gets the token.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence

from ttrnlp.morph.diag import LM_MORPH_006, Diagnostic, info
from ttrnlp.morph.profiles import UnknownProfile, get_profile
from ttrnlp.morph.records import (
    PROVENANCE_LEXICON,
    PROVENANCE_STATISTICAL,
    Analysis,
)
from ttrnlp.morph.snapshot import MorphState

#: The verdicts a queue entry can carry (contracts §6). The chain only ever
#: emits ``miss``: ``resolved_wrong`` is a human's judgement, arriving through
#: `ReportToken` from a consumer that saw the wrong answer, and nothing here
#: can know it made one.
VERDICT_MISS = "miss"
VERDICT_RESOLVED_WRONG = "resolved_wrong"

#: What a guessed analysis claims about its part of speech: nothing. UD's "X"
#: is the honest tag — the stem guess knows an ending, not a word class. The
#: editorial cascade (NLS-P9) is where a real upos gets proposed.
GUESS_UPOS = "X"

#: A statistical backend: form -> analyses, or None if it has no opinion.
StatisticalSeam = Callable[[str], Sequence[Analysis] | None]

#: A miss sink: (world, token, verdict).
MissSink = Callable[[str, str, str], None]

#: Below this, a stripped stem is not a word, it is a fragment.
_MIN_STEM = 3


def resolve(
    form: str,
    state: MorphState,
    *,
    statistical: StatisticalSeam | None = None,
    miss_sink: MissSink | None = None,
    world: str = "",
    diagnostics: list[Diagnostic] | None = None,
) -> tuple[Analysis, ...]:
    """Resolve one surface form through the whole chain.

    Args:
        form: The surface form, as written.
        state: The loaded snapshot.
        statistical: The Wave-C seam. **Nothing in the wheel passes this.**
        miss_sink: Called ``(world, form, "miss")`` for any non-lexicon answer.
            ⚑ *Any* — a common noun as much as a name. LM-10 routing (core vs
            the world's entity layer) is decided studio-side when the miss is
            ingested, because it needs a proposal and the world's model
            vocabulary, neither of which exists on the query path. Reaching
            this sink means "unanswered here", not "owned by this world".
        world: The world the miss belongs to. Queues never cross worlds
            (LM-5/S-4), so an empty world with a sink configured is still
            reported — the sink decides what to do with it — rather than
            silently dropped.
        diagnostics: If given, `LM-MORPH-006` is appended when the seam answers.

    Returns:
        Ranked analyses. Never empty for a form the guess can stem, which is
        every form with at least a few characters.
    """
    hit = state.lookup(form)
    if hit is not None and hit.analyses:
        if all(a.provenance == PROVENANCE_LEXICON for a in hit.analyses):
            return hit.analyses
        # A world overlay answered with provisional rows (Q-7). That is a real
        # answer from a real artifact — not a guess — so it stands as it is,
        # and it is still not a lexicon hit, so the token is still reported.
        _report(miss_sink, world, form)
        return hit.analyses

    if statistical is not None:
        answered = statistical(form)
        if answered:
            marked = tuple(_as_statistical(a) for a in answered)
            if diagnostics is not None:
                diagnostics.append(
                    info(
                        LM_MORPH_006,
                        f"the statistical seam answered for {form!r} — "
                        "provenance is on the record",
                    )
                )
            _report(miss_sink, world, form)
            return marked

    guessed = _guess(form, state)
    _report(miss_sink, world, form)
    return guessed


def _report(miss_sink: MissSink | None, world: str, form: str) -> None:
    if miss_sink is not None:
        miss_sink(world, form, VERDICT_MISS)


def _as_statistical(analysis: Analysis) -> Analysis:
    """Re-mark an answer the seam produced. Rank goes flat: a model's own
    ordering is a confidence, and confidences are what this layer does not
    carry (NL-17)."""
    return Analysis(
        lemma=analysis.lemma,
        upos=analysis.upos,
        feats=analysis.feats,
        vzor=analysis.vzor,
        flags=analysis.flags,
        provenance=PROVENANCE_STATISTICAL,
        rank=0,
        parts=analysis.parts,
    )


def _guess(form: str, state: MorphState) -> tuple[Analysis, ...]:
    """Last resort: strip a known ending, then fall back to the stem itself.

    Longest ending first, and only where the remainder is still long enough to
    be a word. Two outcomes:

    * the remainder resolves — *Kauflandu* -> *Kaufland*, if the world layer
      taught us *Kaufland*. Those analyses come back re-marked ``statistical``,
      because the inflection was guessed even though the base was known.
    * nothing resolves — the stem becomes the lemma, tagged ``X``. This is the
      weakest claim the system can make and it is deliberately still a claim:
      *Kauflandu* -> *Kaufland* is the proposal the editorial cascade starts
      from, and it is also what makes the token's `lemma` feature usable by a
      world list that already knows the entity.
    """
    for ending in _suffixes(state):
        if not form.casefold().endswith(ending) or len(form) - len(ending) < _MIN_STEM:
            continue
        stem = form[: len(form) - len(ending)]
        hit = state.lookup(stem)
        if hit is not None and hit.analyses:
            return tuple(_as_statistical(a) for a in hit.analyses)

    stem = form
    for ending in _suffixes(state):
        if form.casefold().endswith(ending) and len(form) - len(ending) >= _MIN_STEM:
            stem = form[: len(form) - len(ending)]
            break

    return (
        Analysis(
            lemma=stem,
            upos=GUESS_UPOS,
            feats=frozenset(),
            provenance=PROVENANCE_STATISTICAL,
            rank=0,
        ),
    )


def _suffixes(state: MorphState) -> tuple[str, ...]:
    """The language's guessable endings, longest first — profile data (LM-2).

    A language with no profile registered gets an empty tuple and therefore no
    stem guessing, which is the right answer: guessing Czech endings off a
    Slovak word is worse than admitting the miss.
    """
    try:
        return get_profile(state.manifest.language).guess_suffixes
    except UnknownProfile:
        return ()


__all__ = [
    "GUESS_UPOS",
    "VERDICT_MISS",
    "VERDICT_RESOLVED_WRONG",
    "MissSink",
    "StatisticalSeam",
    "resolve",
]
