# SPDX-License-Identifier: Apache-2.0
"""The compiler: expansion, merge, ranks, routing, and the artifact's bytes.

Two things here are worth naming because they are easy to get wrong quietly.

The first is **routing** (C-F3): a share-alike layer must not end up in the main
body. There is no runtime symptom if it does — the artifact loads fine and
answers fine — so the only place that mistake can be caught is a test that reads
which file each row landed in.

The second is **rank defaulting**. Lower ranks sort first, so a lemma the
frequency table never saw must not default to 0: it would outrank the most
frequent word in the language and become the head of the list, which is what
every pre-morph consumer reads as `lemma`.
"""

from __future__ import annotations

from ttrnlp.morph.snapshot import content_hash

from ttrmorph.compile.snapshot import (
    NOTICE_FILENAME,
    compile_layers,
    read_frequencies,
)
from ttrmorph.engine import fold

SNAP = "cs.morph.snap"
PART = "core-kaikki.morph.part"


def compile_core(paths, **kwargs):
    return compile_layers(paths, snapshot_version="0.1.0", **kwargs)


def rows_of(text):
    """The data rows of a rendered artifact, as parsed cells."""
    lines = text.splitlines()
    start = next(i for i, line in enumerate(lines) if line.startswith("form\t")) + 1
    out = []
    for line in lines[start:]:
        if line.startswith("#"):
            break
        out.append(line.split("\t"))
    return out


def headers_of(text):
    found = {}
    for line in text.splitlines()[1:]:
        if not line.startswith("#"):
            break
        key, _, value = line[1:].partition(":")
        found[key.strip()] = value.strip()
    return found


def section(text, name):
    lines = text.splitlines()
    if name not in lines:
        return []
    start = lines.index(name) + 1
    out = []
    for line in lines[start:]:
        if line.startswith("#"):
            break
        out.append(line)
    return out


# ── the happy path ───────────────────────────────────────────────────────────


def test_a_core_compile_produces_a_body_a_part_and_a_notice(
    hand_layer_path, kaikki_layer_path
):
    result = compile_core([hand_layer_path, kaikki_layer_path])
    assert result.ok
    assert set(result.outputs) == {SNAP, PART, NOTICE_FILENAME}


def test_share_alike_rows_are_not_in_the_main_body(hand_layer_path, kaikki_layer_path):
    """The separability proof, checked in the bytes (C-F3)."""
    result = compile_core([hand_layer_path, kaikki_layer_path])
    body_layers = {row[7] for row in rows_of(result.outputs[SNAP])}
    part_layers = {row[7] for row in rows_of(result.outputs[PART])}
    assert body_layers == {"core-hand"}
    assert part_layers == {"core-kaikki"}
    assert headers_of(result.outputs[SNAP])["layers"] == "core-hand=suite"
    assert headers_of(result.outputs[PART])["layers"] == "core-kaikki=CC-BY-SA-4.0"


def test_a_vzor_entry_expands_to_its_whole_paradigm(hand_layer_path):
    result = compile_core([hand_layer_path])
    forms = {row[0] for row in rows_of(result.outputs[SNAP]) if row[1] == "tržba"}
    assert {"tržba", "tržby", "tržbě", "tržbami"} <= forms


def test_kind_a_ambiguity_travels_in_one_row(hand_layer_path):
    """`tržby` is genitive singular and nominative and accusative plural — one
    row with a `;`-packed reading set, not three rows (contracts §1)."""
    result = compile_core([hand_layer_path])
    rows = [
        row
        for row in rows_of(result.outputs[SNAP])
        if row[0] == "tržby" and row[1] == "tržba"
    ]
    assert len(rows) == 1
    readings = rows[0][3].split(";")
    assert len(readings) >= 3
    assert readings == sorted(readings)


def test_a_full_form_entry_passes_through_with_no_vzor(hand_layer_path):
    result = compile_core([hand_layer_path])
    rows = [row for row in rows_of(result.outputs[SNAP]) if row[1] == "být"]
    assert {row[0] for row in rows} == {"být", "jsem", "je", "byl"}
    assert {row[4] for row in rows} == {""}


def test_a_decomposition_rides_the_flags_cell(hand_layer_path):
    result = compile_core([hand_layer_path])
    row = next(row for row in rows_of(result.outputs[SNAP]) if row[1] == "abych")
    assert row[5] == "parts:aby+bych"


def test_flags_reach_the_row(hand_layer_path):
    result = compile_core([hand_layer_path])
    row = next(row for row in rows_of(result.outputs[SNAP]) if row[0] == "faktuře")
    assert row[4] == "žena"
    assert row[5] == "palatal"


# ── ranks ────────────────────────────────────────────────────────────────────


