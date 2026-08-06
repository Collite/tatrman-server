# SPDX-License-Identifier: Apache-2.0
"""The RV-11 loop as a pydantic-graph.

    start ─┬─(fresh)──> call_core ──> assess_gaps ──> {ladder_loop | ask | emit | refuse}
           └─(resume)──────────────────> assess_gaps        ^         │
                                                            └─────────┘  the loop edge

Three framework facts this module is built on, all verified by inspection at the P0-3
spike and all still true at 2.22.0:

1. **No built-in persistence.** `pydantic_graph.persistence` does not exist and
   `Graph.run()` has no start-at-node argument. Resume is OURS: a `ResolutionState`
   snapshot plus a start step that routes on it. That is not a workaround — RV-11 says
   a resume rejoins `assess_gaps`, and a start step that dispatches on the pin says
   exactly that, in one readable place, where the RS-26 token contract can see it.
2. **⚑ `.to(a, b)` is a FAN-OUT, not a branch, and it fails SILENTLY** — every target
   runs. Every fork here is a `g.decision()`, and `tests/test_graph_shape.py` asserts
   it mechanically over the built graph rather than trusting review.
3. **A `@g.step` returns the plain value** wired to `g.end_node`; returning `End(x)`
   does NOT unwrap. Unwrapping happens in exactly one place: `run_turn()`.
"""

from __future__ import annotations

import time
from typing import Literal

from pydantic_graph import Graph, GraphBuilder, StepContext, TypeExpression

from golem_py.budgets import Budgets
from golem_py.deps import Deps
from golem_py.ladder import DETERMINISTIC_RUNGS
from golem_py.observability import log_node, log_turn
from golem_py.outputs import Answer, Ask, RefusalWithGaps, TurnOutput
from golem_py.state import Disposition, GapKind, ResolutionState, RungLogEntry
from golem_py.verdicts import (
    askable_gaps,
    carryable_gaps,
    eligible_rungs,
)

StartVerdict = Literal["resume", "fresh"]


def _budgets(ctx: StepContext[ResolutionState, Deps, object]) -> Budgets:
    """Budgets are turn-scoped and the counters live on the state, so rebuilding the
    accounting per node is safe — except for the wall clock, which is anchored to the
    turn's start instant carried on the deps' clock."""
    return Budgets.for_state(ctx.state, ctx.deps.ladder, ctx.deps.clock)


def _record_ladder_noop(state: ResolutionState, note: str) -> None:
    """RV-27's "full SHAPE present" is only auditable if the no-op says so. A turn that
    had open gaps and climbed nothing writes ONE entry naming the empty rung list —
    otherwise the zero-rung default is indistinguishable from a missing ladder."""
    if any(e.rung == "ladder" for e in state.rung_log):
        return
    state.rung_log.append(
        RungLogEntry(
            round=state.next_round(),
            rung="ladder",
            action="noop",
            gaps_open=len(state.open_gaps()),
            note=note,
        )
    )


