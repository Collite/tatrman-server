# SPDX-License-Identifier: Apache-2.0
"""NLS-P1.1.T4 — the cross-checks a schema cannot make.

Every failure here describes a pack that is perfectly well-formed and still
cannot work: the parts do not refer to each other correctly. They share one
property that makes them worth catching at load time rather than at match time
— each produces a rule that **silently never fires**, which is the single worst
failure mode a rule engine has. A rule that crashes gets fixed; a rule that
quietly matches nothing gets debugged for an afternoon.
"""

from __future__ import annotations

import pytest

from ttrnlp.packs.diag import NLS_PACK_002, PackError
from ttrnlp.rules.checks import check_pack
from ttrnlp.rules.dsl import load_pack

PREAMBLE = "pack: p\nversion: 1\nphases:\n"


def _pack(lhs: str, rhs: str, *, input_types: str = "[Lookup, Token]") -> str:
    return (
        PREAMBLE + f"  - phase: ph\n    input: {input_types}\n    control: appelt\n"
        "    rules:\n"
        "      - rule: R\n"
        f"        lhs: {lhs}\n"
        f"        rhs: {rhs}\n"
    )


def _check(source: str):
    return check_pack(load_pack(source))


def _expect_failure(source: str) -> PackError:
    with pytest.raises(PackError) as raised:
        _check(source)
    codes = raised.value.codes
    assert all(d.code == NLS_PACK_002 for d in raised.value.diagnostics), codes
    return raised.value


# ── a type the phase cannot see ──────────────────────────────────────────────


def test_a_step_type_absent_from_input_is_rejected():
    err = _expect_failure(
        _pack("[ { ann: ORGANIZATION } ]", "[ { add: { type: X } } ]")
    )
    message = err.diagnostics[0].message
    assert "ORGANIZATION" in message
    assert "input" in message


def test_the_message_names_the_rule_and_the_phase():
    err = _expect_failure(
        _pack("[ { ann: ORGANIZATION } ]", "[ { add: { type: X } } ]")
    )
    assert "ph" in err.diagnostics[0].message
    assert "R" in err.diagnostics[0].message


def test_a_type_listed_in_input_passes():
    _check(_pack("[ { ann: Lookup } ]", "[ { add: { type: X } } ]"))


def test_nested_steps_are_checked_too():
    # A type buried in a group is exactly as invisible as one at the top level,
    # and much easier to miss by eye.
    err = _expect_failure(
        _pack(
            "[ { group: { or: [ [ { ann: Lookup } ], [ { ann: PERSON } ] ] } } ]",
            "[ { add: { type: X } } ]",
        )
    )
    assert "PERSON" in err.diagnostics[0].message


def test_types_inside_contains_and_within_are_checked():
    err = _expect_failure(
        _pack(
            "[ { ann: Lookup, contains: { ann: Money } } ]",
            "[ { add: { type: X } } ]",
        )
    )
    assert "Money" in err.diagnostics[0].message


def test_the_token_shorthands_require_token_in_input():
    err = _expect_failure(
        _pack(
            "[ { lemma: faktura } ]",
            "[ { add: { type: X } } ]",
            input_types="[Lookup]",
        )
    )
    # The shorthand IS an `ann: Token` step, so it needs Token visible — the
    # author never typed "Token", which is precisely why this must be said.
    assert "Token" in err.diagnostics[0].message


def test_an_added_type_need_not_be_in_input():
    # `add` writes an annotation; it does not match one. Requiring the output
    # type in `input:` would force every pack to declare types it never reads.
    _check(_pack("[ { ann: Lookup } ]", "[ { add: { type: QueryPattern } } ]"))


# ── RHS references to names never bound ──────────────────────────────────────


def test_add_span_referencing_an_unbound_name_is_rejected():
    err = _expect_failure(
        _pack("[ { ann: Lookup } ]", "[ { add: { type: X, span: nope } } ]")
    )
    assert "nope" in err.diagnostics[0].message


def test_a_getter_referencing_an_unbound_name_is_rejected():
    err = _expect_failure(
        _pack(
            "[ { ann: Lookup } ]",
            '[ { add: { type: X, features: '
            '{ v: { from: ghost, get: "@string" } } } } ]',
        )
    )
    assert "ghost" in err.diagnostics[0].message


