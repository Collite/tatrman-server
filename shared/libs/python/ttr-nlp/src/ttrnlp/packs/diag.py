# SPDX-License-Identifier: Apache-2.0
"""Diagnostics — the one shape everything reports failures in.

The CLI, the pack loader, the service's boot-time load and ``ReloadPacks`` all
emit these, and the proto ``PackDiagnostic`` (contracts §2.3) has exactly these
five fields. Keeping the dataclass field-for-field identical to the wire message
is deliberate: a pack author reading a CLI error and an operator reading a
``ReloadPacks`` response are looking at the same text, and neither has to
translate.

Codes are contracts §8:

===================  ===========================================================
``NLS-PACK-001``     the pack does not parse — schema violation, or a structural
                     rule the schema cannot express (``repeat.min > max``)
``NLS-PACK-002``     the pack parses but cannot compile — a type not in the
                     phase's ``input:``, an RHS reference to an unbound name, a
                     duplicated ``bind:``
``NLS-PACK-003``     a list file is invalid
``NLS-PACK-004``     a pipeline references a pack, phase or list that is not there
``NLS-PACK-005``     CLI ``--model`` only: a query id or parameter name the model
                     does not declare
``NLS-PACK-010``     a reload was refused; the previous snapshot still serves
===================  ===========================================================

Why 001 covers the model-validator failures too: from a pack author's side there
is no interesting difference between "the schema rejected this" and "the schema
could not express it, so the loader did" — both mean the file is malformed and
neither can be reached by a rule at runtime. 002 is genuinely different: the
file is well-formed and every error in it is about how the parts refer to each
other.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

SEVERITY_INFO = "INFO"
SEVERITY_ERROR = "ERROR"

NLS_PACK_001 = "NLS-PACK-001"  # schema / parse
NLS_PACK_002 = "NLS-PACK-002"  # compile-time cross-checks
NLS_PACK_003 = "NLS-PACK-003"  # list file invalid
NLS_PACK_004 = "NLS-PACK-004"  # pipeline reference unresolved
NLS_PACK_005 = "NLS-PACK-005"  # model cross-check (CLI lane)
NLS_PACK_010 = "NLS-PACK-010"  # reload refused


@dataclass(frozen=True)
class Diagnostic:
    """One problem, in the shape the wire uses.

    ``message`` leads with the JSON path of the offending node (``$.phases[0].
    control``) because "which of the 300 lines" is the first question every
    reader has. The path is not a separate field: the proto has five fields and
    a sixth here would have to be dropped at the boundary, leaving the CLI and
    the service saying different things about the same pack.
    """

    severity: Literal["INFO", "ERROR"]
    code: str
    message: str
    source: str = ""
    pack: str = ""

    def __str__(self) -> str:
        where = self.pack or self.source or "?"
        return f"[{self.severity}] {self.code} {where}: {self.message}"


def error(code: str, message: str, *, source: str = "", pack: str = "") -> Diagnostic:
    return Diagnostic(
        severity=SEVERITY_ERROR, code=code, message=message, source=source, pack=pack
    )


def info(code: str, message: str, *, source: str = "", pack: str = "") -> Diagnostic:
    return Diagnostic(
        severity=SEVERITY_INFO, code=code, message=message, source=source, pack=pack
    )


class PackError(Exception):
    """A pack could not be loaded or compiled.

    Carries every diagnostic found, not just the first. A pack author fixing six
    typos should need one round trip, not six.
    """

    def __init__(self, diagnostics: list[Diagnostic]):
        self.diagnostics: list[Diagnostic] = list(diagnostics)
        summary = "; ".join(d.message for d in self.diagnostics)
        super().__init__(summary or "invalid pack")

    @property
    def codes(self) -> list[str]:
        return [d.code for d in self.diagnostics]


@dataclass
class DiagnosticCollector:
    """Accumulates diagnostics and raises once, at the end."""

    source: str = ""
    pack: str = ""
    items: list[Diagnostic] = field(default_factory=list)

    def error(self, code: str, message: str) -> None:
        self.items.append(error(code, message, source=self.source, pack=self.pack))

    def raise_if_any(self) -> None:
        if self.items:
            raise PackError(self.items)


__all__ = [
    "NLS_PACK_001",
    "NLS_PACK_002",
    "NLS_PACK_003",
    "NLS_PACK_004",
    "NLS_PACK_005",
    "NLS_PACK_010",
    "SEVERITY_ERROR",
    "SEVERITY_INFO",
    "Diagnostic",
    "DiagnosticCollector",
    "PackError",
    "error",
    "info",
]
