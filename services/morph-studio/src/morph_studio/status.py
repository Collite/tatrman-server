# SPDX-License-Identifier: Apache-2.0
"""The LM-14 status machine (contracts §8; NLS-P9.2 T2/T3).

```
proposed ──▶ auto-validated ──▶ verified ──▶ published
    │              │               │             │
    └──────────────┴───────────────┴─────────────┴──▶ rejected   (terminal)
                                    │             │
                                    └─────────────┴──▶ shadowed ──▶ published
```

**Why a table and not a set of `if`s.** Every edge here is a claim about the
lexicon, and the illegal ones are the interesting half: `proposed → published`
would put an unreviewed generated paradigm into an artifact that ships to every
deployment, which is the single thing the export gate exists to prevent. Written
as conditionals scattered across endpoints, that edge is missing by nobody's
decision; written as a table, its absence is the decision.

**`rejected` is terminal, and that is a feature of the queue.** A reviewer who
rejects `%%%` — or a real word this world does not want — has said so, and the
front will keep reporting that token every time somebody types it. Dedup on
`(world, token)` plus a terminal `rejected` is what turns one verdict into a
permanent answer. Re-opening it is a new decision a human makes explicitly
(`POST /queue/{id}/verdict` with `reopen`), not something an ingest can do by
seeing the word again.

**`shadowed` is not a step on the way anywhere.** It is what a core entry
becomes when a world's overlay covers it (contracts §8, `LM-MORPH-002`), and it
returns to `published` when the overlay goes. Its presence in the machine is the
reason `published` is not terminal.
"""

from __future__ import annotations

PROPOSED = "proposed"
AUTO_VALIDATED = "auto-validated"
VERIFIED = "verified"
PUBLISHED = "published"
REJECTED = "rejected"
SHADOWED = "shadowed"

#: Every status, in the order the machine reads in.
STATUSES = (PROPOSED, AUTO_VALIDATED, VERIFIED, PUBLISHED, REJECTED, SHADOWED)

#: The statuses `POST /export` emits. THE GATE (contracts §7): everything below
#: `verified` is working memory and does not leave the database.
EXPORTABLE = frozenset({VERIFIED, PUBLISHED, SHADOWED})

#: from -> the statuses it may become.
TRANSITIONS: dict[str, frozenset[str]] = {
    # The cascade validated it, a reviewer verified it outright, or it is wrong.
    PROPOSED: frozenset({AUTO_VALIDATED, VERIFIED, REJECTED}),
    # A human confirming is the ONLY way out of auto-validated and into export.
    AUTO_VALIDATED: frozenset({VERIFIED, REJECTED}),
    # Export publishes; a later look can still find it wrong; an overlay can
    # cover it.
    VERIFIED: frozenset({PUBLISHED, REJECTED, SHADOWED}),
    # Published is not terminal: retraction is a supported act, and being
    # shadowed by a world overlay is an ordinary state to be in.
    PUBLISHED: frozenset({REJECTED, SHADOWED}),
    SHADOWED: frozenset({PUBLISHED, REJECTED}),
    REJECTED: frozenset(),
}


class IllegalTransition(ValueError):
    """A status change the machine does not have. The API answers 409."""

    def __init__(self, current: str, wanted: str):
        self.current = current
        self.wanted = wanted
        allowed = sorted(TRANSITIONS.get(current, frozenset()))
        super().__init__(
            f"{current!r} cannot become {wanted!r}"
            + (f" — from {current!r} the machine allows {allowed}" if allowed else "")
            + (
                " (rejected is terminal: re-opening an entry is a decision a "
                "person makes, not something an ingest can do by seeing the "
                "word again)"
                if current == REJECTED
                else ""
            )
        )


class UnknownStatus(ValueError):
    """A status outside the machine. Never a 409 — the caller is wrong, not late."""


def check(current: str, wanted: str) -> None:
    """Raise unless ``current -> wanted`` is an edge of the machine."""
    if current not in TRANSITIONS:
        raise UnknownStatus(f"{current!r} is not one of {list(STATUSES)}")
    if wanted not in TRANSITIONS:
        raise UnknownStatus(f"{wanted!r} is not one of {list(STATUSES)}")
    if wanted not in TRANSITIONS[current]:
        raise IllegalTransition(current, wanted)


def can(current: str, wanted: str) -> bool:
    """The same question, without the exception — for building a UI's buttons."""
    return wanted in TRANSITIONS.get(current, frozenset())


def exportable(status: str) -> bool:
    """Does an entry at this status leave the database? (The gate.)"""
    return status in EXPORTABLE


__all__ = [
    "AUTO_VALIDATED",
    "EXPORTABLE",
    "PROPOSED",
    "PUBLISHED",
    "REJECTED",
    "SHADOWED",
    "STATUSES",
    "TRANSITIONS",
    "VERIFIED",
    "IllegalTransition",
    "UnknownStatus",
    "can",
    "check",
    "exportable",
]
