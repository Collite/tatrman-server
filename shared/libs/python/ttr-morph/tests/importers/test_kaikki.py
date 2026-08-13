# SPDX-License-Identifier: Apache-2.0
"""The kaikki importer: classify-do-not-believe, and what it refuses.

The tests that carry weight are the two about *shape*: an entry whose table a
pattern reproduces must be indistinguishable from a hand-written one (that is
the whole value of the compact form), and one it does not must arrive as a
full-form entry rather than a plausible wrong pattern (D-F1-α).

Fixtures are hand-built dicts in the extract's shape rather than a slice of the
real file: a 193 MB corpus in the test suite would make these tests a download,
and the shape is what is being asserted.
"""

from __future__ import annotations

import json

import pytest

from ttrmorph.importers.kaikki import (
    classify_projected,
    covers,
    do_import,
    entries,
    entry_atoms,
    load_tags,
    read_targets,
    table_of,
    targets_from_frequencies,
)
from ttrmorph.importers.sources import PoisonedSource


@pytest.fixture(scope="module")
def tags():
    return load_tags()


def noun(word, gender, cells, extra=()):
    """An entry in the English edition's shape: gender on the headword."""
    forms = [
        {"form": form, "tags": list(tags_), "source": "declension"}
        for tags_, form in cells.items()
    ]
    return {
        "word": word,
        "pos": "noun",
        "lang_code": "cs",
        "head_templates": [{"name": "cs-noun", "args": {"1": gender}}],
        "forms": forms + list(extra),
    }


ZENA = {
    ("nominative", "singular"): "tržba",
    ("genitive", "singular"): "tržby",
    ("dative", "singular"): "tržbě",
    ("accusative", "singular"): "tržbu",
    ("vocative", "singular"): "tržbo",
    ("locative", "singular"): "tržbě",
    ("instrumental", "singular"): "tržbou",
    ("nominative", "plural"): "tržby",
    ("genitive", "plural"): "tržeb",
    ("dative", "plural"): "tržbám",
    ("accusative", "plural"): "tržby",
    ("vocative", "plural"): "tržby",
    ("locative", "plural"): "tržbách",
    ("instrumental", "plural"): "tržbami",
}


# ── the table ────────────────────────────────────────────────────────────────


def test_the_entry_supplies_the_gender_its_cells_do_not(tags):
    """A Czech noun's gender is stated once, on the lexeme."""
    table, _ = table_of(noun("tržba", "f", ZENA), tags, upos="NOUN")
    assert all("Gender=Fem" in feats for feats in table)
    assert table["Case=Nom|Gender=Fem|Number=Sing"] == {"tržba"}


def test_an_entry_with_no_gender_yields_nothing(tags):
    """Not a guess: a wrong gender turns every cell into a mismatch, and the
    engine would be blamed for it."""
    entry = noun("tržba", "?", ZENA)
    assert entry_atoms(entry, "NOUN", tags) is None
    assert table_of(entry, tags, upos="NOUN") == ({}, 0)


def test_the_czech_edition_gender_is_read_too(tags):
    entry = {"word": "tržba", "pos": "noun", "tags": ["feminine"], "forms": []}
    assert entry_atoms(entry, "NOUN", tags) == ["Gender=Fem"]


def test_register_marked_forms_are_dropped_and_counted(tags):
    extra = [{"form": "tržbách", "tags": ["locative", "plural", "archaic"]}]
    table, dropped = table_of(noun("tržba", "f", ZENA, extra), tags, upos="NOUN")
    assert dropped == 1
    assert table["Case=Loc|Gender=Fem|Number=Plur"] == {"tržbách"}


def test_cells_outside_the_generated_subset_are_dropped(tags):
    extra = [{"form": "tržbový", "tags": ["relational", "adjective"]}]
    _, dropped = table_of(noun("tržba", "f", ZENA, extra), tags, upos="NOUN")
    assert dropped == 1


def test_a_cell_without_the_required_features_is_dropped(tags):
    """Header artefacts: a row tagged only `inanimate|nominative` is comparable
    with both the singular and the plural and agrees with neither."""
    extra = [{"form": "junk", "tags": ["nominative"]}]
    table, dropped = table_of(noun("tržba", "f", ZENA, extra), tags, upos="NOUN")
    assert dropped == 1
    assert "junk" not in {form for forms in table.values() for form in forms}


# ── the projected match ──────────────────────────────────────────────────────


def test_a_clean_table_classifies_to_a_pattern(tags):
    table, _ = table_of(noun("tržba", "f", ZENA), tags, upos="NOUN")
    assert classify_projected("tržba", "NOUN", table) == ("žena", ("fleeting-e",))


def test_a_classified_entry_is_indistinguishable_from_a_hand_entry(tags):
    entry, _ = do_import_one(noun("tržba", "f", ZENA), ["tržba"])
    assert entry == {
        "lemma": "tržba",
        "upos": "NOUN",
        "vzor": "žena",
        "flags": ["fleeting-e"],
        "provenance": "wiktionary",
    }


