# SPDX-License-Identifier: Apache-2.0
"""P4.1·T6 — the `resolve.gate:v1` client, against a recorded gate.

Live wiring is P4.4's. What is proved here is the contract's semantics: what a gated
binding attaches to, what replaces what, and which outcomes a rung must learn from
versus which one it may simply retry.
"""

from __future__ import annotations

import sys
from pathlib import Path

_GENERATED = Path(__file__).resolve().parents[1] / "generated"
if str(_GENERATED) not in sys.path:  # pragma: no cover
    sys.path.insert(0, str(_GENERATED))

from org.tatrman.resolver.v1 import resolver_pb2  # noqa: E402

from golem_py.gate_client import (  # noqa: E402
    RETRYABLE_OUTCOME,
    TERMINAL_OUTCOMES,
    apply_gate_result,
    lattice_to_proto,
    result_from_proto,
)
from golem_py.state import GapKind, Hypothesis, Span  # noqa: E402
from tests.helpers import g1_subject_gap  # noqa: E402
from tests.lattice_fixtures import lattice_golden  # noqa: E402


def _accepted_response() -> resolver_pb2.GateResponse:
    resp = resolver_pb2.GateResponse()
    outcome = resp.outcomes.add()
    outcome.hypothesis.ref = "md.dimension.Customer.category"
    outcome.hypothesis.proposing_rung = "local"
    outcome.hypothesis.span.start = 0
    outcome.hypothesis.span.end = 16
    outcome.hypothesis.span.text = "čerpací stanice"
    outcome.accepted = True
    outcome.binding.ref = "md.dimension.Customer.category"
    outcome.binding.target_class = resolver_pb2.TARGET_CLASS_MODEL_OBJECT
    outcome.binding.evidence_class = resolver_pb2.EVIDENCE_CLASS_DECLARED_ALIAS
    outcome.binding.source = resolver_pb2.SOURCE_TAG_DECLARED
    outcome.binding.in_class_score = 0.94
    outcome.binding.producer.proposing_rung = "local"
    resp.gated_bindings.append(outcome.binding)
    entry = resp.rung_log_entry
    entry.round = 1
    entry.rung = "local"
    entry.action = "regate"
    entry.bindings_added = 1
    return resp


def test_an_accepted_hypothesis_becomes_a_binding_on_its_own_mention() -> None:
    """The route from a binding to a mention is the HYPOTHESIS's span — `Binding`
    carries none. Attaching by anything else would be a guess."""
    state = g1_subject_gap()
    result = result_from_proto(_accepted_response())

    apply_gate_result(state, result)

    mention = state.mentions[0]
    assert [b.ref for b in mention.bindings] == ["md.dimension.Customer.category"]
    # The provenance says which rung proposed it — the other half of "gate evidence".
    assert mention.bindings[0].producer.proposing_rung == "local"


def test_updated_gaps_replace_the_gap_list_rather_than_merging_into_it() -> None:
    """The response is "the gaps recomputed after gating" — the caller's next input.
    Merging would leave the answered gap open and the loop would ask again."""
    state = g1_subject_gap()
    assert state.gaps and state.gaps[0].kind == GapKind.G1_UNBOUND

    apply_gate_result(state, result_from_proto(_accepted_response()))

    assert state.gaps == []


def test_the_rung_log_entry_is_appended_so_the_trail_stays_whole() -> None:
    state = g1_subject_gap()
    apply_gate_result(state, result_from_proto(_accepted_response()))

    assert [e.rung for e in state.rung_log] == ["local"]
    assert state.rung_log[0].action == "regate"


def test_a_binding_whose_span_matches_no_mention_is_dropped() -> None:
    """Inventing a mention to hang a binding on would put a span in the lattice the
    core never saw — and every downstream consumer treats the lattice as the core's
    account of the question."""
    state = g1_subject_gap()
    resp = _accepted_response()
    resp.outcomes[0].hypothesis.span.start = 900
    resp.outcomes[0].hypothesis.span.end = 910

    apply_gate_result(state, result_from_proto(resp))

    assert state.mentions[0].bindings == []


def test_a_refused_hypothesis_carries_its_reason_and_produces_no_binding() -> None:
    resp = resolver_pb2.GateResponse()
    outcome = resp.outcomes.add()
    outcome.hypothesis.ref = "md.measure.revenue"
    outcome.accepted = False
    outcome.reason = "NO_CANDIDATE"

    result = result_from_proto(resp)

    assert result.outcomes[0].binding is None
    assert result.outcomes[0].reason in TERMINAL_OUTCOMES
    assert not result.outcomes[0].is_infrastructure_failure()


def test_lookup_failed_is_an_infrastructure_verdict_not_an_answer() -> None:
    """⚑ The distinction the proto comment insists on: the matcher could not be ASKED.
    Retrying is safe (`Gate` is stateless), and reporting it as NO_CANDIDATE would teach
    a proposer that a correct guess does not exist."""
    resp = resolver_pb2.GateResponse()
    outcome = resp.outcomes.add()
    outcome.accepted = False
    outcome.reason = RETRYABLE_OUTCOME

    result = result_from_proto(resp)

    assert result.outcomes[0].is_infrastructure_failure()
    assert RETRYABLE_OUTCOME not in TERMINAL_OUTCOMES


def test_the_lattice_round_trips_out_to_the_wire_without_the_loop_state() -> None:
    """`Gate` is stateless: the caller carries the lattice. What it does NOT carry is
    our turn-local loop state — the token stays the core's, the state stays ours."""
    state = lattice_golden("h2-cs")
    state.llm_invocations = 2
    state.resume_token = "core-signed"

    msg = lattice_to_proto(state)

    assert [m.id for m in msg.mentions] == ["m1", "m2", "m3", "m4"]
    assert len(msg.gaps) == 2
    assert msg.mentions[2].bindings == []  # the G1 mention stays unbound on the wire too
    assert "resume_token" not in [f.name for f, _ in msg.ListFields()]
    assert "llm_invocations" not in [f.name for f, _ in msg.ListFields()]


def test_a_hypothesis_travels_with_its_span_and_proposing_rung() -> None:
    from golem_py.gate_client import _hypothesis_to_proto

    msg = _hypothesis_to_proto(
        Hypothesis(
            span=Span(start=3, end=9, text="tržeb"),
            ref="md.measure.revenue",
            proposing_rung="local",
        )
    )

    assert msg.span.text == "tržeb"
    assert msg.proposing_rung == "local"
