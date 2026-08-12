# SPDX-License-Identifier: Apache-2.0
"""Which (language, op) pairs emulation is serving — and what that excludes.

RV-6: a conformance claim must never be made from emulated output. The claim a
conformance case makes is *this input produces this analysis*, and emulation
makes that conditional on a hosted model nobody pins — so a case whose asserted
ops are routed at `llm_emulated` has to be **named as skipped**, never silently
passed and never quietly failed.

The predicate reads the same resolved routing the requests use, so a case is
excluded because the route says so, not because a second list of ops agreed with
it. Both halves come from one registry.

⚠ **What this does NOT need to protect, verified rather than assumed.** The
gating conformance tier (`just conformance-service-level`) is Kotlin and
hermetic: `MatchQualityCorpusTest` and its siblings run against a deterministic
fixture `Lemmatizer` *with no nlp dependency at all*, and the resolver's tiers
fake nlp outright. No routing config can reach them, so emulation cannot change
their results — the plan's "conformance suite unaffected when emulation is on" is
true there **by construction**, and an exclusion predicate wired into that runner
would be dead code claiming to guard something. What does read live engine
output is the eval harness (`eval/run_eval.py`), and that is where this is wired.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Mapping

from nlp_service.engines.base import NlpOp
from nlp_service.engines.llm_emulated_engine import EMULATED_ENGINE_NAME


@dataclass(frozen=True)
class Exclusion:
    """One case dropped, with the reason a report has to print."""

    case_id: str
    reason: str


def emulated_routes(capability_rows: Iterable[Mapping]) -> set[tuple[str, str]]:
    """`{(language, op)}` the capability matrix says emulation serves.

    Taken from the matrix rather than from the routing table, because the matrix
    is what a caller can see: a table entry whose engine is not registered does
    not produce a row, and a case must be excluded on what actually served it.
    """
    return {
        (row["language"], _op_name(row["op"]))
        for row in capability_rows
        if row["engine"] == EMULATED_ENGINE_NAME
    }


def exclusions(
    *,
    cases: Iterable[tuple[str, str, Iterable[str]]],
    emulated: set[tuple[str, str]],
) -> list[Exclusion]:
    """Which of `cases` — `(id, language, asserted_ops)` — must be skipped.

    A case is excluded when ANY op it asserts is emulated for its language: the
    case's verdict is a conjunction, so one emulated op is enough to make the
    whole pass-or-fail meaningless. Excluding on "all of them" would let a case
    asserting lemmas AND entities pass on the strength of the lemmas.
    """
    out: list[Exclusion] = []
    for case_id, language, asserted in cases:
        hit = sorted({op for op in asserted if (language, _op_name(op)) in emulated})
        if hit:
            out.append(
                Exclusion(
                    case_id=case_id,
                    reason=f"emulated: {'/'.join(hit)}/{language}",
                )
            )
    return out


def _op_name(op: object) -> str:
    return op.value if isinstance(op, NlpOp) else str(op)


__all__ = ["Exclusion", "emulated_routes", "exclusions"]
