# SPDX-License-Identifier: Apache-2.0
"""The deterministic pattern-guesser (LM-14, contracts §8; NLS-P9.2 T4).

A miss arrives as one surface form and nothing else — *Kauflandu*, no sentence,
no lemma, no part of speech (queues are token-only by default, contracts §6).
This module turns that into ranked ``(lemma, upos, vzor, flags)`` proposals, and
it does so with no model, no corpus and no network: the vzor tables already
describe every shape Czech nouns and adjectives take, and the B-O5 sub-vzor
inventory already carries, as data, the surface priors an analyst would use.

**The engine is the proof, the hints are only the prior.** Every proposal is
generated and checked before it is returned: ``generate(lemma, vzor, flags)``
must actually produce the observed form. A pattern that cannot make the word
somebody typed is not a hypothesis about that word, whatever its hints say. The
hints — ``capitalized`` and ``lemma_pattern`` on each sub-vzor — then decide the
*order*, because the generate check alone is far too generous: the citation cell
of ``hrad`` is the bare stem, so for any letter string at all there is a
"pattern that produces it", namely the one that treats it as its own nominative.

**Confidence is a rank, not a probability.** It is assembled from five visible
signals (below), it is comparable between proposals for the same token, and it
is not calibrated against anything. What it is *for* is one decision: whether a
proposal may auto-validate without a human (`AUTO_VALIDATE_CONFIDENCE`), and
that threshold is set so that no proposal clears it without a matched
`lemma_pattern` — the one signal that is about the *pattern*, which is what
auto-validation commits to. Everything weaker falls to the LLM leg or to a
person, which is the cascade working, not the guesser failing.

**Base patterns are proposed too, and never auto-validate.** Restricting the
guesser to the hint-carrying sub-vzory would have made it useless for the core
analytical queue, which is mostly ordinary common nouns whose base pattern has
no hints to carry. So they are proposed — and with no hints to match they can
collect only the hint-independent signals, whose sum is below
`AUTO_VALIDATE_CONFIDENCE` by construction. A hintless pattern can be ranked;
it can never be acted on unasked.

**What is NOT a word gets nothing.** A token with a digit, punctuation or a
single letter returns no proposals at all: the paradigm engine would happily
inflect it, and an editorial queue full of `2026` and `%` teaches reviewers to
skim. A well-formed nonsense string — `krupel` — *does* get a low-confidence
proposal, and that is correct: this is a proposer, not a Czech-word detector,
and deciding that a well-shaped word is not a word is the one judgement it has
no way to make.
"""

from __future__ import annotations

import re
from collections.abc import Iterator, Mapping, Sequence
from dataclasses import dataclass
from functools import cache

from ttrmorph.engine.errors import EngineError
from ttrmorph.engine.paradigm import generate
from ttrmorph.engine.tables import Tables, Vzor, load

#: `proposal.source` for everything this module produces (contracts §7's
#: `proposal` jsonb: `source: guesser|inflector|llm`).
SOURCE_GUESSER = "guesser"

#: A hint key on a sub-vzor (`seed/data/cs/vzory.yaml`, the `subvzory` block).
HINT_CAPITALIZED = "capitalized"
HINT_LEMMA_PATTERN = "lemma_pattern"

# ── the score ────────────────────────────────────────────────────────────────
#
# Five signals, each worth stating out loud rather than tuning silently. The
# numbers are chosen so that nothing clears the auto-validate line without a
# matched `lemma_pattern` — the *Kaufland* / *Nováková* / *zákazník* shape, the
# one the enrichment loop was designed around — and everything weaker meets a
# human or the LLM.

