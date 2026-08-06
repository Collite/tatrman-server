# SPDX-License-Identifier: Apache-2.0
"""Service settings, and the ONE inequality that must hold before the service starts.

    snapshot_ttl_s  ≥  core_token_max_age_s

⚑ P4.2·T3 names this "the likely first production bug", in advance, because the two
numbers live in different processes and neither side can see the other's. The core's
`resolver.resume-token.max-age-seconds` bounds how long its signed option set may be
replayed (default **3600 s**, and a MISSING key falls back to that default rather than
meaning "eternal" — RG-P6 review K). Our snapshot TTL bounds how long we keep the state
that token belongs to.

If ours is SHORTER, a user answering inside the window the core still honours gets
"that question expired" from us — a self-inflicted failure that looks like a core bug
and reproduces only under load, when snapshots are swept sooner. So the inequality is
validated at STARTUP and a violating config refuses to boot, which is the same posture
the resolver takes about an empty key set.

The value is deliberately NOT inferred from the core: asking it would make the check a
round trip that can fail, and a config that disagrees with reality is exactly what
should stop a deployment.
"""

from __future__ import annotations

import os

from pydantic import BaseModel, ConfigDict, model_validator

from golem_py.errors import ConfigInvalid

# The resolver's own default (`DEFAULT_RESUME_MAX_AGE_SECONDS`, Application.kt).
DEFAULT_CORE_TOKEN_MAX_AGE_S = 3600


class GolemSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")

    core_target: str = "localhost:7276"
    # What the CORE will still honour. Mirror of its config, not a wish.
    core_token_max_age_s: int = DEFAULT_CORE_TOKEN_MAX_AGE_S
    # What WE will still hold. Must cover the core's window.
    snapshot_ttl_s: int = DEFAULT_CORE_TOKEN_MAX_AGE_S
    snapshot_dir: str = ""  # empty ⇒ the in-memory store (tests, single-shot CLI)
    profile: str = "CHAT_QUICK"

    @model_validator(mode="after")
    def _ttl_covers_the_token(self) -> GolemSettings:
        if self.snapshot_ttl_s < self.core_token_max_age_s:
            raise ValueError(
                f"snapshot_ttl_s ({self.snapshot_ttl_s}s) is shorter than the core's "
                f"resume-token max age ({self.core_token_max_age_s}s): a user answering "
                "inside the window the core still honours would be told their question "
                "expired. Raise the snapshot TTL, or lower the core's max-age to match."
            )
        if self.snapshot_ttl_s <= 0 or self.core_token_max_age_s <= 0:
            raise ValueError("TTLs must be positive")
        return self

    @classmethod
    def from_env(cls) -> GolemSettings:
        """`GOLEM_*` env vars, validated. Raises `ConfigInvalid` at STARTUP."""

        def _int(name: str, default: int) -> int:
            raw = os.getenv(name)
            return default if raw is None or raw == "" else int(raw)

        try:
            return cls(
                core_target=os.getenv("GOLEM_CORE_TARGET") or "localhost:7276",
                core_token_max_age_s=_int(
                    "GOLEM_CORE_TOKEN_MAX_AGE_S", DEFAULT_CORE_TOKEN_MAX_AGE_S
                ),
                snapshot_ttl_s=_int("GOLEM_SNAPSHOT_TTL_S", DEFAULT_CORE_TOKEN_MAX_AGE_S),
                snapshot_dir=os.getenv("GOLEM_SNAPSHOT_DIR") or "",
                profile=os.getenv("GOLEM_PROFILE") or "CHAT_QUICK",
            )
        except ValueError as exc:
            raise ConfigInvalid(str(exc)) from exc
