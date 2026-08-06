# SPDX-License-Identifier: Apache-2.0
"""`resolve.gate:v1` — the re-gate client (P4.1·T6).

Q-13 = B: a SIBLING rpc (`ResolverService.Gate`), so `Resolve` stays single-purpose
(text → lattice). Hypotheses `{span?, ref?, correction?, proposing_rung}` go in; what
comes back is `{gated_bindings, updated_gaps, rung_log_entry, outcomes}`.

**Proposer-not-binder is structural here** (RV-7). No code path in golem-py builds a
`Binding` — bindings arrive only from a `bind` or `gate` RESPONSE, through the two
mappers in `core_client.py`, and `tests/test_single_binder_fence.py` asserts that over
the whole package. It is the Python port of the server's `SingleBinderTest`, and it
earned its keep there: it FAILED the day a third file could suddenly build one.

`Gate` is STATELESS — the caller carries the lattice, exactly as `Resolve` carries the
text — which is what makes the retry semantics safe: a retry is a fresh call with the
same inputs. That matters for `LOOKUP_FAILED`, below.
"""

from __future__ import annotations

import sys
from collections.abc import Sequence
from pathlib import Path
from typing import Any

_GENERATED = Path(__file__).resolve().parents[2] / "generated"
if str(_GENERATED) not in sys.path:  # pragma: no cover - import bootstrap
    sys.path.insert(0, str(_GENERATED))

import grpc  # noqa: E402
from org.tatrman.resolver.v1 import resolver_pb2, resolver_pb2_grpc  # noqa: E402

from golem_py.core_client import (  # noqa: E402
    DEFAULT_TARGET,
    binding_from_proto,
    gap_from_proto,
    rung_log_entry_from_proto,
)
from golem_py.deps import GateResult, HypothesisOutcome  # noqa: E402
from golem_py.observability import log_gate  # noqa: E402
from golem_py.state import Hypothesis, ResolutionState, Span  # noqa: E402

# Outcomes a rung must LEARN from: the vocabulary genuinely has no such term, or the
# proposal was contradicted. Re-proposing these is how a ladder burns its budget.
TERMINAL_OUTCOMES = frozenset({"NO_CANDIDATE", "REF_MISMATCH", "WEAK", "AMBIGUOUS", "NO_SPAN"})
# NOT a verdict about the hypothesis: the matcher could not be ASKED. Safe to retry —
# `Gate` is idempotent. Reporting it as NO_CANDIDATE would teach a proposer that a
# correct guess does not exist, which is the worst lie an evaluation loop can be told.
RETRYABLE_OUTCOME = "LOOKUP_FAILED"


def _hypothesis_to_proto(h: Hypothesis) -> Any:
    msg = resolver_pb2.Hypothesis(
        ref=h.ref, correction=h.correction, proposing_rung=h.proposing_rung
    )
    if h.span is not None:
        msg.span.CopyFrom(resolver_pb2.Span(start=h.span.start, end=h.span.end, text=h.span.text))
    return msg


def _span_to_proto(span: Any) -> Any:
    return resolver_pb2.Span(start=span.start, end=span.end, text=span.text)


def lattice_to_proto(state: ResolutionState) -> Any:
    """OUR lattice → the proto's, for the round trip. Only what `Gate` reads is sent:
    the mentions, values and gaps it re-computes over. The turn-local loop state is
    ours and has no business on the wire (P0-3 T5: two things, never merged)."""
    msg = resolver_pb2.ResolutionState()
    for m in state.mentions:
        pm = msg.mentions.add()
        pm.id = m.id
        pm.lemma = m.lemma
        pm.span.CopyFrom(_span_to_proto(m.span))
        for role in m.frame_roles:
            pm.frame_roles.append(resolver_pb2.FrameRole.Value(f"FRAME_ROLE_{role.value}"))
        for b in m.bindings:
            pb = pm.bindings.add()
            # An ECHO of a binding the core itself produced — not a construction. The
            # fence is about MINTING a binding from nothing, which nothing here does:
            # every field below is copied from a binding that already survived a gate.
            pb.ref = b.ref
            pb.target_class = resolver_pb2.TargetClass.Value(f"TARGET_CLASS_{b.target_class.value}")
            pb.evidence_class = resolver_pb2.EvidenceClass.Value(
                f"EVIDENCE_CLASS_{b.evidence_class.value}"
            )
            pb.method = resolver_pb2.MatchMethod.Value(f"MATCH_METHOD_{b.method.value}")
            pb.source = resolver_pb2.SourceTag.Value(f"SOURCE_TAG_{b.source.value}")
            pb.in_class_score = b.in_class_score
            if b.max_distance is not None:
                pb.max_distance = b.max_distance
            if b.uniqueness_margin is not None:
                pb.uniqueness_margin = b.uniqueness_margin
            if b.auto_bindable is not None:
                pb.auto_bindable = b.auto_bindable
            pb.producer.vocabulary_source = b.producer.vocabulary_source
            pb.producer.algorithm = b.producer.algorithm
            pb.producer.score = b.producer.score
            pb.producer.tier = b.producer.tier
            pb.producer.snapshot_hash = b.producer.snapshot_hash
            pb.producer.proposing_rung = b.producer.proposing_rung
    for gap in state.gaps:
        pg = msg.gaps.add()
        pg.span.CopyFrom(_span_to_proto(gap.span))
        pg.kind = resolver_pb2.GapKind.Value(f"GAP_KIND_{gap.kind.value}")
        for role in gap.frame_roles:
            pg.frame_roles.append(resolver_pb2.FrameRole.Value(f"FRAME_ROLE_{role.value}"))
        pg.disposition = resolver_pb2.Disposition.Value(f"DISPOSITION_{gap.disposition.value}")
        pg.asked_round = gap.asked_round
        pg.user_answer = gap.user_answer
        pg.mention_id = gap.mention_id
        pg.value_id = gap.value_id
    return msg


