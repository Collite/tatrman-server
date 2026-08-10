# SPDX-License-Identifier: Apache-2.0
"""NLS-P1.1.T1 — the schema and the parser.

Two obligations, and the second is the interesting one:

1. Every construct the DSL claims to support must load (``kitchen-sink``), and
   the pack the design published must load unchanged (``hero``).
2. Every way a pack can be malformed must fail with ``NLS-PACK-001`` **and a
   message naming where**. A validator that says "invalid" and stops is worse
   than no validator: it moves the search from the author's editor into their
   guesswork.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ttrnlp.packs.diag import NLS_PACK_001, SEVERITY_ERROR, PackError
from ttrnlp.rules.dsl import PackModel, load_pack, load_schema

PACKS = Path(__file__).parent.parent / "fixtures" / "packs"
VALID = PACKS / "valid"
INVALID = PACKS / "invalid"


# ── the schema resource itself ───────────────────────────────────────────────


def test_schema_loads_from_package_data():
    schema = load_schema()
    assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
    assert "step" in schema["$defs"]


def test_schema_is_itself_a_valid_2020_12_schema():
    from jsonschema import Draft202012Validator

    Draft202012Validator.check_schema(load_schema())


def test_every_object_level_forbids_unknown_keys():
    """`additionalProperties: false` at EVERY level (T2), checked structurally.

    Spot-checking a few levels by hand would miss exactly the level someone adds
    later without the guard.
    """
    offenders: list[str] = []

    def walk(node, path):
        if isinstance(node, dict):
            declares_properties = "properties" in node
            if declares_properties and node.get("additionalProperties") is not False:
                offenders.append(path)
            for key, value in node.items():
                walk(value, f"{path}.{key}")
        elif isinstance(node, list):
            for index, value in enumerate(node):
                walk(value, f"{path}[{index}]")

    walk(load_schema(), "$")
    assert offenders == []


# ── valid packs ──────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "path", sorted(VALID.glob("*.pack.yaml")), ids=lambda p: p.stem
)
def test_valid_packs_load(path):
    pack = load_pack(path)
    assert isinstance(pack, PackModel)
    assert pack.pack
    assert pack.phases


def test_the_design_hero_pack_survives_verbatim():
    pack = load_pack(VALID / "hero.pack.yaml")
    assert pack.pack == "dfp-query-patterns"
    assert pack.version == 1

    (phase,) = pack.phases
    assert phase.phase == "query-match"
    assert phase.input == ["Lookup", "ORG", "PER"]
    assert phase.control == "appelt"

    (rule,) = phase.rules
    assert rule.rule == "FakturyZakaznika"
    assert rule.priority == 100
    assert len(rule.lhs) == 3

    faktura, subjekt, alternation = rule.lhs
    assert faktura.ann == "Lookup"
    assert faktura.features == {"kind": "entity_alias", "entity": "faktura"}
    assert subjekt.features["entity"] == "subjekt"
    assert alternation.bind == "name"
    assert [branch[0].ann for branch in alternation.group.or_] == ["ORG", "PER"]

    (action,) = rule.rhs
    assert action.add.type == "QueryPattern"
    assert action.add.features["query"] == "faktury_zakaznika"
    getter = action.add.features["nazev_zakaznika"]
    assert (getter.from_, getter.get) == ("name", "@string")


def test_priority_defaults_to_zero():
    pack = load_pack(VALID / "kitchen-sink.pack.yaml")
    bare = pack.phases[1].rules[0]
    assert bare.priority == 0


def test_kitchen_sink_covers_every_control_style():
    pack = load_pack(VALID / "kitchen-sink.pack.yaml")
    assert {p.control for p in pack.phases} == {
        "appelt",
        "brill",
        "all",
        "first",
        "once",
    }


def test_kitchen_sink_covers_every_step_form():
    pack = load_pack(VALID / "kitchen-sink.pack.yaml")
    forms = set()

    def walk(step):
        forms.add(step.form)
        for nested in step.substeps():
            walk(nested)

    for phase in pack.phases:
        for rule in phase.rules:
            for step in rule.lhs:
                walk(step)

    assert forms == {"ann", "text", "lemma", "all", "group", "after", "notafter", "not"}


def test_kitchen_sink_covers_every_constraint_flavour():
    from ttrnlp.rules.dsl import InConstraint, RangeConstraint, RegexConstraint

    pack = load_pack(VALID / "kitchen-sink.pack.yaml")
    features = pack.phases[0].rules[0].lhs[2].features
    assert features["upos"] == "NOUN"
    assert features["dep_head"] == 3
    assert features["is_head"] is True
    assert isinstance(features["lemma"], RegexConstraint)
    assert isinstance(features["xpos"], InConstraint)
    assert isinstance(features["length"], RangeConstraint)
    assert (features["length"].gte, features["length"].lt) == (2, 40)


def test_kitchen_sink_covers_both_actions_and_every_getter():
    pack = load_pack(VALID / "kitchen-sink.pack.yaml")
    rhs = pack.phases[0].rules[0].rhs
    add_full, add_bare, update = rhs

    assert add_full.add.span == "subject"
    assert add_full.add.set == "patterns"
    assert {v.get for v in add_full.add.features.values() if hasattr(v, "get")} == {
        "@string",
        "@length",
        "kind",
    }
    assert add_bare.add.span is None and add_bare.add.set is None
    assert update.update.on == "subject"
    assert update.update.features["verified"] == "company"


# ── invalid packs: one file per failure, each naming its path ────────────────

INVALID_CASES = [
    # (fixture stem, fragments the message must contain)
    ("unknown-top-key", ["phasez"]),
    ("unknown-step-key", ["feature", "lhs"]),
    ("empty-or-branch", ["or"]),
    ("repeat-min-gt-max", ["repeat"]),
    ("missing-pack", ["pack"]),
    ("missing-version", ["version"]),
    ("bad-control", ["control"]),
    ("rule-without-rhs", ["rhs"]),
]


@pytest.mark.parametrize(
    ("stem", "fragments"), INVALID_CASES, ids=[c[0] for c in INVALID_CASES]
)
def test_invalid_pack_reports_pack_001_with_a_path(stem, fragments):
    with pytest.raises(PackError) as raised:
        load_pack(INVALID / f"{stem}.pack.yaml")

    diagnostics = raised.value.diagnostics
    assert diagnostics, "a rejected pack must say why"
    assert all(d.code == NLS_PACK_001 for d in diagnostics), raised.value.codes
    assert all(d.severity == SEVERITY_ERROR for d in diagnostics)

    blob = " | ".join(d.message for d in diagnostics)
    # Every message leads with a JSON path.
    assert all(d.message.startswith("$") for d in diagnostics), blob
    for fragment in fragments:
        assert fragment in blob, f"{fragment!r} missing from: {blob}"


def test_every_invalid_fixture_has_a_case():
    on_disk = {p.name.removesuffix(".pack.yaml") for p in INVALID.glob("*.pack.yaml")}
    covered = {stem for stem, _ in INVALID_CASES}
    assert on_disk == covered


def test_diagnostics_carry_the_source_and_the_pack_id():
    path = INVALID / "bad-control.pack.yaml"
    with pytest.raises(PackError) as raised:
        load_pack(path)
    diagnostic = raised.value.diagnostics[0]
    assert diagnostic.source == str(path)
    # The pack id survives even though the pack is broken — diagnostics have to
    # be attributable to a pack to be reportable per pack.
    assert diagnostic.pack == "bad-control"


# ── failures that are not fixture files ──────────────────────────────────────


def test_malformed_yaml_is_reported_not_raised_raw():
    with pytest.raises(PackError) as raised:
        load_pack("pack: [unclosed\nversion: 1\n")
    assert raised.value.diagnostics[0].code == NLS_PACK_001
    assert "YAML" in raised.value.diagnostics[0].message


def test_a_non_mapping_document_is_reported():
    with pytest.raises(PackError) as raised:
        load_pack("- just\n- a list\n")
    assert "mapping" in raised.value.diagnostics[0].message


def test_bare_not_outside_all_is_rejected():
    with pytest.raises(PackError) as raised:
        load_pack(
            "pack: p\nversion: 1\nphases:\n"
            "  - phase: p\n    input: [Lookup]\n    control: appelt\n"
            "    rules:\n"
            "      - rule: R\n"
            "        lhs: [ { not: { ann: Lookup } } ]\n"
            "        rhs: [ { add: { type: X } } ]\n"
        )
    message = raised.value.diagnostics[0].message
    assert "`not:` is only legal as a member of `all:`" in message


def test_a_step_with_two_form_keys_is_rejected():
    with pytest.raises(PackError) as raised:
        load_pack(
            "pack: p\nversion: 1\nphases:\n"
            "  - phase: p\n    input: [Lookup, Token]\n    control: appelt\n"
            "    rules:\n"
            "      - rule: R\n"
            "        lhs: [ { ann: Lookup, text: 'x' } ]\n"
            "        rhs: [ { add: { type: X } } ]\n"
        )
    assert raised.value.diagnostics[0].code == NLS_PACK_001


def test_features_without_ann_is_rejected():
    # `features:` on a bare step has nothing to constrain; dependentRequired
    # catches it before it becomes a rule that silently never fires.
    with pytest.raises(PackError) as raised:
        load_pack(
            "pack: p\nversion: 1\nphases:\n"
            "  - phase: p\n    input: [Token]\n    control: appelt\n"
            "    rules:\n"
            "      - rule: R\n"
            "        lhs: [ { text: 'x', features: { upos: NOUN } } ]\n"
            "        rhs: [ { add: { type: X } } ]\n"
        )
    assert raised.value.diagnostics[0].code == NLS_PACK_001


def test_an_uncompilable_regex_is_rejected():
    with pytest.raises(PackError) as raised:
        load_pack(
            "pack: p\nversion: 1\nphases:\n"
            "  - phase: p\n    input: [Token]\n    control: appelt\n"
            "    rules:\n"
            "      - rule: R\n"
            "        lhs: [ { ann: Token, features: "
            "{ lemma: { regex: '[unclosed' } } } ]\n"
            "        rhs: [ { add: { type: X } } ]\n"
        )
    assert "does not compile" in raised.value.diagnostics[0].message


# ── message quality ──────────────────────────────────────────────────────────
#
# These pin how failures READ, not just that they happen. A validator is a
# writing surface: the message is the entire product from the author's side, and
# every one of these started as a message that was accurate and useless.


def _step_pack(step: str) -> str:
    return (
        "pack: p\nversion: 1\nphases:\n"
        "  - phase: p\n    input: [Token, Lookup]\n    control: appelt\n"
        "    rules:\n"
        "      - rule: R\n"
        f"        lhs: [ {step} ]\n"
        "        rhs: [ { add: { type: X } } ]\n"
    )


def _first_message(source: str) -> str:
    with pytest.raises(PackError) as raised:
        load_pack(source)
    return raised.value.diagnostics[0].message


def test_two_form_keys_says_which_two():
    message = _first_message(_step_pack("{ ann: Token, text: x }"))
    assert "needs exactly one of" in message
    assert "found 2 of them (ann, text)" in message
    # The raw jsonschema rendering of this is "is valid under each of
    # {'required': ['text']}, {'required': ['ann']}" — schema-speak, not English.
    assert "valid under each of" not in message


def test_no_form_key_says_so():
    assert "found none" in _first_message(_step_pack("{ bind: b }"))


def test_a_mistyped_step_key_still_names_the_key():
    # The form-key rephrasing must not swallow this: `additionalProperties`
    # carries the same subschema, and rephrasing it would answer a question the
    # author never asked while hiding the key they actually got wrong.
    message = _first_message(_step_pack("{ ann: Lookup, feature: { kind: x } }"))
    assert "'feature' was unexpected" in message
    assert message.endswith("lhs[0].feature: Additional properties are not allowed "
                            "('feature' was unexpected)")


def test_exclusive_choice_rephrasing_covers_groups_and_actions():
    assert "needs exactly one of or | seq" in _first_message(
        _step_pack(
            "{ group: { or: [[{ann: Token}],[{ann: Token}]], "
            "seq: [{ann: Token}] } }"
        )
    )
    both = (
        "pack: p\nversion: 1\nphases:\n"
        "  - phase: p\n    input: [Token]\n    control: appelt\n"
        "    rules:\n"
        "      - rule: R\n"
        "        lhs: [ { ann: Token, bind: b } ]\n"
        "        rhs: [ { add: { type: X }, update: { on: b, features: { a: 1 } } } ]\n"
    )
    assert "needs exactly one of add | update" in _first_message(both)


def test_a_union_field_reports_once_not_once_per_arm():
    """`repeat` is `RepeatModel | sugar`, so pydantic complains about both arms.

    The author wrote an interval; being told it "should be a valid string" is
    pydantic explaining that it is also not `*`/`+`/`?`, which they never tried.
    """
    with pytest.raises(PackError) as raised:
        load_pack(_step_pack("{ ann: Token, repeat: { min: 5, max: 2 } }"))
    messages = [d.message for d in raised.value.diagnostics]
    assert len(messages) == 1, messages
    assert "greater than" in messages[0]
    assert "valid string" not in messages[0]


def test_pydantic_internals_do_not_leak_into_the_path():
    message = _first_message(_step_pack("{ ann: Token, repeat: { min: 5, max: 2 } }"))
    path = message.split(":")[0]
    assert path == "$.phases[0].rules[0].lhs[0].repeat"
    assert "function-after" not in message
    assert "Value error" not in message


def test_a_self_locating_validator_is_not_located_twice():
    message = _first_message(_step_pack("{ not: { ann: Token } }"))
    assert not message.startswith("$: $")
    assert message.count("$.") == 1


# ── the YAML 1.1 boolean trap ────────────────────────────────────────────────


def _update_pack(key: str) -> str:
    return (
        "pack: p\nversion: 1\nphases:\n"
        "  - phase: p\n    input: [Lookup]\n    control: appelt\n"
        "    rules:\n"
        "      - rule: R\n"
        "        lhs: [ { ann: Lookup, bind: it } ]\n"
        f"        rhs: [ {{ update: {{ {key}: it, features: {{ seen: true }} }} }} ]\n"
    )


def test_the_update_action_key_on_is_not_swallowed_as_a_boolean():
    """`on:` is a YAML 1.1 boolean; contracts §3 spells the update action with it.

    Under PyYAML's default (1.1) resolver this key parses as `True`, and the
    author gets "'on' is a required property" while looking straight at it.
    Packs are loaded with YAML 1.2 boolean rules so the contract's spelling
    works unquoted.
    """
    pack = load_pack(_update_pack("on"))
    assert pack.phases[0].rules[0].rhs[0].update.on == "it"


def test_quoting_the_key_also_works():
    pack = load_pack(_update_pack('"on"'))
    assert pack.phases[0].rules[0].rhs[0].update.on == "it"


@pytest.mark.parametrize("word", ["yes", "no", "on", "off"])
def test_yaml_11_boolean_words_are_plain_strings_as_values(word):
    # The other half of the 1.2 trade-off, pinned so it is a decision and not a
    # surprise: these are strings, and `true`/`false` are the booleans.
    pack = load_pack(
        "pack: p\nversion: 1\nphases:\n"
        "  - phase: p\n    input: [Token]\n    control: appelt\n"
        "    rules:\n"
        "      - rule: R\n"
        f"        lhs: [ {{ ann: Token, features: {{ flag: {word} }} }} ]\n"
        "        rhs: [ { add: { type: X } } ]\n"
    )
    assert pack.phases[0].rules[0].lhs[0].features["flag"] == word


def test_true_and_false_are_still_booleans():
    pack = load_pack(
        "pack: p\nversion: 1\nphases:\n"
        "  - phase: p\n    input: [Token]\n    control: appelt\n"
        "    rules:\n"
        "      - rule: R\n"
        "        lhs: [ { ann: Token, features: { flag: true } } ]\n"
        "        rhs: [ { add: { type: X } } ]\n"
    )
    assert pack.phases[0].rules[0].lhs[0].features["flag"] is True


def test_load_pack_accepts_yaml_text_as_well_as_a_path():
    text = (VALID / "hero.pack.yaml").read_text(encoding="utf-8")
    assert load_pack(text).pack == "dfp-query-patterns"


def test_all_errors_are_reported_not_only_the_first():
    # Two independent schema violations in one pack.
    with pytest.raises(PackError) as raised:
        load_pack(
            "pack: p\nversion: 1\nphases:\n"
            "  - phase: p\n    input: [Lookup]\n    control: nonsense\n"
            "    rules:\n"
            "      - rule: R\n"
            "        lhs: [ { ann: Lookup } ]\n"
            "        rhs: []\n"
        )
    assert len(raised.value.diagnostics) >= 2


def test_schema_ships_as_json_that_round_trips():
    raw = (
        Path(__file__).parent.parent.parent
        / "src"
        / "ttrnlp"
        / "rules"
        / "schema"
        / "pack.schema.json"
    )
    assert json.loads(raw.read_text(encoding="utf-8")) == load_schema()
