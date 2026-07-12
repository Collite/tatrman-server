# SPDX-License-Identifier: Apache-2.0
"""S-1 invariant helpers (contracts §6) — model identity on the wire.

S-1: every model-touched response echoes `EngineVersion{engine, model,
model_version}`, and no code path selects a model by empty/default parameter.
These pure helpers let the response path (S1.T6) and the tests assert the
invariant without duplicating the rule. A violation is surfaced as `RG-NLP-003`
(backend launched / responded without an explicit model id).
"""

from __future__ import annotations

from typing import Iterable, Iterator, Protocol


class _EngineVersionLike(Protocol):
    op: str
    engine: str
    model: str
    model_version: str


def iter_s1_violations(used: Iterable[_EngineVersionLike]) -> Iterator[str]:
    """Yield a human-readable message per S-1 violation in `used`.

    Violations:
      * `used` is empty — no engine echoed at all.
      * any entry has a blank `model` — the empty/default-model bug class S-1
        exists to kill (RG-NLP-003).
    An empty iterator means the response is S-1-clean.
    """
    used = list(used)
    if not used:
        yield "S-1: used[] is empty (no engine+model echoed on the response)"
        return
    for ev in used:
        if not (ev.model or "").strip():
            engine = ev.engine or "<unknown-engine>"
            op = ev.op or "<unknown-op>"
            yield f"S-1: blank model for engine={engine!r} op={op!r} (RG-NLP-003)"


def is_s1_clean(used: Iterable[_EngineVersionLike]) -> bool:
    """True iff `used` satisfies S-1 (non-empty, every entry has a model)."""
    return not any(True for _ in iter_s1_violations(used))
