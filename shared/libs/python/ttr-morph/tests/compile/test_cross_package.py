# SPDX-License-Identifier: Apache-2.0
"""The drift guard: what `ttr-morph` writes, `ttrnlp.morph` must read (T4).

The compiler and the loader are two packages with two release cadences, and the
only thing binding them is the format. A drift between them does not present as
a crash — it presents as a snapshot that "is corrupt" on the day it is
published, with nothing to say which of the two is wrong.

The hash function and the fold are *imported* from the wheel rather than matched
against it, for the same reason `fold` is: one implementation cannot drift from
itself. What that leaves unproven is everything else — the row canonicalisation,
the column order, the section spellings, the reading packing — which is what
these tests compile and load for real.
"""

from __future__ import annotations

import pytest
from ttrnlp.morph import load_morph
from ttrnlp.morph.snapshot import LoadError

from ttrmorph.compile.snapshot import NOTICE_FILENAME, compile_layers


@pytest.fixture
def compiled(tmp_path, hand_layer_path, kaikki_layer_path):
    """A core snapshot plus its share-alike member file, written to disk."""
    result = compile_layers(
        [hand_layer_path, kaikki_layer_path],
        snapshot_version="0.1.0",
        output="cs.morph.snap",
    )
    assert result.ok, [d.message for d in result.diagnostics]
    for name, text in result.outputs.items():
        (tmp_path / name).write_text(text, encoding="utf-8")
    return tmp_path


def test_the_loader_accepts_what_the_compiler_wrote(compiled):
    state = load_morph([str(compiled / "cs.morph.snap")])
    assert state.stats().version == "0.1.0"
    assert state.stats().language == "cs"


def test_the_member_file_loads_as_a_second_core_source(compiled):
    """C-F3: the share-alike part is core, not an overlay."""
    body = str(compiled / "cs.morph.snap")
    part = str(compiled / "core-kaikki.morph.part")

    alone = load_morph([body])
    assert alone.lookup("zákazníka") is None

    together = load_morph([body, part])
    result = together.lookup("zákazníka")
    assert result is not None
    assert result.lemma == "zákazník"

    stats = together.stats()
    assert stats.rows == alone.stats().rows + len(
        [
            line
            for line in (compiled / "core-kaikki.morph.part")
            .read_text(encoding="utf-8")
            .splitlines()
            if line.count("\t") == 8 and not line.startswith("form\t")
        ]
    )
    assert dict(stats.layers) == {"core-hand": "suite", "core-kaikki": "CC-BY-SA-4.0"}


def test_member_order_does_not_change_the_answer(compiled):
    body = str(compiled / "cs.morph.snap")
    part = str(compiled / "core-kaikki.morph.part")
    forward = load_morph([body, part])
    backward = load_morph([part, body])
    assert forward.exact.keys() == backward.exact.keys()
    assert forward.stats().rows == backward.stats().rows
    # No shadow diagnostics between core members: the compiler already merged.
    assert [d.code for d in forward.diagnostics] == []


def test_a_core_member_after_an_overlay_is_refused(
    compiled, tmp_path, world_layer_path
):
    overlay = compile_layers(
        [world_layer_path],
        snapshot_version="0.1.0",
        output="dfp.morph.overlay",
        world="dfp",
    )
    path = tmp_path / "dfp.morph.overlay"
    path.write_text(overlay.outputs["dfp.morph.overlay"], encoding="utf-8")

    load_morph(
        [
            str(compiled / "cs.morph.snap"),
            str(compiled / "core-kaikki.morph.part"),
            str(path),
        ]
    )
    with pytest.raises(LoadError) as caught:
        load_morph(
            [
                str(compiled / "cs.morph.snap"),
                str(path),
                str(compiled / "core-kaikki.morph.part"),
            ]
        )
    assert "LM-MORPH-001" in caught.value.codes


def test_the_hero_vocabulary_survives_the_round_trip(compiled):
    """S-7's shape, on the fixtures: the compiled artifact answers the hero's
    forms, and the diacritics-less twin answers through the compiled index."""
    state = load_morph(
        [str(compiled / "cs.morph.snap"), str(compiled / "core-kaikki.morph.part")]
    )

    tržby = state.lookup("tržby")
    assert tržby is not None and tržby.lemma == "tržba"
    assert tržby.matched_via == "exact"
    assert len(tržby.analyses[0].feats) >= 3  # kind-(a), one Analysis

    folded = state.lookup("trzby")
    assert folded is not None and folded.matched_via == "folded"
    assert folded.lemma == "tržba"

    assert state.lookup("porovnej").lemma == "porovnat"
    assert state.lookup("letošního").lemma == "letošní"


def test_the_fold_collision_ranks_the_exact_form_first(compiled):
    state = load_morph([str(compiled / "cs.morph.snap")])
    result = state.lookup("byt")
    assert result.matched_via == "exact"
    assert [a.lemma for a in result.analyses][0] == "byt"
    assert "být" in {a.lemma for a in result.analyses}


def test_the_decomposition_survives_as_analysis_parts(compiled):
    state = load_morph([str(compiled / "cs.morph.snap")])
    result = state.lookup("abych")
    assert result.analyses[0].parts == ("aby", "bych")
    assert result.analyses[0].flags == ()


def test_the_ne_exception_survives_the_round_trip(compiled):
    """The compiler writes the section, the loader reads it, and `nemoc` does
    not become "not power" in between."""
    state = load_morph([str(compiled / "cs.morph.snap")])
    assert state.lookup("nemoci").lemma == "nemoc"
    assert "nemoc" in state.ne_exceptions

    stripped = state.lookup("nerok")  # not an exception, and `rok` is loaded
    assert stripped is not None
    assert stripped.lemma == "rok"
    assert any("Polarity=Neg" in reading for reading in stripped.analyses[0].feats)


def test_a_notice_is_never_loaded_as_an_artifact(compiled):
    with pytest.raises(LoadError):
        load_morph([str(compiled / NOTICE_FILENAME)])