def build_graph() -> Graph[ResolutionState, Deps, None, TurnOutput]:
    g = GraphBuilder(
        state_type=ResolutionState,
        deps_type=Deps,
        output_type=Ask | Answer | RefusalWithGaps,
    )

    @g.step
    async def start(ctx: StepContext[ResolutionState, Deps, None]) -> StartVerdict:
        """THE resume fork. A pin means this run is the second half of a paused turn,
        so the deterministic resolve already happened — rejoin at `assess_gaps` and do
        not pay for it twice."""
        verdict: StartVerdict = "resume" if ctx.state.pin is not None else "fresh"
        log_node(ctx.state, "start", verdict=verdict)
        return verdict

    @g.step
    async def call_core(ctx: StepContext[ResolutionState, Deps, object]) -> None:
        """The ONE deterministic call (`resolve.bind:v1`). Zero LLM below the door."""
        t0 = ctx.deps.clock()
        lattice = await ctx.deps.core.resolve(
            question=ctx.state.question,
            locale=ctx.state.locale,
            conversation_id=ctx.state.conversation_id,
            caller_subject=ctx.state.caller_subject,
        )
        # Merge the door's lattice in WITHOUT clobbering turn-local fields (budgets,
        # pin, identity). The lattice is the door's; the loop state is ours.
        ctx.state.parse = lattice.parse
        ctx.state.mentions = lattice.mentions
        ctx.state.values = lattice.values
        ctx.state.gaps = lattice.gaps
        ctx.state.lexicon_versions = lattice.lexicon_versions
        ctx.state.trace_id = lattice.trace_id or ctx.state.trace_id
        if lattice.resume_token:
            ctx.state.resume_token = lattice.resume_token
        # The core writes round 0 of the rung log for its own pass; if it did not (an
        # older door), record it here rather than leaving the trail starting at 1.
        ctx.state.rung_log.extend(lattice.rung_log)
        if not any(e.rung == "core" for e in ctx.state.rung_log):
            ctx.state.rung_log.append(
                RungLogEntry(
                    round=0,
                    rung="core",
                    action="annotate",
                    mention_ids=[m.id for m in lattice.mentions],
                    value_ids=[v.id for v in lattice.values],
                    bindings_added=sum(len(m.bindings) for m in lattice.mentions),
                    gaps_open=len(lattice.gaps),
                    elapsed_ms=int((ctx.deps.clock() - t0) * 1000),
                    note="resolve.bind:v1 — deterministic, zero-LLM",
                )
            )
        log_node(
            ctx.state,
            "call_core",
            mentions=len(ctx.state.mentions),
            gaps=len(ctx.state.gaps),
        )

    @g.step
    async def assess_gaps(
        ctx: StepContext[ResolutionState, Deps, object],
    ) -> Literal["climb", "ask", "emit", "refuse"]:
        """The loop's objective function — a PURE verdict (see `verdicts.py`). This
        node decides nothing on its own and mutates nothing; it asks."""
        from golem_py.verdicts import assess

        verdict = assess(ctx.state, ctx.deps.ladder, _budgets(ctx))
        log_node(
            ctx.state,
            "assess_gaps",
            verdict=verdict.value,
            gaps_open=len(ctx.state.open_gaps()),
            llm_invocations=ctx.state.llm_invocations,
        )
        return verdict.value

    @g.step
    async def ladder_loop(ctx: StepContext[ResolutionState, Deps, object]) -> None:
        """Climb the eligible rungs. Every rung PROPOSES; nothing here binds (RV-7) —
        hypotheses go back through `resolve.gate:v1` and are gated by the same rules as
        any other candidate.

        With the shipped zero-rung default this node is unreachable, and that is the
        design: the SHAPE is present, the policy admits no rung. When it IS entered
        with nothing runnable, it says so rather than looping.
        """
        budgets = _budgets(ctx)
        rungs = eligible_rungs(ctx.state, ctx.deps.ladder, budgets)
        if not rungs:
            _record_ladder_noop(
                ctx.state, "no eligible rung — zero-rung policy or exhausted budget (RV-27)"
            )
            return

        for rung in rungs:
            t0 = ctx.deps.clock()
            ctx.state.rungs_run.append(rung)
            if rung not in DETERMINISTIC_RUNGS:
                ctx.state.llm_invocations += 1
            # ⚑ THE SEAM. A real rung proposes hypotheses here and re-gates them via
            # `ctx.deps.gate`. P4.1 stops at the seam deliberately: the rung bodies are
            # RV-P5's (Kotlin) and this service's own rung implementations are a
            # post-P4 effort — what P4 ships is the loop that would drive them, and a
            # loop with a fake rung in it would be untestable theatre.
            ctx.state.rung_log.append(
                RungLogEntry(
                    round=ctx.state.next_round(),
                    rung=rung,
                    action="climb",
                    gaps_open=len(ctx.state.open_gaps()),
                    elapsed_ms=int((ctx.deps.clock() - t0) * 1000),
                    note="seam: proposes → resolve.gate:v1 (RV-7 proposer-not-binder)",
                )
            )
        log_node(ctx.state, "ladder_loop", rungs=",".join(rungs))

    @g.step
    async def ask(ctx: StepContext[ResolutionState, Deps, object]) -> Ask:
        """Pause. P4.2 gives this node the snapshot store and the option set; P4.1
        emits the question and the core's token, which is what the graph can honestly
        produce today."""
        _record_ladder_noop(ctx.state, "ask reached with no rung climbed (zero-rung default)")
        gap = askable_gaps(ctx.state, ctx.deps.ladder)[0]
        ctx.state.hitl_rounds += 1
        gap.asked_round = ctx.state.hitl_rounds
        out = Ask(
            question=f"What does {gap.span.text!r} refer to?",
            gap_kind=gap.kind,
            resume_token=ctx.state.resume_token,
            lattice=ctx.state,
        )
        log_node(ctx.state, "ask", gap_kind=gap.kind.value, round=ctx.state.hitl_rounds)
        log_turn(ctx.state, "ask")
        return out

    @g.step
    async def emit(ctx: StepContext[ResolutionState, Deps, object]) -> Answer:
        """A covered lattice (or one whose residue is carryable) becomes an answer.

        P4.1's answer is a STRUCTURAL summary, not a rendered one: compose, the query
        door and the skill bodies are P4.3. Saying "N mentions bound" is the honest
        thing to say before there is a retrieval path.
        """
        if ctx.state.open_gaps():
            _record_ladder_noop(ctx.state, "emitted with carryable gaps and no rung climbed")
        carried = carryable_gaps(ctx.state, ctx.deps.ladder)
        for gap in carried:
            if gap.kind == GapKind.G5_NLP_DARK:
                gap.disposition = Disposition.DEGRADED
            else:
                gap.disposition = Disposition.IGNORED
        bound = sum(1 for m in ctx.state.mentions if m.bindings)
        out = Answer(
            content=(
                f"{bound}/{len(ctx.state.mentions)} mentions bound, "
                f"{len(ctx.state.gaps)} gaps, {ctx.state.llm_invocations} LLM calls"
            ),
            lattice=ctx.state,
            llm_invocations=ctx.state.llm_invocations,
            asks=ctx.state.hitl_rounds,
            gaps_carried=carried,
        )
        log_node(ctx.state, "emit", bound=bound, carried=len(carried))
        log_turn(ctx.state, "emit")
        return out

    @g.step
    async def refuse(ctx: StepContext[ResolutionState, Deps, object]) -> RefusalWithGaps:
        """The honest refusal, with the lattice attached: "this much I understood"."""
        _record_ladder_noop(ctx.state, "refused with no rung climbed (zero-rung default)")
        out = RefusalWithGaps(
            reason="UNRESOLVED_GAPS",
            gaps=ctx.state.open_gaps(),
            lattice=ctx.state,
        )
        log_node(ctx.state, "refuse", gaps_open=len(ctx.state.open_gaps()))
        log_turn(ctx.state, "refuse")
        return out

    g.add(
        g.edge_from(g.start_node).to(start),
        # ⚑ Every fork is a decision node. A bare multi-target `.to(...)` here would
        # run BOTH targets, silently (P0-3's most expensive finding).
        g.edge_from(start).to(
            g.decision()
            .branch(g.match(TypeExpression[Literal["resume"]]).to(assess_gaps))
            .branch(g.match(TypeExpression[Literal["fresh"]]).to(call_core))
        ),
        g.edge_from(call_core).to(assess_gaps),
        g.edge_from(assess_gaps).to(
            g.decision()
            .branch(g.match(TypeExpression[Literal["climb"]]).to(ladder_loop))
            .branch(g.match(TypeExpression[Literal["ask"]]).to(ask))
            .branch(g.match(TypeExpression[Literal["emit"]]).to(emit))
            .branch(g.match(TypeExpression[Literal["refuse"]]).to(refuse))
        ),
        g.edge_from(ladder_loop).to(assess_gaps),  # THE loop edge
        g.edge_from(ask, emit, refuse).to(g.end_node),
    )
    return g.build()


_GRAPH: Graph[ResolutionState, Deps, None, TurnOutput] | None = None


def graph() -> Graph[ResolutionState, Deps, None, TurnOutput]:
    """The built graph, once per process. Building is pure and cheap, but the identity
    matters to the structural test, which walks THIS object."""
    global _GRAPH
    if _GRAPH is None:
        _GRAPH = build_graph()
    return _GRAPH


async def run_turn(state: ResolutionState, deps: Deps) -> TurnOutput:
    """Drive one turn. THE single place a graph result is unwrapped (framework fact 3);
    everything else in this service deals in `Ask | Answer | RefusalWithGaps`."""
    t0 = time.monotonic()
    output: TurnOutput = await graph().run(state=state, deps=deps)
    log_turn(state, "done", elapsed_ms=int((time.monotonic() - t0) * 1000))
    return output