def test_an_explicit_rank_wins(hand_layer_path):
    result = compile_core([hand_layer_path])
    ranks = {row[6] for row in rows_of(result.outputs[SNAP]) if row[1] == "tržba"}
    assert ranks == {"3"}


def test_without_a_frequency_table_every_rank_is_zero(hand_layer_path):
    result = compile_core([hand_layer_path])
    ranks = {row[6] for row in rows_of(result.outputs[SNAP]) if row[1] == "rok"}
    assert ranks == {"0"}


def test_the_frequency_table_ranks_by_count(tmp_path, hand_layer_path):
    freq = tmp_path / "cac-freq.tsv"
    freq.write_text("rok\t900\nbyt\t400\nnemoc\t50\n", encoding="utf-8")
    result = compile_core([hand_layer_path], frequencies=read_frequencies(freq))
    rank = {
        row[1]: row[6] for row in rows_of(result.outputs[SNAP]) if row[1] in
        {"rok", "byt", "nemoc"}
    }
    assert rank == {"rok": "1", "byt": "2", "nemoc": "3"}


def test_a_lemma_the_table_never_saw_ranks_last_not_first(tmp_path, hand_layer_path):
    """0 would sort ahead of the most frequent word in the language."""
    freq = tmp_path / "cac-freq.tsv"
    freq.write_text("rok\t900\nbyt\t400\n", encoding="utf-8")
    result = compile_core([hand_layer_path], frequencies=read_frequencies(freq))
    ranks = {row[1]: int(row[6]) for row in rows_of(result.outputs[SNAP])}
    assert ranks["rok"] == 1
    assert ranks["porovnat"] > ranks["byt"]


def test_read_frequencies_ignores_comments_and_junk(tmp_path):
    path = tmp_path / "f.tsv"
    path.write_text("# header\nrok\t9\nbroken line\nbyt\tnot-a-number\n", "utf-8")
    assert read_frequencies(path) == {"rok": 1}


# ── merge ────────────────────────────────────────────────────────────────────


def test_a_later_layer_replaces_an_earlier_entry_with_a_diagnostic(
    tmp_path, hand_layer_path
):
    override = tmp_path / "domain.morph.yaml"
    override.write_text(
        "layer: domain-business\nversion: 1\nlanguage: cs\nlicense: suite\n"
        "entries:\n"
        "  - lemma: tržba\n    upos: NOUN\n    vzor: předseda\n"
        "    rank: 9\n    provenance: manual\n",
        encoding="utf-8",
    )
    result = compile_core([hand_layer_path, str(override)])
    assert "LM-MORPH-002" in [d.code for d in result.diagnostics]

    rows = [row for row in rows_of(result.outputs[SNAP]) if row[1] == "tržba"]
    assert {row[7] for row in rows} == {"domain-business"}
    assert {row[6] for row in rows} == {"9"}
    # The later layer takes the whole lexeme, not the cells it happened to
    # touch: no row of the replaced entry survives.
    assert {row[4] for row in rows} == {"předseda"}


def test_the_same_entry_in_the_same_layer_twice_never_reaches_the_artifact(
    tmp_path,
):
    path = tmp_path / "dup.morph.yaml"
    path.write_text(
        "layer: core-hand\nversion: 1\nlanguage: cs\nlicense: suite\n"
        "entries:\n"
        "  - lemma: rok\n    upos: NOUN\n    vzor: hrad\n    provenance: manual\n"
        "  - lemma: rok\n    upos: NOUN\n    vzor: stroj\n    provenance: manual\n",
        encoding="utf-8",
    )
    result = compile_core([str(path)])
    assert not result.ok


def test_layers_of_another_language_are_refused(tmp_path, hand_layer_path):
    path = tmp_path / "sk.morph.yaml"
    path.write_text(
        "layer: core-sk\nversion: 1\nlanguage: sk\nlicense: suite\nentries: []\n",
        encoding="utf-8",
    )
    result = compile_core([hand_layer_path, str(path)])
    assert not result.ok
    assert "one artifact, one language" in " ".join(
        d.message for d in result.diagnostics
    )


# ── provisional (LM-MORPH-003) ───────────────────────────────────────────────


def test_a_provisional_entry_is_refused_by_a_core_compile(tmp_path):
    path = tmp_path / "seed.morph.yaml"
    path.write_text(
        "layer: core-hand\nversion: 1\nlanguage: cs\nlicense: suite\n"
        "entries:\n"
        "  - lemma: rok\n    upos: NOUN\n    vzor: hrad\n    provenance: manual\n"
        "  - lemma: Tatrman\n    upos: PROPN\n    vzor: hrad-proper\n"
        "    provenance: llm\n    provisional: true\n",
        encoding="utf-8",
    )
    result = compile_core([str(path)])
    assert not result.ok
    assert "LM-MORPH-003" in [d.code for d in result.diagnostics]
    assert "Tatrman" not in result.outputs[SNAP]
    # The entry beside it is unaffected — the row is rejected, not the file.
    assert "rok" in result.outputs[SNAP]


