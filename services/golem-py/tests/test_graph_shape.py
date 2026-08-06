# SPDX-License-Identifier: Apache-2.0
"""P4.1·T2 — the graph's SHAPE, tested structurally rather than hoped for in review.

The fan-out trap (`.to(a, b)` runs BOTH targets and fails silently) cost the P0-3 spike
a debugging session and would cost a production turn a wrong answer. A review rule
catches it once; a test catches it every time — which is why the rule "every fork is a
`g.decision()`" is asserted here over the BUILT graph, not read off the source.
"""

from __future__ import annotations

import pytest
from pydantic_graph import Decision, Fork

from golem_py.graph import build_graph
from golem_py.ladder import LadderConfig
from golem_py.outputs import Answer
from golem_py.state import (
    Disposition,
    FrameRole,
    GapKind,
    GapRecord,
    Mention,
    Pin,
    ResolutionState,
    SignedOption,
    Span,
)
from tests.helpers import (
    RecordedCore,
    RecordedGate,
    deps,
    g1_subject_gap,
    h1_lattice,
    run_traced,
)

# --------------------------------------------------------------- structural (T2·b)


def test_no_node_is_a_fork() -> None:
    """⚑ THE review rule, made mechanical. A bare multi-target `.to(...)` compiles to a
    `Fork` ("broadcast") node and every branch runs. Nothing in this graph may fan out:
    every fork in the RV-11 loop is a CHOICE."""
    built = build_graph()
    forks = [nid for nid, node in built.nodes.items() if isinstance(node, Fork)]
    assert forks == [], f"fan-out node(s) in the graph: {forks} — every fork must be a decision"


def test_every_branch_point_is_a_decision_node() -> None:
    """The positive half: the two forks the RV-11 shape has (start's resume fork and
    assess_gaps' four-way verdict) both exist AS decisions."""
    built = build_graph()
    decisions = [nid for nid, node in built.nodes.items() if isinstance(node, Decision)]
    assert len(decisions) == 2, f"expected exactly two decision nodes, got {decisions}"


def test_no_source_has_more_than_one_outgoing_path() -> None:
    """Belt and braces: even a non-Fork source with two paths would be an ambiguity the
    builder resolved for us, and we would rather know."""
    built = build_graph()
    multi = {src: len(paths) for src, paths in built.edges_by_source.items() if len(paths) > 1}
    assert multi == {}, f"multi-path sources: {multi}"


# --------------------------------------------------------------- start routing (T2·a)


@pytest.mark.asyncio
async def test_fresh_turn_starts_at_call_core() -> None:
    core = RecordedCore(h1_lattice())
    state = ResolutionState(question="Zobraz tržby")

    _, visited = await run_traced(state, deps(core))

    assert visited[0] == "start"
    assert "call_core" in visited
    assert core.calls == 1


@pytest.mark.asyncio
async def test_a_pinned_state_rejoins_at_assess_gaps_and_never_recalls_the_core() -> None:
    """RV-11: a resume rejoins `assess_gaps`. Paying for a second deterministic resolve
    would be wrong twice over — the cost, and the risk that the second lattice differs
    from the one the user was asked about.

    `apply_pin` sits on the way IN (P4.2): the pin is gated before the assessment, not
    bound beside it. The property this test guards is unchanged — the core is not
    called again.
    """
    core = RecordedCore(g1_subject_gap())
    state = g1_subject_gap()
    state.question = "..."
    state.asked_gap_span = state.gaps[0].span
    state.signed_options = [SignedOption(id="opt-1", label="Gas stations", ref="md.x")]
    state.pin = Pin(option_id="opt-1")

    _, visited = await run_traced(state, deps(core, gate=RecordedGate()))

    assert "call_core" not in visited
    assert visited[:4] == ["start", "decision", "apply_pin", "assess_gaps"]
    assert core.calls == 0


# ------------------------------------------------------------------ budgets, assembled
#
# ⛑ Both of these are graph-level ON PURPOSE. The unit tests for the same machinery pass
# and passed throughout: `Budgets` was proven correct by constructing one directly, and
# rung eligibility was proven correct by calling `eligible_rungs`. What was wrong was the
# ASSEMBLY — who builds the budget object, and how often the ladder consults it — and an
# assembly bug is invisible to a test that assembles nothing.


