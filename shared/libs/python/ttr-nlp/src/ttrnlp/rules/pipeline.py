# SPDX-License-Identifier: Apache-2.0
"""Phase running, and the `input:` visibility device.

**`input:` is the whole of visibility, and it is one line of code.** A phase
sees exactly the annotation types it lists; everything else is invisible and
matched *across*. That single filter is what makes ``{Lookup}{Lookup}`` match
with Tokens in between, because PAMPAC's ``Ann`` matches the *next* annotation
in the list it is handed and will not skip a non-matching one. Filter the list,
and the gap-skipping falls out. Filter anywhere else as well, and the two
mechanisms drift.

Phases run in order and each re-reads the annotation set, so a phase can consume
what an earlier one produced — which is the point of having phases at all, and
the reason the filter is applied per phase rather than once per document.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field

from ttrnlp.rules.actions import apply_firings
from ttrnlp.rules.compiler import CompiledPack, CompiledPhase
from ttrnlp.rules.executor import run_rules

#: `kind` for the trace entries this module produces (contracts §2.2).
KIND_RULES = "rules"


@dataclass(frozen=True)
class PhaseTrace:
    """One executed phase. Mirrors the proto ``PhaseTrace`` (contracts §2.2)."""

    phase: str
    kind: str
    annotations_added: int
    elapsed_ms: int
    firings: int = 0


@dataclass
class PhaseReport:
    traces: list[PhaseTrace] = field(default_factory=list)

    @property
    def annotations_added(self) -> int:
        return sum(trace.annotations_added for trace in self.traces)

    @property
    def firings(self) -> int:
        return sum(trace.firings for trace in self.traces)

    def trace(self, phase: str) -> PhaseTrace | None:
        return next((t for t in self.traces if t.phase == phase), None)


def visible_annotations(doc, phase: CompiledPhase, *, annset_name: str = ""):
    """The annotations a phase may see — its `input:` types, in document order.

    This is the JAPE ``Input:`` device. Returning a list rather than a set is
    deliberate: PAMPAC walks it positionally, and the order it is given in is
    the order matching happens in.
    """
    annset = doc.annset(annset_name)
    return list(annset.with_type(*phase.input))


def run_phase(doc, phase: CompiledPhase, *, annset_name: str = "") -> PhaseTrace:
    """Run one phase over a document, writing whatever its rules add."""
    started = time.perf_counter()
    annotations = visible_annotations(doc, phase, annset_name=annset_name)
    firings = run_rules(doc, annotations, phase.rules, phase.control)
    added = apply_firings(doc, firings, working_set=annset_name)
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    return PhaseTrace(
        phase=phase.phase,
        kind=KIND_RULES,
        annotations_added=len(added),
        elapsed_ms=elapsed_ms,
        firings=len(firings),
    )


def run_phases(
    doc,
    packs: list[CompiledPack],
    *,
    phases: list[str] | None = None,
    annset_name: str = "",
) -> PhaseReport:
    """Run phases over one document.

    Args:
        doc: The document. Mutated — rules add annotations to it.
        packs: Compiled packs. Phases run in file order within a pack, and packs
            in the order given: the caller's list order IS the cross-pack order,
            because nothing in a pack can know about another one.
        phases: Optional allow-list of phase names. Names not found are ignored
            rather than raising — a pipeline that names a phase no loaded pack
            provides is caught at boot by ``NLS-PACK-004``, not here.
        annset_name: The working set. Rules read from it and, unless an action
            names another set, write back to it.

    Returns:
        A ``PhaseReport`` with one trace per executed phase.
    """
    wanted = set(phases) if phases is not None else None
    report = PhaseReport()
    for pack in packs:
        for phase in pack.phases:
            if wanted is not None and phase.phase not in wanted:
                continue
            report.traces.append(run_phase(doc, phase, annset_name=annset_name))
    return report


__all__ = [
    "KIND_RULES",
    "PhaseReport",
    "PhaseTrace",
    "run_phase",
    "run_phases",
    "visible_annotations",
]
