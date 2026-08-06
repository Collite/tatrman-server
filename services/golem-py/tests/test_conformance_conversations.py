# SPDX-License-Identifier: Apache-2.0
"""P4.4·T2/T3 — the conformance-conversation tier.

Every fixture in `conformance/conversations/` driven through the whole service, with
its own `expect:` block as the assertion. The fixtures are SHARED — the Kotlin Golem
(RV-P5) must pass these same files unchanged (RV-28) — so this runner asserts what the
fixture SAYS rather than what golem-py happens to produce.

The assertions here are deliberately generic. A shell-specific check would pass in one
language and be unwritable in the other, which is exactly how "one corpus, N shells"
stops being true.
"""

from __future__ import annotations

import json

import pytest

from golem_py.outputs import Answer, Ask, RefusalWithGaps
from tests.conversation_runner import FIXTURE_DIR, drive, fixture_ids, load_fixture

EXPECTED_FIXTURES = [
    "h1-answer",
    "h1prime-regate",
    "h2-ask-pin-resume",
    "h4-refusal",
    "h5-answer-with-gap",
]


def test_the_corpus_holds_exactly_the_five_heroes() -> None:
    """A suite that silently loses a fixture reports as green. The list is pinned here
    and the CONTENT is pinned by `conformance/corpus-hashes.sha256`."""
    assert fixture_ids() == EXPECTED_FIXTURES


@pytest.mark.parametrize("name", EXPECTED_FIXTURES)
def test_every_fixture_states_its_invariants_and_names_its_corpus(name: str) -> None:
    """P2.4's rule for this corpus, extended: a fixture says what it asserts, in words
    a second shell can be held to."""
    fixture = load_fixture(name)

    assert fixture["corpus"] == "hartland_cz"
    assert fixture["invariants"], "a fixture with no stated invariant teaches nothing"
    assert fixture["turns"], "a fixture with no turns asserts nothing"


@pytest.mark.parametrize("name", EXPECTED_FIXTURES)
def test_no_fixture_copies_a_lattice(name: str) -> None:
    """The lattices are the resolver's own goldens, named by id. A fixture that inlined
    one would drift from the core's suite the first time either moved."""
    raw = json.loads((FIXTURE_DIR / f"{name}.json").read_text(encoding="utf-8"))
    for turn in raw["turns"]:
        core = turn.get("core")
        if core is not None:
            assert isinstance(core["lattice"], str)


@pytest.mark.asyncio
@pytest.mark.parametrize("name", EXPECTED_FIXTURES)
async def test_fixture(name: str) -> None:
    run = await drive(name)
    turns = [t for t in run.fixture["turns"] if t["tool"] != "resolve.gate:v1"]

    for index, turn in enumerate(turns):
        expect = turn.get("expect", {})
        output = run.outputs[index]
        _assert_turn(name, index, expect, output, run)

    # The gate turns, asserted against the lattice they folded into.
    gate_turns = [t for t in run.fixture["turns"] if t["tool"] == "resolve.gate:v1"]
    for position, turn in enumerate(gate_turns):
        _assert_gate(turn.get("expect", {}), run, position)


def _assert_turn(name: str, index: int, expect: dict, output: object, run: object) -> None:  # type: ignore[type-arg]
    where = f"{name} turn {index + 1}"
    outcome = expect.get("outcome")

    if outcome == "ask":
        assert isinstance(output, Ask), where
        # An ask is not an answer: it must carry everything a resume needs.
        assert output.snapshot_id and output.escape, where
        if "min_options" in expect:
            assert len(output.options) >= expect["min_options"], where
        if "gap_kind" in expect:
            assert output.gap_kind.value == expect["gap_kind"].removeprefix("GAP_KIND_"), where
        if "asked_span" in expect:
            assert expect["asked_span"] in output.question, where
        if "escape_offered" in expect:
            assert bool(output.escape) == expect["escape_offered"], where
        if "snapshot_stored" in expect:
            assert bool(output.snapshot_id) == expect["snapshot_stored"], where
        return

    if outcome == "refusal":
        assert isinstance(output, RefusalWithGaps), where
        assert output.reason == expect["refusal_reason"], where
        if "min_bindings" in expect:
            bound = [b for m in output.lattice.mentions for b in m.bindings]
            assert len(bound) >= expect["min_bindings"], where
        if "gap_kinds" in expect:
            assert [g.kind.value for g in output.lattice.gaps] == [
                k.removeprefix("GAP_KIND_") for k in expect["gap_kinds"]
            ], where
        if "composable_residue" in expect:
            assert output.composable_residue == expect["composable_residue"], where
        return

    assert outcome == "answer", where
    assert isinstance(output, Answer), where
    if "llm_invocations" in expect:
        assert output.llm_invocations == expect["llm_invocations"], where
    if "asks" in expect:
        assert output.asks == expect["asks"], where
    if "core_calls_total" in expect:
        assert run.core.calls == expect["core_calls_total"], where  # type: ignore[attr-defined]
    envelope = output.envelope
    question = envelope.question if envelope else None
    for key in ("measures", "subjects", "operators", "inapplicable_operators"):
        if key in expect:
            assert question is not None and getattr(question, key) == expect[key], where
    if "member_filters" in expect:
        assert question is not None
        assert [f.member_ref for f in question.filters if f.member_ref] == expect[
            "member_filters"
        ], where
    if "gaps_carried" in expect:
        assert [g.kind.value for g in output.gaps_carried] == [
            k.removeprefix("GAP_KIND_") for k in expect["gaps_carried"]
        ], where
    if "gaps_carried_spans" in expect:
        assert [g.span.text for g in output.gaps_carried] == expect["gaps_carried_spans"], where
    if "provenance_lexicon_artifact_hash" in expect:
        assert envelope is not None
        assert (
            envelope.provenance["lexicon_artifact_hash"]
            == expect["provenance_lexicon_artifact_hash"]
        ), where
    if "gated_refs" in expect:
        bound_refs = [b.ref for m in output.lattice.mentions for b in m.bindings]
        for ref in expect["gated_refs"]:
            assert ref in bound_refs, where


def _assert_gate(expect: dict, run: object, position: int) -> None:  # type: ignore[type-arg]
    result = run.gate_results[position]  # type: ignore[attr-defined]
    accepted = [o for o in result.outcomes if o.accepted]

    if "gated_refs" in expect:
        assert [o.binding.ref for o in accepted if o.binding] == expect["gated_refs"]
    if "evidence_classes" in expect:
        assert [o.binding.evidence_class.value for o in accepted if o.binding] == [
            c.removeprefix("EVIDENCE_CLASS_") for c in expect["evidence_classes"]
        ]
    if "proposing_rung" in expect:
        # A hypothesis is not evidence — but a gated binding must carry WHO proposed it.
        assert all(o.hypothesis.proposing_rung == expect["proposing_rung"] for o in accepted)
    if "gap_kinds" in expect:
        assert [g.kind.value for g in result.updated_gaps] == [
            k.removeprefix("GAP_KIND_") for k in expect["gap_kinds"]
        ]


@pytest.mark.asyncio
async def test_the_zero_rung_default_file_is_what_the_suite_runs() -> None:
    """The suite proving the SHIPPED default is the point (T3): no fixture may quietly
    enable a rung. If this ever needs relaxing, the relaxation is the finding."""
    from golem_py.ladder import DEFAULT_CONFIG_PATH, load_default

    run = await drive("h1-answer")

    assert run.deps is not None
    assert run.deps.ladder == load_default()
    assert all(p.rungs == [] for p in load_default().policy.values())
    assert DEFAULT_CONFIG_PATH.exists()
