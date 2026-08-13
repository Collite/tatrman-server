# SPDX-License-Identifier: Apache-2.0
"""`NOTICE-morph.md` — generated from the same block the compiler enforces.

The test that matters is the last one: a share-alike layer cannot reach the
artifact without reaching the NOTICE, because both read the one `attribution:`
block and the compiler refuses the layer if it is missing (`LM-MORPH-004`).
"""

from __future__ import annotations

from ttrmorph.compile.snapshot import NOTICE_FILENAME, compile_layers


def notice(paths):
    return compile_layers(
        paths, snapshot_version="0.1.0", output="cs.morph.snap"
    ).outputs[NOTICE_FILENAME]


def test_a_suite_only_compile_says_so_plainly(hand_layer_path):
    text = notice([hand_layer_path])
    assert "suite-licensed material only" in text


def test_every_cc_by_sa_field_reaches_the_notice(hand_layer_path, kaikki_layer_path):
    text = notice([hand_layer_path, kaikki_layer_path])
    for expected in (
        "core-kaikki.morph.part",
        "kaikki.org",
        "CC-BY-SA-4.0",
        "https://creativecommons.org/licenses/by-sa/4.0/",
        "2026-08-11",
        "regenerated",  # the modification statement CC BY-SA asks for
    ):
        assert expected in text, expected


def test_the_notice_names_the_member_file_the_rows_are_actually_in(
    hand_layer_path, kaikki_layer_path
):
    result = compile_layers(
        [hand_layer_path, kaikki_layer_path],
        snapshot_version="0.1.0",
        output="cs.morph.snap",
    )
    text = result.outputs[NOTICE_FILENAME]
    for name in result.outputs:
        if name.endswith(".morph.part"):
            assert f"`{name}`" in text


def test_sections_are_ordered_by_layer_id(tmp_path, kaikki_layer_path):
    second = tmp_path / "core-cac.morph.yaml"
    second.write_text(
        (tmp_path.parent and "")
        + "layer: core-cac\nversion: 1\nlanguage: cs\nlicense: CC-BY-SA-4.0\n"
        "attribution:\n"
        "  source: UD_Czech-CAC\n"
        "  url: https://universaldependencies.org/\n"
        "  license: CC-BY-SA-4.0\n"
        "  license_url: https://creativecommons.org/licenses/by-sa/4.0/\n"
        "  extracted: '2026-08-11'\n"
        "  transformation: frequency counts and lemma inventory only\n"
        "entries: []\n",
        encoding="utf-8",
    )
    text = notice([kaikki_layer_path, str(second)])
    assert text.index("core-cac") < text.index("core-kaikki")
