# SPDX-License-Identifier: Apache-2.0
"""Route resolution result (RG-P1.S1.T3).

A `Route` is the fully-resolved decision for one (language, op): which engine
serves it, the explicit model id + version it will echo (S-1), the pinning
`tier`, and any diagnostics attached (RG-NLP-002 for a Lindat tier, RG-NLP-010
for a degrade-floor fallback). `route.model` is **never empty** — the floor
names its deterministic in-front producer.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional, Tuple

from nlp_service.engines.base import NlpEngine, NlpOp

# The degrade floor's producer identity (S-1 holds even when no backend serves
# the op): deterministic in-front tokenize + S-2 fold + langid.
FLOOR_ENGINE = "floor"
FLOOR_MODEL = "tokenize+fold+langid"
FLOOR_MODEL_VERSION = "s2"


@dataclass(frozen=True)
class Route:
    op: NlpOp
    language: str
    engine: str
    model: str
    model_version: str
    tier: str
    adapter: Optional[NlpEngine] = None
    is_floor: bool = False
    info: Tuple[str, ...] = field(default_factory=tuple)
