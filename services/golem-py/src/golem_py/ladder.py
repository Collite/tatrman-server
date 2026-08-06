# SPDX-License-Identifier: Apache-2.0
"""`golem-ladder/v1` — the ladder / gap-policy config (contracts §3, RV-10/12).

ONE gap-policy table (RV-10) over a rung vocabulary of FOUR (RV-33). The shipped
default is the full SHAPE with `policy.*.rungs: []` (RV-27): an out-of-the-box open
estate proposes nothing, asks once about a load-bearing gap if it has one, and
otherwise refuses honestly with the lattice attached. Enabling a rung is a deliberate,
per-estate act — and the shape being present is what makes it a config change rather
than a code change.

Both Golems read this schema (the Kotlin one at RV-P5), so the file is the contract:
the fixtures under `config/` are shared, not copied.

⚑ RULED at P4.1·T4 — how a profile selects its terminal posture. contracts §3 gives
`terminal: {human_profiles: best-effort-with-gap-notes, strict: refusal-with-gaps}`
but never says which profile is which. Ruled here, recorded on the task list for
Bora: a profile may name its posture with `terminal: strict | human_profiles`, and the
**default is `strict`**. Rationale: the open Golem's whole posture is refuse-over-guess
(H4), so the safe default is the one that refuses; best-effort is an estate opting IN
to answering over gap notes. A profile naming a posture the `terminal:` block does not
define is a config error, not a fallback.
"""

from __future__ import annotations

from enum import StrEnum
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator

from golem_py.state import GapKind

SCHEMA_ID = "golem-ladder/v1"

# RV-33 — the rung vocabulary is CLOSED at four. `lookup` is deterministic (anchored,
# category-scoped) and is the only one that is not an LLM rung: it is bounded by the
# ladder's wall clock and never counts against `max_llm_invocations`.
RUNG_NAMES = ("lookup", "local", "capable", "emulated")
DETERMINISTIC_RUNGS = frozenset({"lookup"})


class ConfigError(ValueError):
    """A ladder config that cannot be trusted. Raised at LOAD, never at run time — a
    malformed ladder must not be discovered halfway through a conversation."""


class AskPolicy(StrEnum):
    ESCALATE_THEN_ASK = "escalate-then-ask"
    EMIT_UNLESS_LOAD_BEARING = "emit-unless-load-bearing"
    ASK_IF_LOAD_BEARING = "ask-if-load-bearing"
    DEGRADE_BANNER = "degrade-banner"
    ASK_IMMEDIATELY = "ask-immediately"


class TerminalPosture(StrEnum):
    REFUSAL_WITH_GAPS = "refusal-with-gaps"
    BEST_EFFORT_WITH_GAP_NOTES = "best-effort-with-gap-notes"


class RungDef(BaseModel):
    """A rung DEFINITION (contracts §3: definitions, not instances)."""

    model_config = ConfigDict(extra="forbid")

    model_class: str = ""
    timeout_ms: int | None = None  # falls back to `rung_timeouts_ms`
    temperature: float = 0.0  # RV-12: temp 0 everywhere
    last_resort: bool = False


class GapPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")

    rungs: list[str] = Field(default_factory=list)
    ask: AskPolicy = AskPolicy.ASK_IF_LOAD_BEARING


class Profile(BaseModel):
    model_config = ConfigDict(extra="forbid")

    rungs_allowed: list[str] | str = Field(default_factory=list)  # list | "*"
    max_llm_invocations: int = 0
    ladder_budget_ms: int = 0
    hitl_rounds: int = 0
    terminal: str = "strict"  # ⚑ see the module docstring

    @field_validator("max_llm_invocations", "ladder_budget_ms", "hitl_rounds")
    @classmethod
    def _non_negative(cls, v: int) -> int:
        if v < 0:
            raise ValueError("budgets cannot be negative")
        return v

    def allows(self, rung: str) -> bool:
        if self.rungs_allowed == "*":
            return True
        return rung in self.rungs_allowed


class LadderConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    # `schema` is the YAML key; `schema_` the attribute (BaseModel.schema is taken).
    schema_: str = Field(default=SCHEMA_ID, alias="schema")
    rungs: dict[str, RungDef] = Field(default_factory=dict)
    policy: dict[GapKind, GapPolicy] = Field(default_factory=dict)
    rung_timeouts_ms: dict[str, int] = Field(default_factory=dict)
    profiles: dict[str, Profile] = Field(default_factory=dict)
    terminal: dict[str, TerminalPosture] = Field(default_factory=dict)

    # ---------------------------------------------------------------- validation

    @model_validator(mode="after")
    def _check(self) -> LadderConfig:
        if self.schema_ != SCHEMA_ID:
            raise ValueError(f"unknown schema id {self.schema_!r} (expected {SCHEMA_ID!r})")

        unknown_rungs = sorted(set(self.rungs) - set(RUNG_NAMES))
        if unknown_rungs:
            raise ValueError(
                f"unknown rung(s) {unknown_rungs} — the vocabulary is closed at "
                f"{list(RUNG_NAMES)} (RV-33)"
            )
        for name, ms in self.rung_timeouts_ms.items():
            if name not in RUNG_NAMES:
                raise ValueError(f"rung_timeouts_ms names unknown rung {name!r}")
            if ms <= 0:
                raise ValueError(f"rung_timeouts_ms[{name}] must be > 0")
        for rung_name, definition in self.rungs.items():
            if definition.timeout_ms is not None and definition.timeout_ms <= 0:
                raise ValueError(f"rungs[{rung_name}].timeout_ms must be > 0")

        for kind, pol in self.policy.items():
            for rung in pol.rungs:
                if rung not in self.rungs:
                    raise ValueError(
                        f"policy[{kind.value}] names rung {rung!r}, which is not defined "
                        f"under `rungs:`"
                    )
        for prof_name, prof in self.profiles.items():
            if prof.rungs_allowed != "*":
                allowed = prof.rungs_allowed
                assert isinstance(allowed, list)
                for rung in allowed:
                    if rung not in self.rungs:
                        raise ValueError(
                            f"profile {prof_name!r} allows rung {rung!r}, which is not "
                            f"defined under `rungs:`"
                        )
            if prof.terminal not in self.terminal:
                raise ValueError(
                    f"profile {prof_name!r} names terminal posture {prof.terminal!r}, "
                    f"which `terminal:` does not define"
                )
        return self

    # ------------------------------------------------------------------ loading

    @classmethod
    def load(cls, path: str | Path) -> LadderConfig:
        raw: Any = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise ConfigError(f"{path}: not a YAML mapping")
        try:
            return cls.model_validate(raw)
        except ValidationError as exc:  # one error type at the boundary
            raise ConfigError(f"{path}: {exc}") from exc

    # ------------------------------------------------------------------ queries

    def profile(self, name: str) -> Profile:
        try:
            return self.profiles[name]
        except KeyError as exc:
            raise ConfigError(f"unknown profile {name!r}") from exc

    def gap_policy(self, kind: GapKind) -> GapPolicy:
        """A gap kind with no policy row gets the conservative default: no rung may
        run, and an ask fires only if the gap is load-bearing."""
        return self.policy.get(kind, GapPolicy())

    def timeout_ms(self, rung: str) -> int:
        defined = self.rungs.get(rung)
        if defined is not None and defined.timeout_ms is not None:
            return defined.timeout_ms
        return self.rung_timeouts_ms.get(rung, 0)

    def eligible_rungs(self, kinds: set[GapKind], profile_name: str) -> list[str]:
        """The rungs that MAY run for this set of open gap kinds, in policy order.

        Zero-rung default ⇒ empty, and that emptiness is the point: the shape is
        present, nothing is on.
        """
        prof = self.profile(profile_name)
        out: list[str] = []
        for kind in kinds:
            for rung in self.gap_policy(kind).rungs:
                if rung not in out and prof.allows(rung):
                    out.append(rung)
        return out

    def terminal_posture(self, profile_name: str) -> TerminalPosture:
        return self.terminal[self.profile(profile_name).terminal]


DEFAULT_CONFIG_PATH = Path(__file__).resolve().parents[2] / "config" / "golem-ladder.yaml"


def load_default() -> LadderConfig:
    """The SHIPPED default (RV-27 zero-rung), overridable by `GOLEM_LADDER_CONFIG`."""
    import os

    return LadderConfig.load(os.getenv("GOLEM_LADDER_CONFIG") or DEFAULT_CONFIG_PATH)
