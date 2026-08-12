# SPDX-License-Identifier: Apache-2.0
"""The cascade and the routing decision (LM-14/LM-10, contracts §8; NLS-P9.2 T6).

One function, `run_cascade`, answers the only two questions a queued miss
raises: *what do we think this word is*, and *may we act on that without asking
a person*. It is deliberately a pure function of its legs' answers — the service
around it owns the database, the endpoints and the overlay files, and none of
those change what the right answer is.

**The cascade, per architecture §5.** Deterministic pattern-guesser → *CAC
inflector (Wave C; absent in v1)* → LLM classifier tie-break → human. The
inflector's absence is why `legs` is a list rather than two named parameters:
when Wave C lands, it enters as a second deterministic leg and the agreement
rule below covers it unchanged, because agreement was never defined as
"guesser and LLM" but as "two independent sources naming the same entry".

**Auto-validation needs two things, and one of them is not an opinion.** The
observed form must be in the engine-generated paradigm — LM-14's rule, checked
here rather than trusted from whichever leg proposed it — *and* the proposal
must be either high-confidence from the deterministic leg or corroborated by a
second source. The engine check alone is far too weak to stand on: it is
satisfied by any pattern whose citation cell happens to be the bare stem, which
is most of them (see `guesser`'s module docstring).

**Routing is a licence decision, not a linguistic one** (LM-10). A proper noun
or a model-vocabulary match goes to the *world's* entity layer; everything else
queues for the core analytical lexicon. The asymmetry that matters: a world
layer is the world's own file and can be retracted by the world, while the core
is published to every deployment — so an unclear case belongs on the world side,
where being wrong is reversible.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field

from ttrmorph.enrich.guesser import (
    AUTO_VALIDATE_CONFIDENCE,
    SOURCE_GUESSER,
    Proposal,
    ends_inflected,
    guess,
    validates,
)
from ttrmorph.enrich.llm import LlmError, LlmLeg

#: Where an entry is routed (LM-10). These are the values the service stores in
#: `entry.layer` and the queue item's precomputed route.
LAYER_CORE = "core"
LAYER_WORLD = "world"

#: The two statuses a cascade can reach on its own. The rest of the machine
#: (`verified`, `published`, `rejected`, `shadowed`) needs a human or an export
#: and lives in the service (`morph_studio.status`).
STATUS_PROPOSED = "proposed"
STATUS_AUTO_VALIDATED = "auto-validated"

#: Which leg the outcome is owed to — the queue's "source" column, and what a
#: reviewer sees before they read the proposal itself.
TIER_GUESSER = "guesser"
TIER_INFLECTOR = "inflector"  # Wave C (LM-6). Never produced in v1.
TIER_LLM = "llm"
TIER_HUMAN = "human"

#: The prefix of the note a failed LLM leg leaves. A constant because the bulk
#: batch reads it back (`bootstrap.run`): the cascade does not raise for a leg,
#: so the note IS the failure signal, and a note nobody can match on is a
#: failure nobody can count.
LLM_UNAVAILABLE = "llm leg unavailable:"

#: What a corroborated proposal is worth. Above the auto-validate line by
#: construction: two independent sources naming the same (lemma, upos, vzor,
#: flags) is the strongest signal this loop has short of a person.
AGREEMENT_CONFIDENCE = 0.90


@dataclass(frozen=True)
class CascadeResult:
    """What the cascade concluded about one token."""

    token: str
    #: Ranked, best first. Empty means every leg declined — the token still
    #: queues, with nothing to show, which is a legitimate outcome for a typo.
    proposals: tuple[Proposal, ...] = ()
    status: str = STATUS_PROPOSED
    layer: str = LAYER_CORE
    tier: str = TIER_HUMAN
    #: Two sources named the same entry.
    agreed: bool = False
    #: Anything a reviewer should know that the fields above cannot say — an
    #: LLM leg that was configured and did not answer, most of all. A leg that
    #: failed silently would be indistinguishable from a leg nobody configured.
    notes: tuple[str, ...] = field(default_factory=tuple)

    @property
    def best(self) -> Proposal | None:
        return self.proposals[0] if self.proposals else None


def run_cascade(
    token: str,
    *,
    llm: LlmLeg | None = None,
    vocabulary: Iterable[str] = (),
    context: str = "",
    lang: str = "cs",
) -> CascadeResult:
    """Guesser → (inflector) → LLM → human, for one observed form.

    Args:
        token: The surface form from the queue.
        llm: The classifier leg, or ``None`` for an air-gapped deployment. Its
            absence is not a degradation and produces no note.
        vocabulary: The world's model vocabulary — entity names and terms from
            the model (LM-10). Compared folded, so *Kaufland* matches a
            vocabulary that spells it *KAUFLAND*.
        context: The stored span, when the world's config keeps one.
        lang: Which tables to guess against.

    Returns:
        A `CascadeResult`. Never raises for a leg's failure: a cascade that
        threw would turn one unreachable gateway into an ingest that stops
        accepting misses.
    """
    proposals = list(guess(token, lang=lang))
    notes: list[str] = []

    # ── the deterministic leg alone ─────────────────────────────────────────
    top = proposals[0] if proposals else None
    inflected = ends_inflected(token, lang)
    if inflected:
        # ⚑⚑ Categorical, not a score adjustment (NLS-P9.3 T6). The guesser
        # already charges `INFLECTED_TAIL_PENALTY` for this, and the p9-3
        # bootstrap run over a world glossary showed that charging is not
        # enough: *pololetích* came out as `muzeum-um` with the invented lemma
        # *pololetum*, scoring 0.95 − 0.15 = exactly 0.80 — the auto-validate
        # line, met rather than missed. Tuning the constant so the arithmetic
        # lands elsewhere would fix that word and nothing else.
        #
        # The rule the arithmetic was reaching for: if the engine's own tables
        # say this token ends in one of their endings, then a pattern claiming
        # it is a citation form has not earned an unreviewed pass. It still gets
        # proposed and still gets ranked; it goes to a person or to the LLM leg.
        # Single-character endings are excluded from that set on purpose, so
        # *Kauflandu* — the hero of detailed-design §9 — is untouched.
        notes.append(
            f"{token!r} ends in an inflectional ending: the deterministic leg "
            "may propose but not auto-validate"
        )
    if (
        top is not None
        and not inflected
        and top.confidence >= AUTO_VALIDATE_CONFIDENCE
        and validates(top, token, lang=lang)
    ):
        return CascadeResult(
            token=token,
            proposals=tuple(proposals),
            status=STATUS_AUTO_VALIDATED,
            layer=route(token, top, vocabulary=vocabulary),
            tier=TIER_GUESSER,
            notes=tuple(notes),
        )

    # ── the tie-break ───────────────────────────────────────────────────────
    if llm is not None:
        try:
            answer = llm.classify(token, context=context)
        except LlmError as exc:
            # Named, not swallowed: "the model was asked and could not answer"
            # and "there is no model here" are different facts about a queue
            # item, and only one of them is worth retrying later.
            notes.append(f"{LLM_UNAVAILABLE} {exc}")
        else:
            # ⚑ Agreement is computed BEFORE the merge, because the merge drops
            # the deterministic twin of the model's answer — which is precisely
            # the proposal that constitutes the agreement.
            agreed = _agreement(proposals, answer)
            proposals = _merge(proposals, answer)
            if agreed is not None and validates(agreed, token, lang=lang):
                proposals = _promote(proposals, agreed)
                return CascadeResult(
                    token=token,
                    proposals=tuple(proposals),
                    status=STATUS_AUTO_VALIDATED,
                    layer=route(token, proposals[0], vocabulary=vocabulary),
                    tier=TIER_LLM,
                    agreed=True,
                    notes=tuple(notes),
                )

    # ── the human tier ──────────────────────────────────────────────────────
    return CascadeResult(
        token=token,
        proposals=tuple(proposals),
        status=STATUS_PROPOSED,
        layer=route(token, proposals[0] if proposals else None, vocabulary=vocabulary),
        tier=TIER_HUMAN,
        notes=tuple(notes),
    )


def route(
    token: str,
    proposal: Proposal | None = None,
    *,
    vocabulary: Iterable[str] = (),
) -> str:
    """`core` or `world` — LM-10's default, which a human may override.

    Three signals, in order of how much they know:

    1. the proposal says `PROPN` — the guesser reached a proper-noun pattern,
       which is the strongest evidence available and the usual case;
    2. the token folds onto something in the world's model vocabulary — a term
       the model already names is the world's whatever its part of speech;
    3. nothing was proposed at all and the token is capitalized. A weak signal,
       and it decides for `world` anyway: an unrecognised capitalized word is
       far more often a name than a common noun, and being wrong on the world
       side is retractable while being wrong in the core is published.
    """
    folded = {_fold(term) for term in vocabulary}
    if folded and _fold(token) in folded:
        return LAYER_WORLD
    if proposal is not None:
        if proposal.upos == "PROPN":
            return LAYER_WORLD
        if folded and _fold(proposal.lemma) in folded:
            return LAYER_WORLD
        return LAYER_CORE
    return LAYER_WORLD if token[:1].isupper() else LAYER_CORE


# ── internals ────────────────────────────────────────────────────────────────


def _fold(s: str) -> str:
    # Imported here rather than at module level only to keep the import graph of
    # this package's docstring honest: the fold is the wheel's, always.
    from ttrmorph.engine import fold

    return fold(s)


def _merge(proposals: Sequence[Proposal], answer: Proposal) -> list[Proposal]:
    """Add the model's answer, ranked among the others by confidence."""
    keep = [p for p in proposals if p.key != answer.key]
    merged = [*keep, answer]
    return sorted(merged, key=lambda p: (-p.confidence, p.source, p.lemma))