#: Every surviving proposal starts here: the engine generated the observed form.
BASE = 0.45
#: The sub-vzor's `lemma_pattern` matches the lemma we derived.
LEMMA_PATTERN_MATCH = 0.20
#: ...and does not. Without this, a pattern could be handed the whole token as
#: its own citation form and collect the `CITATION_FORM` bonus while its own
#: hint said citation forms of this pattern do not look like that — which is how
#: *atašé* came out as a consonant-stem masculine ahead of the indeclinable.
LEMMA_PATTERN_MISMATCH = -0.20
#: The sub-vzor's `capitalized` hint agrees with the observed token.
CAPITALIZATION_MATCH = 0.15
#: ...and disagrees. A penalty rather than a veto: a sentence-initial common
#: noun is capitalized and still a common noun, so this has to be survivable —
#: but it must also be enough to put `hrad-u` below `hrad-proper` for a
#: capitalized token, which is the single most common routing decision (LM-10).
CAPITALIZATION_MISMATCH = -0.25
#: The observed token IS the proposed citation form. Less inference happened, so
#: there is less to be wrong about.
CITATION_FORM = 0.15
#: The observed token is the candidate lemma plus an ending — nothing was
#: rewritten and nothing was invented. Small, and it is what settles the
#: flagship tie: *Kauflandu* fits `hrad-proper` as *Kaufland* + `-u` and
#: `predseda-name` as *Kauflanda* with the `-a` swapped out, both at the same
#: hint score, and a coin-flip on declaration order is not an answer to give a
#: reviewer about the shape the whole enrichment loop was designed around.
LEMMA_IS_A_PREFIX = 0.05
#: Per character by which the candidate lemma's own tail is LONGER than the
#: ending it replaced — material that was invented rather than observed.
#:
#: ⚑ This is the `hrad-foreign` defect (vzory.yaml) in the guesser's own shape.
#: A candidate lemma is built by stripping an ending the pattern can produce and
#: putting that pattern's citation ending back, so a pattern whose citation
#: ending is LONGER than the ending it stripped invents letters — and then its
#: own `lemma_pattern` hint matches the letters it just invented and pays it for
#: them. That is how *tržbami* came out as `muzeum-um` with the lemma
#: *tržbamum*, above the correct *tržba*, at a confidence that auto-validates.
#:
#: It is measured on the TAILS and not on total length, because substituting one
#: ending for another is the ordinary case and must cost nothing: an adjective's
#: citation form is never a prefix of its oblique forms (*loňský* / *loňském*),
#: and a rule that charged for the whole non-shared tail would drop every
#: adjective the guesser has. What it charges for is a tail that grew.
INVENTION_PENALTY = -0.20
#: ...and the opposite of that bonus: the token ends in something this language
#: uses as a multi-character inflectional ending, so reading it as a citation
#: form is the *weaker* hypothesis rather than the stronger one.
#:
#: ⚑ This is the guesser's only defence against its worst failure. *loňském* and
#: *fakturám* are a lowercase adjective and a lowercase feminine dative plural,
#: and both are perfectly good masculine nominatives as far as surface shape
#: goes — `hrad-u`'s hints (lowercase, consonant-final) match them exactly, and
#: with the citation bonus they auto-validated as masculine nouns whose lemma is
#: the inflected form. The tables already know that *-ém* and *-ám* are endings,
#: and that is the evidence: a word ending in one is far more often inflected
#: than freshly nominative. It leaves *krupel*, *Kaufland* and *atašé* — which
#: end in nothing the language inflects with — where they were.
INFLECTED_TAIL_PENALTY = -0.15
#: The paradigm collapses to ONE surface form — an indeclinable, an acronym.
#: Such a proposal explains any single observation perfectly and says nothing:
#: from one form there is no evidence at all that distinguishes "this word never
#: inflects" from "I have only seen its nominative". Enough of a penalty to keep
#: an indeclinable reading below a real pattern that also fits (*zákazníkovi* is
#: `pan-velar`, not an indeclinable whose only form ends in -i), and to keep the
#: genuine indeclinables — *atašé*, where nothing else fits — proposed but under
#: the auto-validate line, where a person or the model settles it.
INVARIANT_PENALTY = -0.30

#: Below this a proposal is not worth showing, and it is `BASE` on purpose: a
#: proposal must be at least as good as knowing nothing beyond "the engine can
#: make this form". Any *negative* evidence — a hint the pattern itself
#: contradicts — therefore drops it, which is what keeps `hrad-u` out of a
#: capitalized token's list rather than merely below `hrad-proper`.
MIN_CONFIDENCE = BASE

#: A single-source proposal at or above this may auto-validate (LM-14). Two
#: sources agreeing auto-validate at any confidence — see `cascade.run_cascade`,
#: where that rule lives, because it is about the cascade and not about this leg.
#:
#: The line sits exactly where `BASE + LEMMA_PATTERN_MATCH + CAPITALIZATION_MATCH`
#: lands, and that is the point: `lemma_pattern` is the only signal that is
#: about the *pattern*, which is what auto-validation commits to. Without it,
#: "the token is lowercase and looks like a citation form" — true of most of the
#: queue — cleared the line on its own, against four patterns at once.
AUTO_VALIDATE_CONFIDENCE = 0.80

#: A word, for the purpose of "is this worth proposing anything for": letters,
#: optionally joined by one hyphen or apostrophe. `[^\W\d_]` is "alphanumeric
#: minus digits minus underscore" — i.e. a letter in any script, which is what
#: this needs and what `\w` is not.
_WORD = re.compile(r"^[^\W\d_]+(?:[-'’][^\W\d_]+)*$")

