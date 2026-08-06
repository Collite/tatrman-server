# SPDX-License-Identifier: Apache-2.0
"""Structured logging for the loop (P4.1·T7).

One line per node and one per turn, key=value, so a conversation can be reconstructed
from logs alone: which node ran, what the verdict was, how many gaps were open, how
much of each budget was spent. The two numbers P2.4's review asked for —
**hypotheses in / survived the gate** — are logged per gate call in `gate_client.py`,
because they are the only honest measure of whether a rung is worth its latency.

⚑ OTEL is deliberately NOT wired here. The resolver's own span is inert until a Python
SDK lands in this repo's conventions, and half-wiring a tracer would make the trace
look complete while carrying nothing. Same posture, its own task later.
"""

from __future__ import annotations

import logging
from typing import Any

_LOGGER = logging.getLogger("golem_py")


def _fmt(fields: dict[str, Any]) -> str:
    return " ".join(f"{k}={v}" for k, v in fields.items() if v not in (None, ""))


def log_node(state: Any, node: str, **fields: Any) -> None:
    _LOGGER.info(
        "node %s",
        _fmt(
            {
                "node": node,
                "conversation": state.conversation_id,
                "turn": state.turn_id,
                "trace": state.trace_id,
                **fields,
            }
        ),
    )


def log_turn(state: Any, outcome: str, **fields: Any) -> None:
    _LOGGER.info(
        "turn %s",
        _fmt(
            {
                "outcome": outcome,
                "conversation": state.conversation_id,
                "turn": state.turn_id,
                "gaps_open": len(state.open_gaps()),
                "llm_invocations": state.llm_invocations,
                "hitl_rounds": state.hitl_rounds,
                "rungs_run": ",".join(state.rungs_run),
                **fields,
            }
        ),
    )


def log_gate(*, hypotheses_in: int, survived: int, **fields: Any) -> None:
    """The two ladder health numbers (P2.4's finding). A rung proposing 20 hypotheses
    of which 0 survive is a rung to turn off, and nothing else reports that."""
    _LOGGER.info(
        "gate %s",
        _fmt({"hypotheses_in": hypotheses_in, "survived": survived, **fields}),
    )
