# SPDX-License-Identifier: Apache-2.0
"""NLS-P7.2 — the snapshot loader (LM contracts §2/§3/§5/§9).

The fixture artifacts in `tests/fixtures/morph/` are hand-authored and small
enough to read in one screen: 28 rows of hero vocabulary plus the traps the
design named — the kind-(a) reading set, the kind-(b) two-lemma form, the
být/byt fold collision, a decomposition, and the ne- exception that keeps
*nemoc* from resolving as "not power".

Negatives are written to ``tmp_path`` rather than checked in. A broken artifact
in the fixtures directory is a loaded gun: every test that globs the directory
starts failing for a reason that has nothing to do with it.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

from ttrnlp.morph.diag import LM_MORPH_001, LM_MORPH_002, LM_MORPH_003
from ttrnlp.morph.records import MATCHED_EXACT, MATCHED_FOLDED
from ttrnlp.morph.snapshot import (
    COLUMNS,
    LoadError,
    MorphState,
    content_hash,
    load_morph,
)

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "morph"
SNAPSHOT = FIXTURES / "cs-test.morph.snap"
OVERLAY = FIXTURES / "world-test.morph.overlay"
REGEN = FIXTURES / "regen.py"


@pytest.fixture
def state() -> MorphState:
    return load_morph([SNAPSHOT])


@pytest.fixture
def state_with_overlay() -> MorphState:
    return load_morph([SNAPSHOT, OVERLAY])


# ── T1: header and row negatives -> LM-MORPH-001 ─────────────────────────────


def _write(tmp_path: Path, name: str, text: str) -> Path:
    path = tmp_path / name
    path.write_text(text, encoding="utf-8")
    return path


def _stamped(rows: list[str], *, header: list[str]) -> str:
    """A well-formed artifact around the rows given."""
    lines = list(header)
    lines.append("#content-hash: " + content_hash(rows))
    lines.append(f"#rows: {len(rows)}")
    lines.append("\t".join(COLUMNS))
    lines.extend(rows)
    return "\n".join(lines) + "\n"


CORE_HEADER = [
    "#morph-snapshot v1",
    "#language: cs",
    "#version: 1.2.3",
    "#layers: core-hand=suite",
]
OVERLAY_HEADER = [
    "#morph-overlay v1",
    "#language: cs",
    "#version: 1.2.3",
    "#world: acme",
]
ROW = "tržba\ttržba\tNOUN\tCase=Nom|Number=Sing\tžena\t\t0\tcore-hand\tlexicon"


def test_a_missing_magic_line_is_refused(tmp_path: Path):
    path = _write(tmp_path, "x.morph.snap", "#language: cs\n" + ROW + "\n")
    with pytest.raises(LoadError) as excinfo:
        load_morph([path])
    assert excinfo.value.codes == [LM_MORPH_001]
    assert "morph-snapshot v1" in str(excinfo.value)


def test_an_empty_file_is_refused(tmp_path: Path):
    with pytest.raises(LoadError) as excinfo:
        load_morph([_write(tmp_path, "x.morph.snap", "")])
    assert excinfo.value.codes == [LM_MORPH_001]


def test_a_tampered_content_hash_is_refused(tmp_path: Path):
    text = _stamped([ROW], header=CORE_HEADER).replace(
        ROW, ROW.replace("tržba\ttržba", "tržba\tvýnos")
    )
    with pytest.raises(LoadError) as excinfo:
        load_morph([_write(tmp_path, "x.morph.snap", text)])
    assert excinfo.value.codes == [LM_MORPH_001]
    assert "content-hash mismatch" in str(excinfo.value)


def test_a_wrong_row_count_is_refused(tmp_path: Path):
    """A truncated artifact is exactly what the count is for."""
    text = _stamped([ROW], header=CORE_HEADER).replace("#rows: 1", "#rows: 2")
    with pytest.raises(LoadError) as excinfo:
        load_morph([_write(tmp_path, "x.morph.snap", text)])
    assert "declares 2 rows" in str(excinfo.value)


def test_an_unknown_column_count_is_refused(tmp_path: Path):
    text = _stamped([ROW], header=CORE_HEADER).replace(
        "\t".join(COLUMNS), "\t".join(COLUMNS[:-1])
    )
    with pytest.raises(LoadError) as excinfo:
        load_morph([_write(tmp_path, "x.morph.snap", text)])
    assert excinfo.value.codes == [LM_MORPH_001]
    assert "contracts §2 fixes the columns" in str(excinfo.value)


def test_a_missing_header_key_is_refused(tmp_path: Path):
    text = _stamped([ROW], header=[h for h in CORE_HEADER if "layers" not in h])
    with pytest.raises(LoadError) as excinfo:
        load_morph([_write(tmp_path, "x.morph.snap", text)])
    assert "missing ['layers']" in str(excinfo.value)


def test_a_row_with_an_unknown_provenance_is_refused(tmp_path: Path):
    bad = ROW.replace("\tlexicon", "\tguessed")
    text = _stamped([bad], header=CORE_HEADER)
    with pytest.raises(LoadError) as excinfo:
        load_morph([_write(tmp_path, "x.morph.snap", text)])
    assert "unknown provenance" in str(excinfo.value)


def test_a_provisional_row_in_a_core_snapshot_is_an_error(tmp_path: Path):
    """LM-MORPH-003 — Q-7 is narrow, and this is where the line is drawn."""
    bad = ROW.replace("\tlexicon", "\tprovisional")
    text = _stamped([bad], header=CORE_HEADER)
    with pytest.raises(LoadError) as excinfo:
        load_morph([_write(tmp_path, "x.morph.snap", text)])
    assert excinfo.value.codes == [LM_MORPH_003]
    assert "never reach the published core artifact" in str(excinfo.value)


def test_a_provisional_row_in_an_overlay_is_fine_and_says_so(state_with_overlay):
    result = state_with_overlay.lookup("Kauflandem")
    assert result is not None
    assert [a.provenance for a in result.analyses] == ["provisional"]


def test_a_missing_file_is_a_diagnostic_not_a_crash(tmp_path: Path):
    with pytest.raises(LoadError) as excinfo:
        load_morph([tmp_path / "not-there.morph.snap"])
    assert excinfo.value.codes == [LM_MORPH_001]
    assert "cannot read morph source" in str(excinfo.value)


def test_an_overlay_in_the_core_position_is_refused():
    with pytest.raises(LoadError) as excinfo:
        load_morph([OVERLAY, SNAPSHOT])
    assert "must be a snapshot" in str(excinfo.value)


def test_no_sources_at_all_is_refused():
    with pytest.raises(LoadError):
        load_morph([])


# ── T1: fail-all ─────────────────────────────────────────────────────────────


def test_one_bad_overlay_loads_nothing(tmp_path: Path):
    """NL-15. Three good worlds and one broken file load NO worlds.

    The alternative looks kinder and is much worse: the service comes up
    healthy, answers most questions, and silently cannot answer the ones the
    broken file was for.
    """
    broken = _write(tmp_path, "broken.morph.overlay", "#morph-overlay v1\nnonsense\n")
    with pytest.raises(LoadError) as excinfo:
        load_morph([SNAPSHOT, OVERLAY, broken])
    assert LM_MORPH_001 in excinfo.value.codes
    # and the good sources produced no state — nothing to half-load into
    assert "broken.morph.overlay" in str(excinfo.value)


def test_every_diagnostic_is_reported_not_just_the_first(tmp_path: Path):
    one = _write(tmp_path, "a.morph.overlay", "not an artifact\n")
    two = _write(tmp_path, "b.morph.overlay", "also not\n")
    with pytest.raises(LoadError) as excinfo:
        load_morph([SNAPSHOT, one, two])
    assert len(excinfo.value.diagnostics) == 2


# ── T1: ambiguity, both kinds ────────────────────────────────────────────────


def test_kind_a_ambiguity_is_one_analysis_with_three_readings(state):
    result = state.lookup("tržby")
    assert result is not None
    assert result.matched_via == MATCHED_EXACT
    assert len(result.analyses) == 1
    analysis = result.analyses[0]
    assert analysis.lemma == "tržba"
    assert analysis.feats == frozenset(
        {
            "Case=Gen|Number=Sing",
            "Case=Nom|Number=Plur",
            "Case=Acc|Number=Plur",
        }
    )
    assert analysis.vzor == "žena"


def test_kind_b_ambiguity_is_two_candidates_in_rank_order(state):
    result = state.lookup("má")
    assert result is not None
    assert [(a.lemma, a.upos) for a in result.analyses] == [
        ("mít", "VERB"),
        ("můj", "DET"),
    ]
    assert result.lemma == "mít"


def test_a_decomposition_rides_the_analysis(state):
    result = state.lookup("abych")
    assert result is not None
    assert result.analyses[0].parts == ("aby", "bych")
    # …and the flag that carried it is not mistaken for an alternation flag
    assert result.analyses[0].flags == ()


# ── T4: lookup — exact, folded, and the ne- strip ────────────────────────────


def test_an_exact_hit_says_so(state):
    result = state.lookup("tržbami")
    assert result is not None and result.matched_via == MATCHED_EXACT


def test_a_folded_hit_resolves_through_the_index(state):
    result = state.lookup("trzby")
    assert result is not None
    assert result.matched_via == MATCHED_FOLDED
    assert result.lemma == "tržba"


def test_a_folded_hit_is_case_insensitive_too(state):
    assert state.lookup("TRZBY") is not None
    assert state.lookup("Tržby") is not None


def test_the_fold_collision_ranks_exact_before_folded(state):
    """B-F3. *byt* is a flat; *být* is "to be"; they fold together.

    Asking for the unaccented form is ambiguous by construction, so both come
    back — but the one that matched the form AS WRITTEN leads, because that is
    the stronger piece of evidence and the order is the only place the
    difference survives.
    """
    result = state.lookup("byt")
    assert result is not None
    assert result.matched_via == MATCHED_EXACT
    assert [a.lemma for a in result.analyses] == ["byt", "být"]


def test_an_accented_query_is_not_fold_expanded(state):
    """*být* was typed with diacritics: the writer already chose."""
    result = state.lookup("být")
    assert result is not None
    assert [a.lemma for a in result.analyses] == ["být"]


def test_the_ne_prefix_is_stripped_and_marked(state):
    result = state.lookup("nedostatek")
    assert result is not None
    assert result.analyses[0].lemma == "dostatek"
    assert all("Polarity=Neg" in reading for reading in result.analyses[0].feats)


def test_the_nej_prefix_is_the_superlative(state):
    """⚑ The list said Polarity=Neg for both prefixes; *nejlepší* is "best"."""
    result = state.lookup("nejlepší")
    assert result is not None
    assert result.analyses[0].lemma == "dobrý"
    assert all("Degree=Sup" in reading for reading in result.analyses[0].feats)


def test_an_ne_exception_is_not_stripped(state):
    """*nemoc* is an illness, not "not power" — and *moc* is right there."""
    result = state.lookup("nemoc")
    assert result is not None
    assert result.analyses[0].lemma == "nemoc"
    assert result.matched_via == MATCHED_EXACT


def test_a_form_that_is_nowhere_is_a_miss(state):
    assert state.lookup("Kauflandu") is None
    assert state.lookup("xyzzy") is None


def test_a_stripped_prefix_never_invents_a_word(state):
    """The strip only answers when the remainder is itself in the lexicon."""
    assert state.lookup("nekvalitní") is None


# ── T5: overlays ─────────────────────────────────────────────────────────────


def test_an_overlay_adds_its_world_vocabulary(state_with_overlay):
    result = state_with_overlay.lookup("Kauflandu")
    assert result is not None
    assert result.lemma == "Kaufland"
    assert result.analyses[0].provenance == "lexicon"


def test_overlay_forms_are_folded_at_load(state_with_overlay):
    """The overlay carries no fold index; the loader builds one for it."""
    result = state_with_overlay.lookup("kauflandu")
    assert result is not None
    assert result.matched_via == MATCHED_FOLDED
    assert result.lemma == "Kaufland"


def test_a_shadow_loads_and_says_which_row_did_it(state_with_overlay):
    codes = [d.code for d in state_with_overlay.diagnostics]
    assert codes == [LM_MORPH_002]
    message = state_with_overlay.diagnostics[0].message
    assert "world-test" in message and "moc" in message
    assert state_with_overlay.diagnostics[0].severity == "INFO"


def test_the_shadowing_entry_wins_every_form_of_the_entry(state_with_overlay):
    """Entry identity is (lemma, upos): shadowing takes the whole paradigm.

    A half-shadowed entry would answer from the core in one case and from the
    world in another, depending on which case the writer happened to use.
    """
    result = state_with_overlay.lookup("moc")
    assert result is not None
    assert len(result.analyses) == 1
    assert result.analyses[0].vzor == "žena"


def test_the_core_is_untouched_where_nothing_shadows_it(state_with_overlay):
    assert state_with_overlay.lookup("tržby").lemma == "tržba"


# ── T6: stats and immutability ───────────────────────────────────────────────


def test_stats_echo_the_manifest(state):
    stats = state.stats()
    assert stats.version == "0.0.0-test"
    assert stats.language == "cs"
    assert stats.rows == 28
    assert stats.layers == (("core-hand", "suite"), ("core-test", "suite"))
    assert stats.worlds == ()
    assert stats.content_hash.startswith("sha256:")


def test_stats_count_worlds_and_fold_collisions(state_with_overlay):
    stats = state_with_overlay.stats()
    assert stats.worlds == ("world-test",)
    assert stats.rows == 32
    # být/byt is the collision the fixture was built around.
    assert stats.fold_collisions == 1


def test_the_state_cannot_be_mutated(state):
    with pytest.raises(TypeError):
        state.exact["nový"] = ()
    with pytest.raises(TypeError):
        state.folded["novy"] = ("nový",)


def test_a_reload_builds_a_new_state_object(state):
    again = load_morph([SNAPSHOT])
    assert again is not state
    assert again.exact.keys() == state.exact.keys()


def test_a_returned_analysis_cannot_be_edited(state):
    import dataclasses

    result = state.lookup("tržby")
    with pytest.raises(dataclasses.FrozenInstanceError):
        result.analyses[0].lemma = "něco"  # type: ignore[misc]


# ── the fixtures' own derived fields ─────────────────────────────────────────


def test_the_fixture_hashes_are_current():
    """The p7-2 verify gate, as a test.

    A fixture whose header no longer matches its body is refused by the loader
    — correctly, and from a fixture, in CI, with a message about a corrupt
    artifact that is really just an un-regenerated file.
    """
    out = subprocess.run(
        [sys.executable, str(REGEN), "--check"], capture_output=True, text=True
    )
    assert out.returncode == 0, out.stdout + out.stderr


def test_the_fold_index_is_read_from_the_artifact_not_computed(state):
    """B-F4-α: the loader trusts the compiled index for the core snapshot.

    Proven by giving it an index that disagrees with the rows: if the loader
    were folding the rows itself, the planted entry would be ignored.
    """
    text = SNAPSHOT.read_text(encoding="utf-8").replace(
        "trzby\ttržby", "trzby\ttržby\nvymysleno\ttržby"
    )
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "planted.morph.snap"
        path.write_text(text, encoding="utf-8")
        planted = load_morph([path])
    assert planted.lookup("vymysleno") is not None
    assert planted.lookup("vymysleno").lemma == "tržba"
