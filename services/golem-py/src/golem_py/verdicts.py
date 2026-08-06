# SPDX-License-Identifier: Apache-2.0
"""`assess_gaps` — the loop's objective function (RV-11), as a PURE function.

    gaps ∧ eligible rungs ∧ budget  ⇒  climb
    load-bearing gap ∧ ask budget   ⇒  ask
    covered (or only carryable gaps) ⇒  emit
    otherwise                        ⇒  refuse

It is pure — `(state, ladder, budgets) → verdict`, no mutation — for one reason worth
stating: the verdict is the only place the Golem decides anything, and a decision that
also edits the lattice cannot be tested against a synthetic one. The graph node applies
whatever the verdict implies; this module never does.

Which gaps BLOCK an answer is the subtle half, and the line is **load-bearing** —
the same line RV-15 draws for asking:

* a **load-bearing** gap blocks. It is asked about while the HITL budget lasts, and
  once that is spent it lands on the terminal posture (`strict` ⇒ a refusal carrying
  the lattice). Answering over a gap in the question's SUBJECT would be the guess this
  whole design exists to prevent.
* every **other** open gap is CARRIED, not blocking — an honest gap note beside the
  answer (RV-19). H5 is the case that settles it: *"porovnej s plánem"* leaves an
  honest `G1_UNBOUND` on *plánem* in FILTER position, and the hero must ANSWER while
  saying what it could not do. Refusing the whole question over an unbindable filter
  would throw away four correct bindings to protect one.
* `degrade-banner` (G5_NLP_DARK) never blocks even when load-bearing: no answer the
  user can give would fix a capability the matrix does not have, so the honest banner
  IS the answer.

⚑ This corrects the P4.1 reading, which had every `escalate-then-ask` gap blocking
whether or not it was load-bearing. That would have refused H5 — the phase gate's own
acceptance case — so the hero spec is what settled it.
"""

from __future__ import annotations

from enum import StrEnum

from golem_py.budgets import Budgets
from golem_py.ladder import AskPolicy, GapPolicy, LadderConfig, TerminalPosture
from golem_py.state import GapRecord, ResolutionState


class Verdict(StrEnum):
    CLIMB = "climb"
    ASK = "ask"
    EMIT = "emit"
    REFUSE = "refuse"


def _blocks(gap: GapRecord, policy: GapPolicy) -> bool:
    if policy.ask == AskPolicy.DEGRADE_BANNER:
        return False
    return gap.is_load_bearing()


def _askable(gap: GapRecord, policy: GapPolicy) -> bool:
    if policy.ask == AskPolicy.ASK_IMMEDIATELY:
        return True
    if policy.ask == AskPolicy.DEGRADE_BANNER:
        return False
    return gap.is_load_bearing()


def blocking_gaps(state: ResolutionState, ladder: LadderConfig) -> list[GapRecord]:
    return [g for g in state.open_gaps() if _blocks(g, ladder.gap_policy(g.kind))]


def askable_gaps(state: ResolutionState, ladder: LadderConfig) -> list[GapRecord]:
    """In ask order: the load-bearing ones first, then span order. One ask per round
    (P4.2 T5), so the ORDER decides which gap the single question is spent on."""
    open_gaps = [g for g in state.open_gaps() if _askable(g, ladder.gap_policy(g.kind))]
    return sorted(open_gaps, key=lambda g: (not g.is_load_bearing(), g.span.start))


def carryable_gaps(state: ResolutionState, ladder: LadderConfig) -> list[GapRecord]:
    """Open gaps an answer may carry as notes rather than block on (RV-19)."""
    return [g for g in state.open_gaps() if not _blocks(g, ladder.gap_policy(g.kind))]


def eligible_rungs(state: ResolutionState, ladder: LadderConfig, budgets: Budgets) -> list[str]:
    """Policy-eligible ∧ profile-allowed ∧ budget-affordable ∧ NOT ALREADY RUN.

    The last clause is the loop's termination guarantee. `lookup` spends no LLM budget
    (RV-33), so without "at most once per turn" a deterministic rung that proposes
    nothing would stay eligible forever and the loop edge would spin. It is also the
    ladder's own semantics: each rung handles the residue the one below it left.
    """
    kinds = {g.kind for g in state.open_gaps()}
    policy_eligible = [
        r for r in ladder.eligible_rungs(kinds, state.profile) if r not in state.rungs_run
    ]
    return budgets.runnable(policy_eligible, state)


def assess(state: ResolutionState, ladder: LadderConfig, budgets: Budgets) -> Verdict:
    if not state.open_gaps():
        return Verdict.EMIT

    if eligible_rungs(state, ladder, budgets):
        return Verdict.CLIMB

    if askable_gaps(state, ladder) and budgets.hitl_left(state):
        return Verdict.ASK

    if not blocking_gaps(state, ladder):
        return Verdict.EMIT

    if ladder.terminal_posture(state.profile) == TerminalPosture.BEST_EFFORT_WITH_GAP_NOTES:
        return Verdict.EMIT
    return Verdict.REFUSE
