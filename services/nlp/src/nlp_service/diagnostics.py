# SPDX-License-Identifier: Apache-2.0
"""`RG-NLP-*` / `NLS-*` diagnostics for nlp (contracts §8).

The Python counterpart of the Kotlin `diagnostics` registry (RG-P0.S3):
named, stable, fixture-backed codes. Emitted onto responses as
`ResponseMessage`-shaped dicts (severity/code/human_message) via `message()`.

NLS-P3.2 adds the codes the pipeline surface needs. `NLS-NLP-011` is the one that
matters: it is the *explicit* half of the NL-14 degrade. An op the active lane
cannot route is skipped and every other phase still runs — but the response says
so, by name, on the Rule-6 message slot. A degrade nobody is told about is
indistinguishable from a pack that failed to match, and the two have completely
different fixes.
"""

from __future__ import annotations

from typing import Dict

# error — no engine backend reachable for a routed (lang, op) at startup
RG_NLP_001 = "RG-NLP-001"
# warning — route points at a REMOTE_UNPINNED (Lindat) tier (non-conformant)
RG_NLP_002 = "RG-NLP-002"
# error — backend launched/responded without an explicit model id (S-1)
RG_NLP_003 = "RG-NLP-003"
# info — unsupported (lang, op): degrade floor applied (tokenize+fold+langid)
RG_NLP_010 = "RG-NLP-010"

# ── NLS-P3.2 (NLS contracts §8) ──────────────────────────────────────────────
# warning — an op the ACTIVE LANE does not route (default-lane `NER.cs`): skipped,
# every other phase still ran. The explicit half of the NL-14 degrade.
NLS_NLP_011 = "NLS-NLP-011"
# error — a ReloadPacks was refused; the previous snapshot is still serving
NLS_PACK_010 = "NLS-PACK-010"
# info — ReportToken arrived but the morph queue sink is not wired (LM, NLS-P9)
LM_MORPH_007 = "LM-MORPH-007"

_SEVERITY: Dict[str, str] = {
    RG_NLP_001: "ERROR",
    RG_NLP_002: "WARNING",
    RG_NLP_003: "ERROR",
    RG_NLP_010: "INFO",
    NLS_NLP_011: "WARNING",
    NLS_PACK_010: "ERROR",
    LM_MORPH_007: "INFO",
}

_MEANING: Dict[str, str] = {
    RG_NLP_001: "no engine backend reachable for a routed (language, op)",
    RG_NLP_002: "route points at a REMOTE_UNPINNED (Lindat) tier — non-conformant for parity/determinism",
    RG_NLP_003: "backend has no explicit model id (S-1 violation)",
    RG_NLP_010: "unsupported (language, op) — degrade floor applied (tokenize+fold+langid)",
    NLS_NLP_011: "op not routed in the active lane — skipped; the remaining phases still ran",
    NLS_PACK_010: "reload refused — the previous pack snapshot is still serving",
    LM_MORPH_007: "token report accepted=false — the morph queue sink is not configured",
}


def severity(code: str) -> str:
    return _SEVERITY.get(code, "INFO")


def meaning(code: str) -> str:
    """The registry's own wording for a code, unadorned.

    Callers that build a diagnostic in a different shape (the wheel's
    `Diagnostic`, for a `ReloadPacks` response) need the text without reaching
    into `_MEANING` — one registry, one wording, wherever it surfaces.
    """
    return _MEANING.get(code, code)


def message(code: str, detail: str = "") -> dict:
    """Build a ResponseMessage-shaped dict for `code`, optionally suffixing
    `detail` (e.g. the offending language/op)."""
    text = _MEANING.get(code, code)
    if detail:
        text = f"{text}: {detail}"
    return {"severity": severity(code), "code": code, "message": text}
