# SPDX-License-Identifier: Apache-2.0
"""The enrichment cascade (LM-14, NLS-P9.2) — guesser, LLM leg, and the decision.

`morph-studio` is the service around this package, not the place the thinking
happens. Everything here is importable and testable without a database, a
FastAPI app or a network: the guesser is a pure function of the vzor tables, the
cascade is a pure function of its legs' proposals, and the LLM leg is the one
piece that touches a socket — behind a transport that can be handed a fake.

That split is deliberate. The cascade's rules — which proposal wins, when a
proposal may auto-validate, whether a word belongs to a world or to the core —
are the parts a reviewer argues about, and arguing about them should not require
standing up Postgres.
"""

from ttrmorph.enrich.cascade import (
    LAYER_CORE,
    LAYER_WORLD,
    STATUS_AUTO_VALIDATED,
    STATUS_PROPOSED,
    TIER_GUESSER,
    TIER_HUMAN,
    TIER_LLM,
    CascadeResult,
    route,
    run_cascade,
)
from ttrmorph.enrich.guesser import (
    AUTO_VALIDATE_CONFIDENCE,
    SOURCE_GUESSER,
    Proposal,
    guess,
    validates,
)
from ttrmorph.enrich.llm import (
    SOURCE_LLM,
    LlmLeg,
    LlmUnavailable,
)

__all__ = [
    "AUTO_VALIDATE_CONFIDENCE",
    "LAYER_CORE",
    "LAYER_WORLD",
    "SOURCE_GUESSER",
    "SOURCE_LLM",
    "STATUS_AUTO_VALIDATED",
    "STATUS_PROPOSED",
    "TIER_GUESSER",
    "TIER_HUMAN",
    "TIER_LLM",
    "CascadeResult",
    "LlmLeg",
    "LlmUnavailable",
    "Proposal",
    "guess",
    "route",
    "run_cascade",
    "validates",
]