def _agreement(proposals: Sequence[Proposal], answer: Proposal) -> Proposal | None:
    """The corroborated proposal, if a deterministic leg named the same entry.

    Sources must differ: an LLM that answered twice has not corroborated
    itself. `SOURCE_GUESSER` is spelled out rather than "anything that is not
    the LLM" so that the Wave C inflector counts the day it appears.
    """
    for proposal in proposals:
        deterministic = proposal.source in (SOURCE_GUESSER, TIER_INFLECTOR)
        if deterministic and proposal.key == answer.key:
            return Proposal(
                lemma=answer.lemma,
                upos=answer.upos,
                vzor=answer.vzor,
                flags=answer.flags,
                confidence=AGREEMENT_CONFIDENCE,
                source=proposal.source,
            )
    return None


def _promote(proposals: Sequence[Proposal], agreed: Proposal) -> list[Proposal]:
    """Put the corroborated proposal at the head, its twins dropped."""
    rest = [p for p in proposals if p.key != agreed.key]
    return [agreed, *rest]


__all__ = [
    "LLM_UNAVAILABLE",
    "AGREEMENT_CONFIDENCE",
    "LAYER_CORE",
    "LAYER_WORLD",
    "STATUS_AUTO_VALIDATED",
    "STATUS_PROPOSED",
    "TIER_GUESSER",
    "TIER_HUMAN",
    "TIER_INFLECTOR",
    "TIER_LLM",
    "CascadeResult",
    "route",
    "run_cascade",
]