def test_a_table_no_pattern_reproduces_becomes_a_full_form_entry(tags):
    broken = {**ZENA, ("genitive", "plural"): "tržbotron"}
    entry, report = do_import_one(noun("tržba", "f", broken), ["tržba"])
    assert "vzor" not in entry
    assert {form["form"] for form in entry["forms"]} >= {"tržbotron", "tržba"}
    assert report.full_form == 1
    assert report.classified == 0


def test_the_source_may_carry_cells_the_engine_does_not_generate(tags):
    """The projection rule. A verb table's transgressives are not a defect."""
    table, _ = table_of(noun("tržba", "f", ZENA), tags, upos="NOUN")
    table["Case=Abs|Gender=Fem|Number=Sing"] = {"tržbam"}
    assert classify_projected("tržba", "NOUN", table) == ("žena", ("fleeting-e",))


def test_a_cell_the_source_lacks_is_a_mismatch(tags):
    """The other direction is NOT tolerated — an incomplete table cannot be
    classified, which is the whole argument D-O1 made for this source."""
    short = {tags_: form for tags_, form in ZENA.items() if "vocative" not in tags_}
    table, _ = table_of(noun("tržba", "f", short), tags, upos="NOUN")
    assert classify_projected("tržba", "NOUN", table) is None


def test_covers_compares_per_cell_as_sets():
    assert covers({"A": {"x"}}, [("x", "A")])
    assert not covers({"A": {"y"}}, [("x", "A")])
    assert not covers({}, [])


def test_covers_accepts_a_source_cell_with_an_extra_variant():
    """*novými* beside *novýma*, *dělat* beside *dělati* — untagged variants
    the engine deliberately does not model."""
    assert covers({"A": {"x", "x-colloquial"}}, [("x", "A")])


def test_covers_matches_across_depths_in_both_directions():
    # source coarser than the engine (the adjective plural)
    assert covers(
        {"Case=Ins|Number=Plur": {"x"}}, [("x", "Case=Ins|Gender=Fem|Number=Plur")]
    )
    # source finer than the engine (the l-participle animacy split)
    assert covers(
        {"Animacy=Anim|Gender=Masc": {"x"}, "Animacy=Inan|Gender=Masc": {"x"}},
        [("x", "Gender=Masc")],
    )
    # ...and a disagreement at either depth is still caught
    assert not covers(
        {"Animacy=Anim|Gender=Masc": {"x"}, "Animacy=Inan|Gender=Masc": {"y"}},
        [("x", "Gender=Masc")],
    )


# ── the run ──────────────────────────────────────────────────────────────────


def do_import_one(entry, targets, **kwargs):
    import tempfile
    from pathlib import Path

    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "kaikki.jsonl"
        path.write_text(json.dumps(entry, ensure_ascii=False) + "\n", encoding="utf-8")
        found, report = do_import(path, targets, **kwargs)
    return (found[0] if found else None), report


def test_words_outside_the_target_list_are_not_imported():
    found, report = do_import_one(noun("tržba", "f", ZENA), ["faktura"])
    assert found is None
    assert report.entries_in_target == 0


def test_an_identity_the_hand_seed_claims_is_left_alone():
    found, _ = do_import_one(
        noun("tržba", "f", ZENA), ["tržba"], exclude=[("tržba", "NOUN")]
    )
    assert found is None


def test_the_richest_table_wins_when_a_word_appears_twice(tmp_path):
    """The extract splits by etymology and usually only one line has a table."""
    thin = noun("tržba", "f", {("nominative", "singular"): "tržba"})
    path = tmp_path / "k.jsonl"
    path.write_text(
        json.dumps(thin, ensure_ascii=False)
        + "\n"
        + json.dumps(noun("tržba", "f", ZENA), ensure_ascii=False)
        + "\n",
        encoding="utf-8",
    )
    found, report = do_import(path, ["tržba"])
    assert found[0]["vzor"] == "žena"
    assert report.entries_in_target == 1


def test_the_importer_refuses_a_poisoned_path(tmp_path):
    path = tmp_path / "morfflex-cs.jsonl"
    path.write_text("{}\n", encoding="utf-8")
    with pytest.raises(PoisonedSource):
        list(entries(path))


# ── the target list ──────────────────────────────────────────────────────────


def test_the_authored_target_list_reads(tmp_path):
    path = tmp_path / "t.yaml"
    path.write_text(
        "language: cs\nversion: 1\ngroups:\n  hero: [tržba, faktura]\n  time: [rok]\n",
        encoding="utf-8",
    )
    assert read_targets(path) == ["tržba", "faktura", "rok"]


def test_a_target_list_with_no_groups_is_an_error(tmp_path):
    """⚠ This silently returned ZERO lemmas once, and the whole import ran
    without ever asking for the hero vocabulary."""
    path = tmp_path / "t.yaml"
    path.write_text("language: cs\nwords: [tržba]\n", encoding="utf-8")
    with pytest.raises(ValueError, match="groups"):
        read_targets(path)


def test_frequency_targets_are_the_head_of_the_table(tmp_path):
    path = tmp_path / "f.tsv"
    path.write_text("být\t100\na\t90\nrok\t5\n", encoding="utf-8")
    assert targets_from_frequencies(path, 2) == ["být", "a"]