#: One letter is never enough to guess from, and every single-letter token in a
#: query is an initial, a variable or a typo.
MIN_TOKEN_LENGTH = 2

#: How many proposals a caller gets by default. The studio shows a reviewer a
#: shortlist; a hundred ranked hypotheses for one word is the same as none.
DEFAULT_LIMIT = 5


@dataclass(frozen=True)
class Proposal:
    """One hypothesis about a token: contracts §7's `proposal` jsonb, typed.

    Frozen and comparable, because the cascade compares proposals from
    different legs for agreement and "agreement" has to mean equality of the
    thing that would be written to a layer file — the lemma, the part of
    speech, the pattern and the flags — and not of the confidence that happened
    to come with it.
    """

    lemma: str
    upos: str
    vzor: str
    flags: tuple[str, ...] = ()
    confidence: float = 0.0
    source: str = SOURCE_GUESSER

    @property
    def key(self) -> tuple[str, str, str, tuple[str, ...]]:
        """What two legs must share to be said to agree."""
        return (self.lemma, self.upos, self.vzor, self.flags)

    def as_dict(self) -> dict[str, object]:
        """The jsonb shape. Key order is the reading order of contracts §7."""
        return {
            "lemma": self.lemma,
            "upos": self.upos,
            "vzor": self.vzor,
            "flags": list(self.flags),
            "confidence": round(self.confidence, 4),
            "source": self.source,
        }

    @classmethod
    def from_dict(cls, data: Mapping[str, object]) -> Proposal:
        return cls(
            lemma=str(data["lemma"]),
            upos=str(data.get("upos") or ""),
            vzor=str(data.get("vzor") or ""),
            flags=tuple(str(f) for f in (data.get("flags") or ())),
            confidence=float(data.get("confidence") or 0.0),
            source=str(data.get("source") or SOURCE_GUESSER),
        )


def guess(
    token: str,
    *,
    lang: str = "cs",
    limit: int = DEFAULT_LIMIT,
) -> list[Proposal]:
    """Ranked proposals for one observed surface form, best first.

    Args:
        token: The form as it was observed — inflected, capitalized as written.
        lang: Which vzor tables to guess against.
        limit: How many proposals to return.

    Returns:
        Proposals whose paradigm demonstrably contains ``token``, ranked. Empty
        for anything that is not a word (see the module docstring) — never a
        proposal nobody should be shown.
    """
    if not _is_word(token):
        return []

    tables = load(lang)
    order = {name: index for index, name in enumerate(tables.vzory)}
    best: dict[tuple[str, str, tuple[str, ...]], Proposal] = {}

    for name, pattern in tables.vzory.items():
        for lemma, flags, shapes in _hypotheses(tables, pattern, token, lang=lang):
            score = _score(pattern, token, lemma, shapes, lang=lang)
            if score < MIN_CONFIDENCE:
                continue
            key = (lemma, name, flags)
            current = best.get(key)
            if current is None or score > current.confidence:
                best[key] = Proposal(
                    lemma=lemma,
                    upos=pattern.upos,
                    vzor=name,
                    flags=flags,
                    confidence=score,
                    source=SOURCE_GUESSER,
                )

    ranked = sorted(
        best.values(),
        key=lambda p: (-p.confidence, order[p.vzor], p.lemma),
    )
    return ranked[:limit]


def validates(proposal: Proposal, token: str, *, lang: str = "cs") -> bool:
    """Does this proposal's paradigm actually contain ``token``? (LM-14.)

    The auto-validate rule, as a function. It is re-run by the *service* on the
    stored proposal rather than trusted from whoever produced it: the LLM leg
    and a human both write proposals, and "the engine generated this form" is
    the one claim in the loop that must never be taken on anybody's word.
    """
    try:
        forms = generate(proposal.lemma, proposal.vzor, proposal.flags, lang=lang)
    except EngineError:
        return False
    return any(form == token for form, _ in forms)


def paradigm(proposal: Proposal, *, lang: str = "cs") -> list[tuple[str, str]]:
    """Every ``(form, feats)`` this proposal generates, in a stable order.

    What `try-pattern` shows a reviewer (contracts §7): the generated table, to
    be corrected cell by cell. Sorted so two views of one entry are the same
    view — `generate` returns a set, and a table that reordered itself between
    two page loads would be unreviewable.
    """
    try:
        forms = generate(proposal.lemma, proposal.vzor, proposal.flags, lang=lang)
    except EngineError:
        return []
    return sorted(forms)


