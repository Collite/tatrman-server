# SPDX-License-Identifier: Apache-2.0
"""Layer files: what the schema accepts and what the licence boundary refuses.

The negatives here are the ones that cost something real if they pass. A
share-alike layer holding suite work does not fail at runtime and does not fail
in review — it fails in a licence audit, long after the release, and by then
the artifact is deployed. That is why `LM-MORPH-004` is an ERROR and why these
tests exist before the compiler that would otherwise happily emit it.
"""

from __future__ import annotations

import pytest
import yaml

from ttrmorph.compile.layers import Layer, read_layer, validate_layers

SUITE = {
    "layer": "core-hand",
    "version": 1,
    "language": "cs",
    "license": "suite",
    "attribution": None,
}

SHARE_ALIKE_ATTRIBUTION = {
    "source": "Wiktionary",
    "url": "https://kaikki.org/",
    "license": "CC-BY-SA-4.0",
    "license_url": "https://creativecommons.org/licenses/by-sa/4.0/",
    "extracted": "2026-08-11",
    "transformation": "classified to (vzor, flags) and regenerated",
}


def write(tmp_path, name="layer.morph.yaml", **overrides):
    document = {**SUITE, "entries": [], **overrides}
    path = tmp_path / name
    path.write_text(
        yaml.safe_dump(document, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )
    return path


def codes(diagnostics):
    return [d.code for d in diagnostics]


# ── the fixtures load ────────────────────────────────────────────────────────


def test_the_fixture_layers_are_clean(layer_paths):
    """Every shipped fixture validates — they are the compiler's input."""
    diagnostics = validate_layers(layer_paths)
    assert [d for d in diagnostics if d.severity == "ERROR"] == []


def test_a_hand_layer_parses_into_entries(hand_layer_path):
    layer, diagnostics = read_layer(hand_layer_path)
    assert isinstance(layer, Layer)
    assert layer.license == "suite"
    assert not layer.share_alike
    assert ("tržba", "NOUN") in {entry.identity for entry in layer.entries}
    assert [d for d in diagnostics if d.severity == "ERROR"] == []


# ── schema negatives ─────────────────────────────────────────────────────────


def test_an_unknown_key_is_refused(tmp_path):
    path = write(
        tmp_path,
        entries=[
            {
                "lemma": "tržba",
                "upos": "NOUN",
                "vzor": "žena",
                "provenance": "manual",
                "priority": 7,
            }
        ],
    )
    layer, diagnostics = read_layer(path)
    assert layer is None
    assert codes(diagnostics) == ["LM-MORPH-001"]
    assert "priority" in diagnostics[0].message


def test_a_missing_provenance_is_refused(tmp_path):
    """Provenance is required per entry (contracts §3) — it is what the
    licence boundary is enforced against, so an entry without one cannot be
    placed in any layer at all."""
    path = write(
        tmp_path,
        entries=[{"lemma": "tržba", "upos": "NOUN", "vzor": "žena"}],
    )
    layer, diagnostics = read_layer(path)
    assert layer is None
    assert codes(diagnostics) == ["LM-MORPH-001"]


def test_an_entry_with_neither_vzor_nor_forms_is_refused(tmp_path):
    path = write(
        tmp_path,
        entries=[{"lemma": "tržba", "upos": "NOUN", "provenance": "manual"}],
    )
    layer, diagnostics = read_layer(path)
    assert layer is None
    assert "neither" in diagnostics[0].message


def test_an_unknown_license_is_refused(tmp_path):
    path = write(tmp_path, license="MIT")
    layer, diagnostics = read_layer(path)
    assert layer is None
    assert codes(diagnostics) == ["LM-MORPH-001"]


def test_a_duplicated_identity_within_one_layer_is_refused(tmp_path):
    entry = {"lemma": "tržba", "upos": "NOUN", "vzor": "žena", "provenance": "manual"}
    path = write(tmp_path, entries=[entry, dict(entry)])
    _, diagnostics = read_layer(path)
    assert "LM-MORPH-001" in codes(diagnostics)
    assert "already defined" in " ".join(d.message for d in diagnostics)


def test_a_file_that_is_not_a_mapping_is_refused(tmp_path):
    path = tmp_path / "list.morph.yaml"
    path.write_text("- lemma: tržba\n", encoding="utf-8")
    layer, diagnostics = read_layer(path)
    assert layer is None
    assert codes(diagnostics) == ["LM-MORPH-001"]


def test_a_missing_file_is_a_diagnostic_not_a_crash(tmp_path):
    layer, diagnostics = read_layer(tmp_path / "nope.morph.yaml")
    assert layer is None
    assert codes(diagnostics) == ["LM-MORPH-001"]


# ── the licence boundary (LM-MORPH-004) ──────────────────────────────────────


def test_a_share_alike_layer_may_not_hold_manual_work(tmp_path):
    path = write(
        tmp_path,
        layer="core-kaikki",
        license="CC-BY-SA-4.0",
        attribution=SHARE_ALIKE_ATTRIBUTION,
        entries=[
            {
                "lemma": "tržba",
                "upos": "NOUN",
                "vzor": "žena",
                "provenance": "manual",
            }
        ],
    )
    _, diagnostics = read_layer(path)
    assert "LM-MORPH-004" in codes(diagnostics)
    assert all(d.severity == "ERROR" for d in diagnostics if d.code == "LM-MORPH-004")


def test_a_share_alike_layer_may_not_hold_llm_work(tmp_path):
    path = write(
        tmp_path,
        layer="core-kaikki",
        license="CC-BY-SA-4.0",
        attribution=SHARE_ALIKE_ATTRIBUTION,
        entries=[
            {"lemma": "tržba", "upos": "NOUN", "vzor": "žena", "provenance": "llm"}
        ],
    )
    _, diagnostics = read_layer(path)
    assert "LM-MORPH-004" in codes(diagnostics)


def test_a_share_alike_layer_without_attribution_is_refused(tmp_path):
    path = write(
        tmp_path,
        layer="core-kaikki",
        license="CC-BY-SA-4.0",
        attribution=None,
        entries=[
            {
                "lemma": "zákazník",
                "upos": "NOUN",
                "vzor": "pán",
                "provenance": "wiktionary",
            }
        ],
    )
    _, diagnostics = read_layer(path)
    assert "LM-MORPH-004" in codes(diagnostics)


def test_an_attribution_naming_another_license_is_refused(tmp_path):
    path = write(
        tmp_path,
        layer="core-cac",
        license="CC-BY-SA-4.0",
        attribution={**SHARE_ALIKE_ATTRIBUTION, "license": "CC-BY-4.0"},
        entries=[],
    )
    _, diagnostics = read_layer(path)
    assert "LM-MORPH-004" in codes(diagnostics)


def test_a_partial_attribution_block_is_refused(tmp_path):
    """No defaults on `Attribution`: a NOTICE assembled from half a block looks
    like compliance and is not."""
    partial = dict(SHARE_ALIKE_ATTRIBUTION)
    del partial["transformation"]
    path = write(
        tmp_path,
        layer="core-kaikki",
        license="CC-BY-SA-4.0",
        attribution=partial,
    )
    layer, diagnostics = read_layer(path)
    assert layer is None
    assert codes(diagnostics) == ["LM-MORPH-001"]


def test_a_suite_layer_may_hold_any_provenance(tmp_path):
    path = write(
        tmp_path,
        entries=[
            {"lemma": "tržba", "upos": "NOUN", "vzor": "žena", "provenance": provenance}
            for provenance in ("manual", "llm", "wiktionary", "cac")
        ],
    )
    _, diagnostics = read_layer(path)
    # Four entries with one identity between them: the duplicate check fires,
    # the licence boundary does not.
    assert "LM-MORPH-004" not in codes(diagnostics)


# ── the engine checks ────────────────────────────────────────────────────────


def test_an_unknown_vzor_is_a_diagnostic(tmp_path):
    path = write(
        tmp_path,
        entries=[
            {
                "lemma": "tržba",
                "upos": "NOUN",
                "vzor": "nosorožec",
                "provenance": "manual",
            }
        ],
    )
    _, diagnostics = read_layer(path)
    assert codes(diagnostics) == ["LM-MORPH-001"]


def test_an_unknown_flag_is_a_diagnostic(tmp_path):
    path = write(
        tmp_path,
        entries=[
            {
                "lemma": "tržba",
                "upos": "NOUN",
                "vzor": "žena",
                "flags": ["sparkling"],
                "provenance": "manual",
            }
        ],
    )
    _, diagnostics = read_layer(path)
    assert codes(diagnostics) == ["LM-MORPH-001"]


def test_declared_forms_that_do_not_regenerate_raise_005(tmp_path):
    path = write(
        tmp_path,
        entries=[
            {
                "lemma": "tržba",
                "upos": "NOUN",
                "vzor": "žena",
                "provenance": "wiktionary",
                "forms": [{"form": "tržbotron", "feats": "Case=Gen|Number=Plur"}],
            }
        ],
    )
    _, diagnostics = read_layer(path)
    assert "LM-MORPH-005" in codes(diagnostics)
    mismatch = next(d for d in diagnostics if d.code == "LM-MORPH-005")
    assert "tržbotron" in mismatch.message


def test_declared_forms_that_do_regenerate_raise_only_the_both_note(tmp_path):
    path = write(
        tmp_path,
        entries=[
            {
                "lemma": "tržba",
                "upos": "NOUN",
                "vzor": "žena",
                "provenance": "wiktionary",
                "forms": [
                    {"form": "tržby", "feats": "Case=Gen|Gender=Fem|Number=Sing"}
                ],
            }
        ],
    )
    _, diagnostics = read_layer(path)
    assert "LM-MORPH-005" not in codes(diagnostics)
    assert codes(diagnostics) == ["LM-MORPH-001"]
    assert diagnostics[0].severity == "INFO"


# ── subvzor sugar ────────────────────────────────────────────────────────────


def test_subvzor_sugar_resolves_to_the_pattern(tmp_path):
    path = write(
        tmp_path,
        entries=[
            {
                "lemma": "Kaufland",
                "upos": "PROPN",
                "vzor": "hrad",
                "flags": ["subvzor:hrad-proper"],
                "provenance": "manual",
            }
        ],
    )
    layer, diagnostics = read_layer(path)
    assert layer is not None
    assert [d for d in diagnostics if d.severity == "ERROR"] == []


def test_a_subvzor_of_another_pattern_is_refused(tmp_path):
    path = write(
        tmp_path,
        entries=[
            {
                "lemma": "Kaufland",
                "upos": "PROPN",
                "vzor": "žena",
                "flags": ["subvzor:hrad-proper"],
                "provenance": "manual",
            }
        ],
    )
    _, diagnostics = read_layer(path)
    assert codes(diagnostics) == ["LM-MORPH-001"]
    assert "belongs to" in diagnostics[0].message


# ── validate == what compile would say ───────────────────────────────────────


@pytest.mark.parametrize("as_list", [list, tuple])
def test_validate_layers_reports_every_file(tmp_path, as_list, hand_layer_path):
    bad = write(tmp_path, license="MIT")
    diagnostics = validate_layers(list(as_list([str(hand_layer_path), str(bad)])))
    assert codes(diagnostics) == ["LM-MORPH-001"]
    assert diagnostics[0].source == str(bad)
