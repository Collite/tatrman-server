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
from golem_py.state import (
    Disposition,
    FrameRole,
    GapKind,
    GapRecord,
    Mention,
    Pin,
    ResolutionState,
    Span,
)
from tests.helpers import RecordedCore, deps, g1_subject_gap, h1_lattice, run_traced

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
    from the one the user was asked about."""
    core = RecordedCore(g1_subject_gap())
    state = g1_subject_gap()
    state.question = "..."
    state.pin = Pin(option_id="opt-1")

    _, visited = await run_traced(state, deps(core))

    assert "call_core" not in visited
    assert visited[:3] == ["start", "decision", "assess_gaps"]
    assert core.calls == 0


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
async def test_a_non_load_bearing_blocking_gap_reaches_refuse_and_only_refuse() -> None:
    """RV-15 is a floor AND a ceiling: a gap that is not load-bearing must not generate
    a question. With no rung able to help, the honest move is the refusal that carries
    the lattice — not a guess, and not an ask nobody may spend."""
    lattice = g1_subject_gap()
    lattice.gaps[0].frame_roles = [FrameRole.FILTER]
    _, visited = await run_traced(ResolutionState(question="q"), deps(RecordedCore(lattice)))

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