def _ladder_with(tmp_path, rungs: list[str], **profile: object):  # type: ignore[no-untyped-def]
    import yaml

    from golem_py.ladder import DEFAULT_CONFIG_PATH

    raw = yaml.safe_load(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"))
    raw["policy"]["G1_UNBOUND"]["rungs"] = rungs
    raw["profiles"]["INVESTIGATION_DEEP"].update(profile)
    path = tmp_path / "ladder.yaml"
    path.write_text(yaml.safe_dump(raw), encoding="utf-8")
    return LadderConfig.load(path)


class _SlowCore:
    """A core that costs wall-clock time — the thing no recorded core could express."""

    def __init__(self, lattice: ResolutionState, clock: list[float], cost_s: float):
        self._lattice, self._clock, self._cost = lattice, clock, cost_s

    async def resolve(self, **_: object) -> ResolutionState:
        self._clock[0] += self._cost
        return self._lattice.model_copy(deep=True)


@pytest.mark.asyncio
async def test_the_ladder_budget_is_spent_by_the_TURN_not_reset_at_every_node(tmp_path) -> None:  # type: ignore[no-untyped-def]
    """⛑ `_budgets(ctx)` rebuilds `Budgets` per node, and `Budgets` anchored `started_at`
    to NOW — so `ladder_budget_ms` measured the node it was built in and could never be
    exceeded however long the turn ran. Sixty seconds into a turn budgeted at five, the
    assessment still admitted an LLM rung.

    The turn's start is anchored once, in `start`, and every run passes through it.
    """
    clock = [0.0]
    ladder = _ladder_with(tmp_path, ["local"], ladder_budget_ms=5000)
    state = g1_subject_gap()
    state.profile = "INVESTIGATION_DEEP"
    core = _SlowCore(g1_subject_gap(), clock, cost_s=60.0)

    _, visited = await run_traced(
        state, deps(core, ladder=ladder, gate=RecordedGate(), clock=lambda: clock[0])
    )

    assert "ladder_loop" not in visited, "the ladder ran on a wall clock that had expired"
    assert "ask" in visited


@pytest.mark.asyncio
async def test_the_climb_stops_when_the_invocation_budget_runs_out_mid_ladder(tmp_path) -> None:  # type: ignore[no-untyped-def]
    """⛑ `ladder_loop` filtered eligibility ONCE at entry and then climbed the whole
    list, so a budget of one bought three invocations — including `emulated`, the most
    expensive rung in the vocabulary. A budget is a cap on spending, not a gate on
    starting."""
    ladder = _ladder_with(tmp_path, ["local", "capable", "emulated"], max_llm_invocations=1)
    state = g1_subject_gap()
    state.profile = "INVESTIGATION_DEEP"

    out, visited = await run_traced(
        state, deps(RecordedCore(g1_subject_gap()), ladder=ladder, gate=RecordedGate())
    )

    assert "ladder_loop" in visited
    assert out.lattice is not None
    assert out.lattice.llm_invocations == 1
    assert out.lattice.rungs_run == ["local"]
    assert any(e.action == "halt" for e in out.lattice.rung_log), "the halt must be auditable"


# ------------------------------------------------------- verdict → node routing (T2·d)


@pytest.mark.asyncio
async def test_a_covered_lattice_reaches_emit_and_only_emit() -> None:
    _, visited = await run_traced(ResolutionState(question="q"), deps(RecordedCore(h1_lattice())))

    assert "emit" in visited
    assert "ask" not in visited and "refuse" not in visited and "ladder_loop" not in visited


@pytest.mark.asyncio
async def test_a_load_bearing_gap_reaches_ask_and_only_ask() -> None:
    _, visited = await run_traced(
        ResolutionState(question="q"), deps(RecordedCore(g1_subject_gap()))
    )

    assert "ask" in visited
    assert "emit" not in visited and "refuse" not in visited


@pytest.mark.asyncio
async def test_a_non_load_bearing_gap_is_carried_into_the_answer_not_asked_about() -> None:
    """RV-15 is a floor AND a ceiling: a gap that is not load-bearing must not generate
    a question — and it must not sink the answer either. H5 is the case that settles it:
    an unbindable FILTER (*plánem*) rides along as an honest gap note while the four
    correct bindings still answer."""
    # H5's shape: a composable lattice PLUS one unbindable FILTER.
    lattice = h1_lattice()
    lattice.mentions.append(
        Mention(id="m9", span=Span(start=60, end=66, text="plánem"), frame_roles=[FrameRole.FILTER])
    )
    lattice.gaps = [
        GapRecord(
            span=Span(start=60, end=66, text="plánem"),
            kind=GapKind.G1_UNBOUND,
            frame_roles=[FrameRole.FILTER],
            mention_id="m9",
        )
    ]
    out, visited = await run_traced(ResolutionState(question="q"), deps(RecordedCore(lattice)))

    assert "emit" in visited
    assert "ask" not in visited and "refuse" not in visited
    assert isinstance(out, Answer)
    assert [g.kind for g in out.gaps_carried] == [GapKind.G1_UNBOUND]


@pytest.mark.asyncio
async def test_a_load_bearing_gap_with_the_ask_budget_spent_reaches_refuse() -> None:
    """The refusal's real trigger: the question's SUBJECT is unknown, the ladder cannot
    help, and the one question CHAT_QUICK allows has already been spent."""
    state = ResolutionState(question="q", hitl_rounds=1)
    _, visited = await run_traced(state, deps(RecordedCore(g1_subject_gap())))

    assert "refuse" in visited
    assert "ask" not in visited and "emit" not in visited


@pytest.mark.asyncio
async def test_an_enabled_rung_reaches_ladder_loop_and_comes_back(tmp_path) -> None:  # type: ignore[no-untyped-def]
    """The loop edge, and its termination guarantee. A rung that proposes nothing must
    not be re-offered — the run passes through `ladder_loop` once and then settles."""
    cfg = _ladder_with_local_rung(tmp_path)
    _, visited = await run_traced(
        ResolutionState(question="q"), deps(RecordedCore(g1_subject_gap()), ladder=cfg)
    )

    assert visited.count("ladder_loop") == 1
    assert visited.count("assess_gaps") == 2  # before the climb, and after it
    assert "ask" in visited


# ------------------------------------------------------------ End unwrapping (T2·c)


@pytest.mark.asyncio
async def test_the_turn_output_is_unwrapped_exactly_once() -> None:
    """Framework fact 3: a step returns the plain value; the graph output is NOT an
    `End(...)` wrapper for callers to peel. `run_turn` is the only unwrapping site, and
    the type a caller sees is the union, never a marker."""
    from golem_py.graph import run_turn
    from golem_py.outputs import Answer

    out = await run_turn(ResolutionState(question="q"), deps(RecordedCore(h1_lattice())))

    assert isinstance(out, Answer)
    assert not hasattr(out, "data")


@pytest.mark.asyncio
async def test_zero_rung_default_records_why_it_climbed_nothing() -> None:
    """RV-27: the shipped default has the full SHAPE and no rung enabled. A turn that
    climbed nothing must SAY that it climbed nothing — otherwise the zero-rung default
    is indistinguishable from a ladder that is not wired up."""
    out, _ = await run_traced(ResolutionState(question="q"), deps(RecordedCore(g1_subject_gap())))

    assert out.lattice is not None
    noop = [e for e in out.lattice.rung_log if e.rung == "ladder"]
    assert len(noop) == 1 and noop[0].action == "noop"
    assert "zero-rung" in noop[0].note
    assert out.lattice.llm_invocations == 0


def _ladder_with_local_rung(tmp_path) -> LadderConfig:  # type: ignore[no-untyped-def]
    import yaml

    from golem_py.ladder import DEFAULT_CONFIG_PATH

    raw = yaml.safe_load(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"))
    raw["policy"]["G1_UNBOUND"]["rungs"] = ["local"]
    path = tmp_path / "ladder.yaml"
    path.write_text(yaml.safe_dump(raw), encoding="utf-8")
    return LadderConfig.load(path)


@pytest.mark.asyncio
async def test_a_gap_the_user_confirmed_unknown_no_longer_blocks() -> None:
    """The disposition vocabulary is not decoration: a closed gap must stop driving the
    loop, or a resumed conversation asks the same question forever."""
    lattice = g1_subject_gap()
    lattice.gaps[0].disposition = Disposition.USER_CONFIRMED_UNKNOWN
    out, visited = await run_traced(ResolutionState(question="q"), deps(RecordedCore(lattice)))

    assert "emit" in visited
    assert out.lattice is not None


@pytest.mark.asyncio
async def test_a_gap_with_no_frame_role_is_treated_as_load_bearing() -> None:
    """Under uncertainty the honest posture is to ask, not to drop silently. A gap the
    core could not attribute to a role is exactly that case."""
    lattice = ResolutionState(
        mentions=[Mention(id="m1", span=Span(start=0, end=4, text="xyzq"))],
        gaps=[GapRecord(span=Span(start=0, end=4, text="xyzq"), kind=GapKind.G1_UNBOUND)],
    )
    _, visited = await run_traced(ResolutionState(question="q"), deps(RecordedCore(lattice)))

    assert "ask" in visited