def result_from_proto(resp: Any) -> GateResult:
    result = GateResult(
        gated_bindings=[binding_from_proto(b) for b in resp.gated_bindings],
        updated_gaps=[gap_from_proto(g) for g in resp.updated_gaps],
        rung_log_entry=(
            rung_log_entry_from_proto(resp.rung_log_entry)
            if resp.HasField("rung_log_entry")
            else None
        ),
        outcomes=[
            HypothesisOutcome(
                hypothesis=Hypothesis(
                    span=(
                        Span(
                            start=o.hypothesis.span.start,
                            end=o.hypothesis.span.end,
                            text=o.hypothesis.span.text,
                        )
                        if o.hypothesis.HasField("span")
                        else None
                    ),
                    ref=o.hypothesis.ref,
                    correction=o.hypothesis.correction,
                    proposing_rung=o.hypothesis.proposing_rung,
                ),
                accepted=o.accepted,
                binding=binding_from_proto(o.binding) if o.accepted else None,
                reason=o.reason,
            )
            for o in resp.outcomes
        ],
    )
    log_gate(
        hypotheses_in=len(result.outcomes),
        survived=sum(1 for o in result.outcomes if o.accepted),
        retryable=sum(1 for o in result.outcomes if o.is_infrastructure_failure()),
    )
    return result


class LiveGate:
    """The real `Gate` rpc (GatePort)."""

    def __init__(self, target: str = DEFAULT_TARGET, timeout_s: float = 30.0):
        self.target = target
        self.timeout_s = timeout_s

    async def gate(
        self, *, lattice: ResolutionState, hypotheses: Sequence[Hypothesis]
    ) -> GateResult:
        req = resolver_pb2.GateRequest(
            lattice=lattice_to_proto(lattice),
            hypotheses=[_hypothesis_to_proto(h) for h in hypotheses],
        )
        async with grpc.aio.insecure_channel(self.target) as channel:
            stub = resolver_pb2_grpc.ResolverServiceStub(channel)
            resp = await stub.Gate(req, timeout=self.timeout_s)
        return result_from_proto(resp)


def apply_gate_result(state: ResolutionState, result: GateResult) -> None:
    """Fold a gate response into the lattice.

    `updated_gaps` REPLACES the gap list — the response is "the gaps recomputed after
    gating", the caller's next input, not a delta to apply.

    A gated binding is attached through its OUTCOME, not by guessing: `Binding` carries
    no span, so the only honest route from a binding to a mention is the hypothesis it
    came from (`outcomes[].hypothesis.span`). A binding whose span matches no mention is
    DROPPED — inventing a mention to hang it on would put a span in the lattice that the
    core never saw.
    """
    for outcome in result.outcomes:
        if not outcome.accepted or outcome.binding is None or outcome.hypothesis.span is None:
            continue
        span = outcome.hypothesis.span
        target = next(
            (m for m in state.mentions if m.span.start == span.start and m.span.end == span.end),
            None,
        )
        if target is None:
            continue
        target.bindings.append(outcome.binding.model_copy(deep=True))
    # ⛑ COPIES, not the response's own objects. Found by the replay test: a lattice that
    # ALIASES a gate response shares mutable records with it, so the emit step's
    # disposition writes (`IGNORED`/`DEGRADED`) leaked back into the response — and a
    # second delivery of the same resume then read gaps that were already closed and
    # carried nothing. Determinism under replay is only real if the fold copies.
    state.gaps = [gap.model_copy(deep=True) for gap in result.updated_gaps]
    if result.rung_log_entry is not None:
        state.rung_log.append(result.rung_log_entry.model_copy(deep=True))