def test_a_world_layer_is_refused_by_a_core_compile(world_layer_path):
    """Overlays are how a world gets its own material; there is no second way."""
    result = compile_core([world_layer_path])
    assert not result.ok
    assert "LM-MORPH-004" in [d.code for d in result.diagnostics]
    assert result.outputs[SNAP].count("\n") < 10  # header only, no rows


def test_an_overlay_compile_permits_provisional(world_layer_path):
    result = compile_layers(
        [world_layer_path],
        snapshot_version="0.1.0",
        output="dfp.morph.overlay",
        world="dfp",
    )
    assert result.ok
    text = result.outputs["dfp.morph.overlay"]
    assert text.startswith("#morph-overlay v1\n")
    assert headers_of(text)["world"] == "dfp"
    rows = [row for row in rows_of(text) if row[1] == "Tatrman"]
    assert rows and {row[8] for row in rows} == {"provisional"}
    assert {row[8] for row in rows_of(text) if row[1] == "Kaufland"} == {"lexicon"}


def test_an_overlay_carries_no_fold_index(world_layer_path):
    """The loader folds overlays at load — a world cannot be asked to keep a
    derived index current (B-F4-α)."""
    result = compile_layers(
        [world_layer_path],
        snapshot_version="0.1.0",
        output="dfp.morph.overlay",
        world="dfp",
    )
    assert "#fold-index" not in result.outputs["dfp.morph.overlay"]


def test_an_overlay_keeps_share_alike_layers_in_one_file(
    kaikki_layer_path, world_layer_path
):
    """A world gets one artifact; splitting it would leave its estate loading
    files it never asked for."""
    result = compile_layers(
        [kaikki_layer_path, world_layer_path],
        snapshot_version="0.1.0",
        output="dfp.morph.overlay",
        world="dfp",
    )
    assert set(result.outputs) == {"dfp.morph.overlay", NOTICE_FILENAME}


# ── the derived sections ─────────────────────────────────────────────────────


def test_the_fold_index_puts_colliding_forms_under_one_key(hand_layer_path):
    result = compile_core([hand_layer_path])
    index = dict(
        line.split("\t") for line in section(result.outputs[SNAP], "#fold-index")
    )
    assert index[fold("být")] == "byt,být"


def test_the_fold_index_covers_every_form(hand_layer_path):
    result = compile_core([hand_layer_path])
    text = result.outputs[SNAP]
    indexed = {
        form
        for line in section(text, "#fold-index")
        for form in line.split("\t")[1].split(",")
    }
    assert indexed == {row[0] for row in rows_of(text)}


def test_ne_exceptions_carry_the_whole_paradigm(hand_layer_path):
    """`nemocí` is exactly as un-strippable as `nemoc`, and the loader tests
    the surface form it was handed."""
    result = compile_core([hand_layer_path])
    exceptions = set(section(result.outputs[SNAP], "#ne-exceptions"))
    assert {"nemoc", "nemoci", "nemocí"} <= exceptions
    assert "rok" not in exceptions


def test_an_unmarked_layer_emits_no_ne_section(kaikki_layer_path):
    result = compile_core([kaikki_layer_path])
    assert "#ne-exceptions" not in result.outputs[PART]


# ── the bytes ────────────────────────────────────────────────────────────────


def test_the_header_hash_is_the_hash_of_the_rows_as_written(hand_layer_path):
    text = compile_core([hand_layer_path]).outputs[SNAP]
    rows = [
        line
        for line in text.splitlines()
        if "\t" in line and not line.startswith(("#", "form\t"))
    ]
    body = [line for line in rows if len(line.split("\t")) == 9]
    assert headers_of(text)["content-hash"] == content_hash(body)
    assert headers_of(text)["rows"] == str(len(body))


def test_rows_are_emitted_in_the_order_they_are_hashed_in(hand_layer_path):
    """Sorted by codepoint, so the file reads in the order the hash sees."""
    text = compile_core([hand_layer_path]).outputs[SNAP]
    rows = ["\t".join(cells) for cells in rows_of(text)]
    assert rows == sorted(rows)


def test_two_compiles_of_the_same_input_are_byte_identical(hand_layer_path):
    first = compile_core([hand_layer_path]).outputs
    second = compile_core([hand_layer_path]).outputs
    assert first == second


def test_layer_order_does_not_change_bytes_when_nothing_collides(
    hand_layer_path, kaikki_layer_path
):
    forward = compile_core([hand_layer_path, kaikki_layer_path]).outputs
    backward = compile_core([kaikki_layer_path, hand_layer_path]).outputs
    assert forward[SNAP] == backward[SNAP]
    assert forward[PART] == backward[PART]
