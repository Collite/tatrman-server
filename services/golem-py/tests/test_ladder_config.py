# SPDX-License-Identifier: Apache-2.0
"""P4.1·T4 — `golem-ladder/v1`: the shipped default, and the rejection catalogue.

A ladder config is read once, at startup, and then governs every turn. So it fails at
LOAD or not at all — discovering a typo halfway through a conversation is the failure
mode these tests exist to prevent.
"""

from __future__ import annotations

from typing import Any

import pytest
import yaml

from golem_py.ladder import (
    DEFAULT_CONFIG_PATH,
    SCHEMA_ID,
    AskPolicy,
    ConfigError,
    LadderConfig,
    TerminalPosture,
    load_default,
)
from golem_py.state import GapKind


def _default_raw() -> dict[str, Any]:
    raw: dict[str, Any] = yaml.safe_load(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"))
    return raw


def _write(tmp_path, raw) -> str:  # type: ignore[no-untyped-def]
    path = tmp_path / "ladder.yaml"
    path.write_text(yaml.safe_dump(raw), encoding="utf-8")
    return str(path)


# ------------------------------------------------------------- the shipped default


def test_the_shipped_default_is_zero_rung_with_the_full_shape() -> None:
    """RV-27, asserted rather than asserted-in-a-comment: every rung the ladder knows
    is DEFINED, and no policy row admits one. That combination is what makes enabling a
    rung an estate's edit rather than a code change."""
    cfg = load_default()

    assert cfg.schema_ == SCHEMA_ID
    assert sorted(cfg.rungs) == ["capable", "emulated", "local", "lookup"]
    for kind, policy in cfg.policy.items():
        assert policy.rungs == [], f"{kind.value} admits a rung in the shipped default"
    assert cfg.eligible_rungs({GapKind.G1_UNBOUND}, "CHAT_QUICK") == []


def test_the_default_keeps_the_ask_column_live() -> None:
    """Zeroing the rungs must NOT zero the asks: asking is deterministic behaviour
    governed by RV-15, not escalation. An out-of-the-box estate still asks once."""
    cfg = load_default()

    assert cfg.gap_policy(GapKind.G1_UNBOUND).ask == AskPolicy.ESCALATE_THEN_ASK
    assert cfg.gap_policy(GapKind.G5_NLP_DARK).ask == AskPolicy.DEGRADE_BANNER
    assert cfg.profile("CHAT_QUICK").hitl_rounds == 1


def test_the_q14_budgets_are_the_ruled_ones() -> None:
    cfg = load_default()

    assert cfg.timeout_ms("lookup") == 250
    assert cfg.timeout_ms("local") == 3000
    assert cfg.timeout_ms("capable") == 10000
    assert cfg.timeout_ms("emulated") == 15000

    quick = cfg.profile("CHAT_QUICK")
    assert (quick.max_llm_invocations, quick.ladder_budget_ms, quick.hitl_rounds) == (2, 5000, 1)
    deep = cfg.profile("INVESTIGATION_DEEP")
    assert (deep.max_llm_invocations, deep.ladder_budget_ms, deep.hitl_rounds) == (6, 30000, 3)
    assert deep.rungs_allowed == "*" and deep.allows("emulated")


def test_the_default_file_round_trips() -> None:
    cfg = load_default()
    assert LadderConfig.model_validate(cfg.model_dump(by_alias=True)) == cfg


def test_both_shipped_profiles_refuse_rather_than_answer_over_a_gap() -> None:
    """⚑ The T4 ruling (recorded for Bora): contracts §3 names two terminal postures and
    never says which profile takes which. Default = `strict`, because the open Golem's
    posture is refuse-over-guess."""
    cfg = load_default()

    assert cfg.terminal_posture("CHAT_QUICK") == TerminalPosture.REFUSAL_WITH_GAPS
    assert cfg.terminal_posture("INVESTIGATION_DEEP") == TerminalPosture.REFUSAL_WITH_GAPS


# ---------------------------------------------------------- the rejection catalogue


def test_an_unknown_rung_is_refused(tmp_path) -> None:  # type: ignore[no-untyped-def]
    """RV-33: the vocabulary is CLOSED at four. A fifth rung is a design change, and a
    config that quietly accepts one would let an estate think it had enabled something."""
    raw = _default_raw()
    raw["rungs"]["frontier"] = {"timeout_ms": 1000}

    with pytest.raises(ConfigError, match="unknown rung"):
        LadderConfig.load(_write(tmp_path, raw))


def test_a_negative_budget_is_refused(tmp_path) -> None:  # type: ignore[no-untyped-def]
    raw = _default_raw()
    raw["profiles"]["CHAT_QUICK"]["max_llm_invocations"] = -1

    with pytest.raises(ConfigError, match="negative"):
        LadderConfig.load(_write(tmp_path, raw))


def test_a_non_positive_timeout_is_refused(tmp_path) -> None:  # type: ignore[no-untyped-def]
    raw = _default_raw()
    raw["rung_timeouts_ms"]["local"] = 0

    with pytest.raises(ConfigError, match="must be > 0"):
        LadderConfig.load(_write(tmp_path, raw))


def test_an_unknown_gap_kind_is_refused(tmp_path) -> None:  # type: ignore[no-untyped-def]
    """The gap taxonomy is the contract between the core and the loop. A policy row for
    a gap kind the core cannot emit is dead config that reads as coverage."""
    raw = _default_raw()
    raw["policy"]["G7_VIBES"] = {"rungs": [], "ask": "ask-if-load-bearing"}

    with pytest.raises(ConfigError):
        LadderConfig.load(_write(tmp_path, raw))


def test_a_policy_naming_an_undefined_rung_is_refused(tmp_path) -> None:  # type: ignore[no-untyped-def]
    raw = _default_raw()
    del raw["rungs"]["capable"]
    raw["policy"]["G1_UNBOUND"]["rungs"] = ["capable"]

    with pytest.raises(ConfigError, match="not defined"):
        LadderConfig.load(_write(tmp_path, raw))


def test_a_profile_naming_an_undefined_rung_is_refused(tmp_path) -> None:  # type: ignore[no-untyped-def]
    raw = _default_raw()
    raw["profiles"]["CHAT_QUICK"]["rungs_allowed"] = ["lookup", "frontier"]

    with pytest.raises(ConfigError, match="not defined"):
        LadderConfig.load(_write(tmp_path, raw))


def test_a_profile_naming_an_undefined_terminal_posture_is_refused(tmp_path) -> None:  # type: ignore[no-untyped-def]
    raw = _default_raw()
    raw["profiles"]["CHAT_QUICK"]["terminal"] = "yolo"

    with pytest.raises(ConfigError, match="terminal"):
        LadderConfig.load(_write(tmp_path, raw))


def test_an_unknown_ask_policy_is_refused(tmp_path) -> None:  # type: ignore[no-untyped-def]
    raw = _default_raw()
    raw["policy"]["G1_UNBOUND"]["ask"] = "guess"

    with pytest.raises(ConfigError):
        LadderConfig.load(_write(tmp_path, raw))


def test_a_wrong_schema_id_is_refused(tmp_path) -> None:  # type: ignore[no-untyped-def]
    raw = _default_raw()
    raw["schema"] = "golem-ladder/v2"

    with pytest.raises(ConfigError, match="unknown schema id"):
        LadderConfig.load(_write(tmp_path, raw))


def test_an_unknown_top_level_key_is_refused(tmp_path) -> None:  # type: ignore[no-untyped-def]
    """A typo in a key name is the silent failure this schema most invites — a
    misspelled `profiles:` would leave the defaults in place and nobody would know."""
    raw = _default_raw()
    raw["profles"] = {}

    with pytest.raises(ConfigError):
        LadderConfig.load(_write(tmp_path, raw))


def test_the_rung_order_is_the_policy_tables_and_not_the_gap_sets(tmp_path) -> None:  # type: ignore[no-untyped-def]
    """⛑ `eligible_rungs` used to iterate the caller's `set` of open gap kinds. `GapKind`
    is a `StrEnum`, so such a set iterates in string-hash order — randomised per process
    by CPython — and with two open kinds naming different rungs the climb order flipped
    between runs of the same question over the same lattice:

        seed=0 -> ['lookup', 'local', 'capable']
        seed=1 -> ['capable', 'lookup', 'local']

    Combined with the invocation budget that decides WHICH rungs get spent. Deterministic
    below the LLM line is the whole thesis; the order is now the config author's.
    """
    raw = _default_raw()
    raw["policy"]["G1_UNBOUND"]["rungs"] = ["lookup", "local"]
    raw["policy"]["G4_METHOD_MISS"]["rungs"] = ["capable"]
    ladder = LadderConfig.load(_write(tmp_path, raw))

    forwards = ladder.eligible_rungs(
        [GapKind.G1_UNBOUND, GapKind.G4_METHOD_MISS], "INVESTIGATION_DEEP"
    )
    backwards = ladder.eligible_rungs(
        [GapKind.G4_METHOD_MISS, GapKind.G1_UNBOUND], "INVESTIGATION_DEEP"
    )

    # G1_UNBOUND precedes G4_METHOD_MISS in the shipped `policy:` table, so it leads —
    # whatever order the open gaps happened to arrive in, set or list.
    assert forwards == ["lookup", "local", "capable"]
    assert backwards == forwards
    as_a_set = {GapKind.G4_METHOD_MISS, GapKind.G1_UNBOUND}
    assert ladder.eligible_rungs(as_a_set, "INVESTIGATION_DEEP") == forwards