# ── internals ────────────────────────────────────────────────────────────────


def _is_word(token: str) -> bool:
    return len(token) >= MIN_TOKEN_LENGTH and bool(_WORD.match(token))


def _hypotheses(
    tables: Tables, pattern: Vzor, token: str, *, lang: str
) -> Iterator[tuple[str, tuple[str, ...], int]]:
    """``(lemma, flags, distinct forms)`` under this pattern that make ``token``.

    The form count travels with the hypothesis because it is scoring evidence
    and recomputing it would mean generating the same paradigm twice.

    The flag search is deliberately shallow — the empty set, then one flag at a
    time. Sub-vzory carry their alternations as `implied_flags` already, so the
    open question is only ever a lexical one (`pes` needs `fleeting-e`), and
    those come singly. An exhaustive subset search here would multiply the work
    by the size of the inventory to find combinations that no Czech lexeme in
    the queue has needed.
    """
    singles: Sequence[tuple[str, ...]] = [
        (name,) for name in tables.flag_order if name not in pattern.implied_flags
    ]
    for lemma in _candidate_lemmas(pattern, token):
        for flags in ((), *singles):
            try:
                forms = generate(lemma, pattern.name, flags, lang=lang)
            except EngineError:
                continue
            if any(form == token for form, _ in forms):
                yield lemma, flags, len({form for form, _ in forms})
                # The simplest explanation wins: once the empty set works, a
                # flag that also happens to work is noise in a reviewer's list.
                if not flags:
                    break


def _candidate_lemmas(pattern: Vzor, token: str) -> list[str]:
    """What the citation form could be, if this pattern is the right one.

    The inverse of `_stem` plus the pattern's own citation ending: strip an
    ending the pattern can produce, put the citation ending back. It is a
    *guess* at the inverse — the spelling rules run after the join and are not
    inverted here — which is exactly why every candidate is then generated and
    checked. The token itself is always a candidate: most misses in an
    entity queue arrive in the nominative.
    """
    citation = pattern.strip[0]
    out = {token}
    for slot in pattern.slots:
        for ending in slot.endings:
            if not ending:
                out.add(token + citation)
            elif token.endswith(ending) and len(token) > len(ending):
                out.add(token[: -len(ending)] + citation)
    return sorted(out)


def _invented(lemma: str, token: str) -> int:
    """By how much the lemma's tail outgrew the ending it replaced."""
    shared = 0
    for a, b in zip(lemma, token, strict=False):
        if a != b:
            break
        shared += 1
    return max(0, (len(lemma) - shared) - (len(token) - shared))


@cache
def _inflectional_endings(lang: str) -> frozenset[str]:
    """Every multi-character ending the language's patterns produce.

    Single characters are left out on purpose: -a, -y, -u and -e are endings
    and are also how a great many citation forms end, so they separate nothing.
    """
    tables = load(lang)
    return frozenset(
        ending
        for vzor in tables.vzory.values()
        for slot in vzor.slots
        for ending in slot.endings
        if len(ending) >= 2
    )


def _ends_inflected(token: str, lang: str) -> bool:
    return any(token.endswith(ending) for ending in _inflectional_endings(lang))


def _score(
    pattern: Vzor, token: str, lemma: str, shapes: int = 0, *, lang: str = "cs"
) -> float:
    score = BASE
    hints = pattern.hints

    if shapes == 1:
        score += INVARIANT_PENALTY

    expected_capital = hints.get(HINT_CAPITALIZED)
    if expected_capital is not None:
        observed = token[:1].isupper()
        score += (
            CAPITALIZATION_MATCH
            if bool(expected_capital) == observed
            else CAPITALIZATION_MISMATCH
        )

    lemma_pattern = hints.get(HINT_LEMMA_PATTERN)
    if lemma_pattern:
        score += (
            LEMMA_PATTERN_MATCH
            if re.search(str(lemma_pattern), lemma)
            else LEMMA_PATTERN_MISMATCH
        )

    if lemma == token:
        score += (
            INFLECTED_TAIL_PENALTY if _ends_inflected(token, lang) else CITATION_FORM
        )
    elif token.startswith(lemma):
        score += LEMMA_IS_A_PREFIX

    score += INVENTION_PENALTY * _invented(lemma, token)

    return round(min(score, 0.99), 4)


__all__ = [
    "AUTO_VALIDATE_CONFIDENCE",
    "BASE",
    "DEFAULT_LIMIT",
    "MIN_CONFIDENCE",
    "SOURCE_GUESSER",
    "Proposal",
    "guess",
    "paradigm",
    "validates",
]
