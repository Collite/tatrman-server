# SPDX-License-Identifier: Apache-2.0
"""NLS-P1.1.T5 — the normalisation pass.

Its contract is narrow and worth stating: normalisation may change a pack's
*shape*, never its *meaning*. Most of these tests are about the second half.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ttrnlp.rules.dsl import RepeatModel, load_pack
from ttrnlp.rules.normalize import normalize_pack

PACKS = Path(__file__).parent.parent / "fixtures" / "packs"
GOLDEN = PACKS / "hero-normalized.json"


def _one_step(lhs: str):
    pack = load_pack(
        "pack: p\nversion: 1\nphases:\n"
        "  - phase: p\n    input: [Token, Lookup]\n    control: appelt\n"
        "    rules:\n"
        "      - rule: R\n"
        f"        lhs: {lhs}\n"
        "        rhs: [ { add: { type: X } } ]\n"
    )
    return normalize_pack(pack).phases[0].rules[0].lhs[0]


# ── repeat sugar ─────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("sugar", "minimum", "maximum"),
    [("'*'", 0, None), ("'+'", 1, None), ("'?'", 0, 1)],
)
def test_repeat_sugar_becomes_an_interval(sugar, minimum, maximum):
    step = _one_step(f"[ {{ ann: Token, repeat: {sugar} }} ]")
    assert isinstance(step.repeat, RepeatModel)
    assert (step.repeat.min, step.repeat.max) == (minimum, maximum)


def test_an_explicit_interval_is_left_alone():
    step = _one_step("[ { ann: Token, repeat: { min: 2, max: 5 } } ]")
    assert (step.repeat.min, step.repeat.max) == (2, 5)


def test_an_absent_repeat_stays_absent():
    assert _one_step("[ { ann: Token } ]").repeat is None


def test_repeat_sugar_is_normalised_inside_nested_steps():
    step = _one_step(
        "[ { group: { seq: [ { ann: Token, repeat: '*' }, { ann: Lookup } ] } } ]"
    )
    assert isinstance(step.group.seq[0].repeat, RepeatModel)
    assert step.group.seq[0].repeat.min == 0


# ── token shorthands ─────────────────────────────────────────────────────────


def test_text_shorthand_becomes_a_token_step():
    step = _one_step("[ { text: od } ]")
    assert step.ann == "Token"
    assert step.features == {"text": "od"}
    assert step.text is None


def test_lemma_shorthand_becomes_a_token_step():
    step = _one_step("[ { lemma: faktura } ]")
    assert step.ann == "Token"
    assert step.features == {"lemma": "faktura"}
    assert step.lemma is None


def test_a_shorthand_keeps_its_modifiers():
    step = _one_step("[ { lemma: faktura, repeat: '+', bind: f } ]")
    assert step.ann == "Token"
    assert step.bind == "f"
    assert (step.repeat.min, step.repeat.max) == (1, None)


# ── single-step seq collapse ─────────────────────────────────────────────────


def test_a_one_step_seq_collapses():
    step = _one_step("[ { group: { seq: [ { ann: Lookup } ] } } ]")
    assert step.group is None
    assert step.ann == "Lookup"


def test_a_collapsing_seq_carries_the_groups_modifiers_inward():
    step = _one_step(
        "[ { group: { seq: [ { ann: Lookup } ] }, bind: g, repeat: '?' } ]"
    )
    assert step.ann == "Lookup"
    assert step.bind == "g"
    assert (step.repeat.min, step.repeat.max) == (0, 1)


def test_nested_one_step_seqs_collapse_all_the_way():
    step = _one_step(
        "[ { group: { seq: [ { group: { seq: [ { ann: Lookup } ] } } ] } } ]"
    )
    assert step.ann == "Lookup"


def test_a_multi_step_seq_does_not_collapse():
    step = _one_step("[ { group: { seq: [ { ann: Lookup }, { ann: Token } ] } } ]")
    assert step.group is not None
    assert len(step.group.seq) == 2


def test_an_or_group_never_collapses_even_with_one_reachable_branch():
    step = _one_step(
        "[ { group: { or: [ [ { ann: Lookup } ], [ { ann: Token } ] ] } } ]"
    )
    assert step.group is not None
    assert step.group.or_ is not None


def test_a_seq_is_kept_when_both_levels_bind():
    """Collapsing would have to discard one of the two names."""
    step = _one_step(
        "[ { group: { seq: [ { ann: Lookup, bind: inner } ] }, bind: outer } ]"
    )
    assert step.group is not None
    assert step.bind == "outer"
    assert step.group.seq[0].bind == "inner"


def test_a_seq_is_kept_when_both_levels_repeat():
    """`(A+)?` is not `A+` and it is not `A?` — the nesting is the meaning."""
    step = _one_step(
        "[ { group: { seq: [ { ann: Lookup, repeat: '+' } ] }, repeat: '?' } ]"
    )
    assert step.group is not None
    assert (step.repeat.min, step.repeat.max) == (0, 1)
    assert (step.group.seq[0].repeat.min, step.group.seq[0].repeat.max) == (1, None)


# ── general properties ───────────────────────────────────────────────────────


def test_normalisation_is_idempotent():
    pack = load_pack(PACKS / "valid" / "kitchen-sink.pack.yaml")
    once = normalize_pack(pack)
    twice = normalize_pack(once)
    assert twice.model_dump(by_alias=True) == once.model_dump(by_alias=True)


def test_normalisation_does_not_mutate_its_input():
    pack = load_pack(PACKS / "valid" / "kitchen-sink.pack.yaml")
    before = pack.model_dump(by_alias=True)
    normalize_pack(pack)
    assert pack.model_dump(by_alias=True) == before


def test_no_sugar_survives_normalisation():
    pack = normalize_pack(load_pack(PACKS / "valid" / "kitchen-sink.pack.yaml"))

    def walk(step):
        assert step.text is None and step.lemma is None, "shorthand survived"
        assert not isinstance(step.repeat, str), "repeat sugar survived"
        for nested in step.substeps():
            walk(nested)

    for phase in pack.phases:
        for rule in phase.rules:
            for step in rule.lhs:
                walk(step)


def test_a_normalised_pack_still_passes_the_cross_checks():
    from ttrnlp.rules.checks import check_pack

    pack = normalize_pack(load_pack(PACKS / "valid" / "kitchen-sink.pack.yaml"))
    check_pack(pack)


# ── the golden ───────────────────────────────────────────────────────────────


def test_hero_pack_normalises_to_the_golden():
    pack = normalize_pack(load_pack(PACKS / "valid" / "hero.pack.yaml"))
    actual = pack.model_dump(by_alias=True, exclude_none=True, mode="json")
    expected = json.loads(GOLDEN.read_text(encoding="utf-8"))
    assert actual == expected
