# SPDX-License-Identifier: Apache-2.0
"""The ``LM-MORPH-*`` diagnostic family (LM contracts §9).

These *extend* the ``NLS-PACK-*`` family rather than starting a second one, so
the `Diagnostic` shape is imported from `ttrnlp.packs.diag` instead of being
redefined here. That is the load-bearing part: the proto ``PackDiagnostic`` has
exactly those five fields, and the service reports morph load failures through
the same ``GetStatus`` / ``ReloadPacks`` surfaces it reports pack failures
through. A morph-shaped diagnostic would have to be translated at the boundary,
and an operator would be reading two dialects of the same message.

=================  ============================================================
``LM-MORPH-001``   snapshot header invalid, row malformed, or content-hash
                   mismatch — the artifact cannot be trusted at all
``LM-MORPH-002``   an overlay row shadows a core entry (INFO, per entry). Not
                   an error: shadowing is what world layers are *for*. It is
                   reported because a world quietly overriding curated core
                   vocabulary is the first thing to check when the core's own
                   acceptance cases start failing in one deployment only
``LM-MORPH-003``   a provisional row outside a world overlay (ERROR). Q-7 is
                   narrow on purpose: unverified generated forms may serve in a
                   world, never in the published core artifact
``LM-MORPH-004``   compile — a share-alike layer carries non-wiktionary/cac
                   provenance (`ttr-morph`)
``LM-MORPH-005``   compile — a vzor entry regenerates something other than its
                   declared forms (`ttr-morph`)
``LM-MORPH-006``   annotate — the statistical seam answered (INFO)
``LM-MORPH-007``   ReportToken — queue sink disabled or world unknown
=================  ============================================================

004 and 005 are compile-time codes and live in `ttr-morph`; they are listed
here because the table is the contract and splitting it across two packages is
how half a table gets forgotten.
"""

from __future__ import annotations

from ttrnlp.packs.diag import (
    SEVERITY_ERROR,
    SEVERITY_INFO,
    Diagnostic,
    error,
    info,
)

LM_MORPH_001 = "LM-MORPH-001"  # header / row / content-hash invalid
LM_MORPH_002 = "LM-MORPH-002"  # overlay shadows a core entry (INFO)
LM_MORPH_003 = "LM-MORPH-003"  # provisional row outside a world overlay
LM_MORPH_004 = "LM-MORPH-004"  # share-alike layer, wrong provenance (compile)
LM_MORPH_005 = "LM-MORPH-005"  # vzor entry regenerates != declared (compile)
LM_MORPH_006 = "LM-MORPH-006"  # statistical seam answered (INFO)
LM_MORPH_007 = "LM-MORPH-007"  # queue sink disabled / world unknown

#: Every code in the family, for the round-trip tests and the CLI's help.
LM_MORPH_CODES = (
    LM_MORPH_001,
    LM_MORPH_002,
    LM_MORPH_003,
    LM_MORPH_004,
    LM_MORPH_005,
    LM_MORPH_006,
    LM_MORPH_007,
)

__all__ = [
    "LM_MORPH_001",
    "LM_MORPH_002",
    "LM_MORPH_003",
    "LM_MORPH_004",
    "LM_MORPH_005",
    "LM_MORPH_006",
    "LM_MORPH_007",
    "LM_MORPH_CODES",
    "SEVERITY_ERROR",
    "SEVERITY_INFO",
    "Diagnostic",
    "error",
    "info",
]
