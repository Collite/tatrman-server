# SPDX-License-Identifier: Apache-2.0
"""`RG-NLP-*` diagnostics for ttr-nlp (contracts §8).

The Python counterpart of the Kotlin `ttr-diagnostics` registry (RG-P0.S3):
named, stable, fixture-backed codes. Emitted onto responses as
`ResponseMessage`-shaped dicts (severity/code/human_message) via `message()`.
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

_SEVERITY: Dict[str, str] = {
    RG_NLP_001: "ERROR",
    RG_NLP_002: "WARNING",
    RG_NLP_003: "ERROR",
    RG_NLP_010: "INFO",
}

_MEANING: Dict[str, str] = {
    RG_NLP_001: "no engine backend reachable for a routed (language, op)",
    RG_NLP_002: "route points at a REMOTE_UNPINNED (Lindat) tier — non-conformant for parity/determinism",
    RG_NLP_003: "backend has no explicit model id (S-1 violation)",
    RG_NLP_010: "unsupported (language, op) — degrade floor applied (tokenize+fold+langid)",
}


def severity(code: str) -> str:
    return _SEVERITY.get(code, "INFO")


def message(code: str, detail: str = "") -> dict:
    """Build a ResponseMessage-shaped dict for `code`, optionally suffixing
    `detail` (e.g. the offending language/op)."""
    text = _MEANING.get(code, code)
    if detail:
        text = f"{text}: {detail}"
    return {"severity": severity(code), "code": code, "message": text}
