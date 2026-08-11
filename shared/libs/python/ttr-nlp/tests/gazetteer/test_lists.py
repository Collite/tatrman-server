# SPDX-License-Identifier: Apache-2.0
"""NLS-P2.1.T1/T2/T4 — the list interchange reader, and what it refuses.

A list file is generated far more often than it is hand-written, which is
precisely why the negatives matter: a broken exporter run produces a *plausible*
file — right keys, wrong or missing values — and the failure mode of accepting it
is a gazetteer that loads, reports a list, and annotates nothing.

Every diagnostic here is ``NLS-PACK-003`` (contracts §8) and names the JSON path
of the offending node, the same way pack diagnostics do.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from ttrnlp.gazetteer.lists import (
    MATCHING_MODES,
    RESERVED_FEATURES,
    load_list,
)
from ttrnlp.packs.diag import NLS_PACK_003, PackError

LISTS = Path(__file__).parent.parent / "fixtures" / "lists"
VALID = LISTS / "valid"
INVALID = LISTS / "invalid"


def diagnostics(path_or_text) -> list[str]:
    """Load something that must fail; return its messages."""
    with pytest.raises(PackError) as raised:
        load_list(path_or_text)
    assert raised.value.codes == [NLS_PACK_003] * len(raised.value.diagnostics)
    return [d.message for d in raised.value.diagnostics]


def a_list(body: str) -> str:
    """A minimal well-formed list, with `body` splicing in the parts under test."""
    return (
        "list: fixture\nversion: 1\nmatching: ci\n"
        "source: {world: hand, origin: test}\n" + body
    )


# ── the happy path ───────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "path", sorted(VALID.glob("*.list.yaml")), ids=lambda p: p.stem
)
def test_every_valid_fixture_loads(path):
    loaded = load_list(path)
    assert loaded.id == path.name.removesuffix(".list.yaml")
    assert loaded.entries


def test_the_hero_list_says_what_the_heroes_need():
    """The other half of `expected.yaml`'s contract — the terms themselves are
    asserted by `test_hero_from_lists.py`; this is the shape."""
    loaded = load_list(VALID / "dfp-entity-aliases.list.yaml")
    assert loaded.matching == "lemma"
    assert loaded.annotation == "Lookup"
    assert loaded.source.world == "dfp"
    assert {e.term for e in loaded.entries} == {
        "faktura",
        "zákazník",
        "role",
        "obchodní zástupce",
    }


def test_annotation_defaults_to_lookup():
    """contracts §4: `annotation` is optional and defaults to Lookup."""
    assert load_list(a_list("entries: [{term: faktura}]")).annotation == "Lookup"


def test_a_list_may_emit_its_own_type():
    loaded = load_list(a_list("annotation: Unit\nentries: [{term: kg}]"))
    assert loaded.annotation == "Unit"


@pytest.mark.parametrize("mode", MATCHING_MODES)
def test_all_four_modes_are_accepted(mode):
    assert load_list(a_list(f"matching: {mode}\nentries: [{{term: x}}]")).matching == (
        mode
    )


def test_a_path_and_the_yaml_itself_are_both_accepted():
    """`load_list` takes either, like `load_pack` — the loader holds paths and
    tests hold text, and neither should have to make a temp file."""
    path = VALID / "dfp-entity-aliases.list.yaml"
    assert load_list(path).id == load_list(path.read_text(encoding="utf-8")).id


# ── the negatives (T1) ───────────────────────────────────────────────────────


def test_a_list_without_a_matching_mode_is_rejected():
    (message,) = diagnostics(INVALID / "missing-matching.list.yaml")
    assert "$.matching" in message


def test_a_matching_mode_outside_the_closed_set_is_rejected():
    messages = diagnostics(INVALID / "bad-matching.list.yaml")
    assert any("$.matching" in m for m in messages)
    # The message has to name the modes that ARE legal, or the author is left
    # guessing which of four spellings we wanted.
    assert any("lemma" in m for m in messages)


def test_an_entry_without_a_term_is_rejected():
    (message,) = diagnostics(INVALID / "entry-without-term.list.yaml")
    assert "$.entries[1].term" in message


def test_an_empty_term_is_rejected():
    """Distinct from a missing one: the key is there, so pydantic is satisfied and
    only the validator catches it. An empty trie key would match every token."""
    (message,) = diagnostics(a_list('entries: [{term: "  "}]'))
    assert "$.entries[0]" in message
    assert "empty" in message


@pytest.mark.parametrize("reserved", RESERVED_FEATURES)
def test_an_entry_may_not_set_a_reserved_feature(reserved):
    """T4. The gazetteer stamps `source` and `matching`; an entry doing the same
    would erase the provenance of its own Lookup."""
    (message,) = diagnostics(
        a_list(f"entries: [{{term: faktura, features: {{{reserved}: mine}}}}]")
    )
    assert "reserved" in message
    assert reserved in message


def test_an_unknown_key_is_rejected():
    """`extra="forbid"`: a typo'd key is silent data loss otherwise. `score` is
    the one to catch — NL-17 keeps scoring world-side."""
    (message,) = diagnostics(a_list("entries: [{term: faktura, score: 0.8}]"))
    assert "score" in message


def test_a_list_without_provenance_is_rejected():
    messages = diagnostics(
        "list: fixture\nversion: 1\nmatching: ci\nentries: [{term: x}]"
    )
    assert any("$.source" in m for m in messages)


@pytest.mark.parametrize("field", ["world", "origin"])
def test_an_empty_provenance_field_is_rejected(field):
    other = "origin" if field == "world" else "world"
    (message,) = diagnostics(
        f"list: fixture\nversion: 1\nmatching: ci\n"
        f'source: {{{field}: "", {other}: x}}\nentries: [{{term: y}}]'
    )
    assert field in message


def test_an_empty_entry_list_is_rejected():
    """A list that loads and annotates nothing is the exporter failure this whole
    file exists to catch — it looks identical to success from the outside."""
    (message,) = diagnostics(a_list("entries: []"))
    assert "empty" in message


def test_a_bad_list_id_is_rejected():
    (message,) = diagnostics(
        "list: Not An Id\nversion: 1\nmatching: ci\n"
        "source: {world: hand, origin: test}\nentries: [{term: x}]"
    )
    assert "[a-z0-9-]+" in message


def test_yaml_that_is_not_a_mapping_is_rejected():
    (message,) = diagnostics("- faktura\n- zákazník\n")
    assert "must be a YAML mapping" in message


def test_broken_yaml_is_rejected_before_the_shape():
    (message,) = diagnostics("list: fixture\n  bad indent: [\n")
    assert "YAML is not well-formed" in message


def test_diagnostics_name_the_file_and_the_list():
    with pytest.raises(PackError) as raised:
        load_list(INVALID / "bad-matching.list.yaml")
    (first, *_) = raised.value.diagnostics
    assert first.source.endswith("bad-matching.list.yaml")
    # Named even though the file is broken — "which of my 40 lists" is the first
    # question a reader has.
    assert first.pack == "bad-mode"