def test_update_on_an_unbound_name_is_rejected():
    err = _expect_failure(
        _pack(
            "[ { ann: Lookup } ]",
            "[ { update: { on: ghost, features: { a: 1 } } } ]",
        )
    )
    assert "ghost" in err.diagnostics[0].message


def test_a_bound_name_satisfies_every_reference_form():
    _check(
        _pack(
            "[ { ann: Lookup, bind: it } ]",
            "[ { add: { type: X, span: it, features: "
            '{ v: { from: it, get: "@string" } } } }, '
            "{ update: { on: it, features: { seen: true } } } ]",
        )
    )


def test_a_name_bound_in_a_nested_group_counts_as_bound():
    _check(
        _pack(
            "[ { group: { seq: [ { ann: Lookup, bind: inner } ] } } ]",
            '[ { add: { type: X, features: '
            '{ v: { from: inner, get: "@string" } } } } ]',
        )
    )


def test_a_name_bound_in_another_rule_does_not_count():
    """Bindings are rule-scoped, as in JAPE.

    Leaking them across rules would make a pack's behaviour depend on rule
    order in a way nothing in the file shows.
    """
    source = (
        PREAMBLE + "  - phase: ph\n    input: [Lookup]\n    control: appelt\n"
        "    rules:\n"
        "      - rule: Binder\n"
        "        lhs: [ { ann: Lookup, bind: elsewhere } ]\n"
        "        rhs: [ { add: { type: X } } ]\n"
        "      - rule: Borrower\n"
        "        lhs: [ { ann: Lookup } ]\n"
        "        rhs: [ { add: { type: Y, span: elsewhere } } ]\n"
    )
    err = _expect_failure(source)
    assert "elsewhere" in err.diagnostics[0].message
    assert "Borrower" in err.diagnostics[0].message


# ── duplicate binds ──────────────────────────────────────────────────────────


def test_a_duplicate_bind_within_one_rule_is_rejected():
    err = _expect_failure(
        _pack(
            "[ { ann: Lookup, bind: it }, { ann: Token, bind: it } ]",
            "[ { add: { type: X, span: it } } ]",
        )
    )
    assert "it" in err.diagnostics[0].message
    assert "duplicate" in err.diagnostics[0].message.lower()


def test_a_duplicate_bind_across_a_group_boundary_is_still_a_duplicate():
    err = _expect_failure(
        _pack(
            "[ { ann: Lookup, bind: it }, "
            "{ group: { seq: [ { ann: Token, bind: it } ] } } ]",
            "[ { add: { type: X, span: it } } ]",
        )
    )
    assert "duplicate" in err.diagnostics[0].message.lower()


def test_the_same_bind_name_in_two_different_rules_is_fine():
    source = (
        PREAMBLE + "  - phase: ph\n    input: [Lookup]\n    control: appelt\n"
        "    rules:\n"
        "      - rule: One\n"
        "        lhs: [ { ann: Lookup, bind: it } ]\n"
        "        rhs: [ { add: { type: X, span: it } } ]\n"
        "      - rule: Two\n"
        "        lhs: [ { ann: Lookup, bind: it } ]\n"
        "        rhs: [ { add: { type: Y, span: it } } ]\n"
    )
    _check(source)


def test_the_same_bind_in_two_or_branches_is_fine():
    # Only one branch can match, so the name is never bound twice at runtime.
    # Rejecting this would ban the most natural way to write an alternation.
    _check(
        _pack(
            "[ { group: { or: [ [ { ann: Lookup, bind: it } ], "
            "[ { ann: Token, bind: it } ] ] } } ]",
            "[ { add: { type: X, span: it } } ]",
        )
    )


# ── the real packs pass ──────────────────────────────────────────────────────


def test_the_hero_pack_passes_the_cross_checks(tmp_path):
    from pathlib import Path

    packs = Path(__file__).parent.parent / "fixtures" / "packs" / "valid"
    check_pack(load_pack(packs / "hero.pack.yaml"))


def test_the_kitchen_sink_pack_passes_the_cross_checks():
    from pathlib import Path

    packs = Path(__file__).parent.parent / "fixtures" / "packs" / "valid"
    check_pack(load_pack(packs / "kitchen-sink.pack.yaml"))


def test_every_error_is_reported_not_only_the_first():
    err = _expect_failure(
        _pack("[ { ann: ORGANIZATION } ]", "[ { add: { type: X, span: nope } } ]")
    )
    assert len(err.diagnostics) == 2
