# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.2 T2/T6 — the export gate, and that what leaves is a real layer file.

An export test that only counted entries would pass on a file the compiler
rejects. So every assertion here runs `ttr-morph`'s own reader over the output:
"an analyst can read it" and "the compiler accepts it" are the same claim, and
this is where the studio's half of it is checked.
"""

from __future__ import annotations

import yaml
from ttrmorph.compile.layers import LICENSE_SUITE, read_layer
from ttrmorph.enrich.cascade import LAYER_CORE, LAYER_WORLD

from morph_studio import status as st
from morph_studio import store
from morph_studio.export import CORE_LAYER_ID, world_layer_id

from .conftest import WORLD


def entry_at(session, status_, **kwargs):
    entry = store.create_entry(
        session,
        lemma=kwargs.pop("lemma", "tržba"),
        upos=kwargs.pop("upos", "NOUN"),
        layer=kwargs.pop("layer", LAYER_CORE),
        vzor=kwargs.pop("vzor", "žena"),
        **kwargs,
    )
    if status_ != st.PROPOSED:
        # Walk the machine rather than assigning: a fixture that could reach a
        # status the machine cannot would test a service nobody ships.
        path = {
            st.AUTO_VALIDATED: [st.AUTO_VALIDATED],
            st.VERIFIED: [st.VERIFIED],
            st.PUBLISHED: [st.VERIFIED, st.PUBLISHED],
            st.REJECTED: [st.REJECTED],
            st.SHADOWED: [st.VERIFIED, st.SHADOWED],
        }[status_]
        for step in path:
            store.set_status(session, entry, step)
    session.commit()
    return entry


def export(client, **body) -> dict:
    response = client.post("/v1/export", json=body)
    assert response.status_code == 200, response.text
    return response.json()


# ── the gate ─────────────────────────────────────────────────────────────────


def test_only_verified_and_above_leave_the_database(client, session):
    entry_at(session, st.PROPOSED, lemma="sazba")
    entry_at(session, st.AUTO_VALIDATED, lemma="smlouva")
    entry_at(session, st.VERIFIED, lemma="tržba")
    entry_at(session, st.PUBLISHED, lemma="faktura")
    entry_at(session, st.REJECTED, lemma="položka")
    entry_at(session, st.SHADOWED, lemma="prodejna")

    body = export(client)
    layer, _ = read_layer_text(body["files"][f"{CORE_LAYER_ID}.morph.yaml"])
    lemmas = {entry.lemma for entry in layer.entries}

    assert lemmas == {"tržba", "faktura", "prodejna"}
    assert body["exported"] == 3
    assert body["withheld"] == {st.PROPOSED: 1, st.AUTO_VALIDATED: 1, st.REJECTED: 1}


def test_the_withheld_count_distinguishes_empty_from_not_ready(client, session):
    """"Nothing was exported" and "nothing is ready" are different answers."""
    assert export(client)["withheld"] == {}

    entry_at(session, st.AUTO_VALIDATED, lemma="smlouva")
    assert export(client)["withheld"] == {st.AUTO_VALIDATED: 1}


def test_the_two_layers_are_separate_files_with_separate_licences(client, session):
    entry_at(session, st.VERIFIED, lemma="tržba", layer=LAYER_CORE)
    entry_at(
        session,
        st.VERIFIED,
        lemma="Kaufland",
        upos="PROPN",
        layer=LAYER_WORLD,
        vzor="hrad-proper",
    )

    body = export(client)
    core, _ = read_layer_text(body["files"][f"{CORE_LAYER_ID}.morph.yaml"])
    world, _ = read_layer_text(body["files"][f"{world_layer_id(WORLD)}.morph.yaml"])

    assert core.license == "suite"
    assert world.license == f"world:{WORLD}"
    assert world.world == WORLD, "a world layer refuses to compile into the core"
    assert [e.lemma for e in core.entries] == ["tržba"]
    assert [e.lemma for e in world.entries] == ["Kaufland"]


def test_the_export_is_a_layer_file_the_compiler_reads(client, session):
    entry_at(session, st.VERIFIED, lemma="tržba")

    layer, diagnostics = read_layer_text(
        export(client)["files"][f"{CORE_LAYER_ID}.morph.yaml"]
    )
    assert diagnostics == []
    (entry,) = layer.entries
    assert (entry.lemma, entry.upos, entry.vzor) == ("tržba", "NOUN", "žena")
    assert entry.provenance == "manual"


def test_a_corrected_entry_exports_as_a_full_form_one(client, session):
    """⚑ `LM-MORPH-005`, avoided at the source.

    A reviewer edited the table, so the pattern no longer describes the entry.
    Writing `vzor: žena` would make the compiler regenerate the paradigm the
    reviewer rejected — and either raise, or ship it.
    """
    entry = entry_at(session, st.VERIFIED, lemma="tržba")
    client.post(
        f"/v1/entries/{entry.id}/forms",
        json={"forms": [{"form": "tržba", "feats": "Case=Nom|Number=Sing"},
                        {"form": "tržeb", "feats": "Case=Gen|Number=Plur"}]},
    )

    layer, _ = read_layer_text(export(client)["files"][f"{CORE_LAYER_ID}.morph.yaml"])
    (written,) = layer.entries
    assert [f.form for f in written.forms] == ["tržba", "tržeb"]
    assert written.vzor == "žena", "the overruled pattern stays visible"


def test_writing_the_export_puts_it_where_the_lane_reads_it(client, session, settings):
    entry_at(session, st.VERIFIED, lemma="tržba")

    body = export(client, write=True)
    assert body["written"]
    for path in body["written"]:
        assert path.startswith(settings.export_dir)


def test_a_preview_touches_no_disk(client, session, settings):
    entry_at(session, st.VERIFIED, lemma="tržba")
    from pathlib import Path

    assert export(client)["written"] == []
    assert not Path(settings.export_dir).exists()


def test_retractions_are_written_into_the_header(session, settings):
    """A removal in a two-thousand-line generated diff attributes to nobody."""
    from morph_studio.export import export_layers

    entry_at(session, st.VERIFIED, lemma="tržba")
    result = export_layers(session, settings, retractions=["Kaufland (PROPN)"])

    assert "RETRACTED" in result.files[f"{CORE_LAYER_ID}.morph.yaml"]
    assert "Kaufland (PROPN)" in result.files[f"{CORE_LAYER_ID}.morph.yaml"]


# ── the DFP lane (LM-12) ─────────────────────────────────────────────────────


def test_the_proposals_export_carries_exactly_what_the_gate_withholds(client, session):
    entry_at(session, st.PROPOSED, lemma="sazba")
    entry_at(session, st.AUTO_VALIDATED, lemma="smlouva")
    entry_at(session, st.VERIFIED, lemma="tržba")

    response = client.post("/v1/export/proposals", json={})
    assert response.status_code == 200
    body = response.json()

    # These three are core-routed (`entry_at` defaults to LAYER_CORE), so they
    # are in the core fragment — see the licence test below.
    layer, diagnostics = read_layer_text(
        body["files"][f"{CORE_LAYER_ID}-proposed.morph.yaml"]
    )
    assert diagnostics == []
    assert {e.lemma for e in layer.entries} == {"sazba", "smlouva"}
    assert body["exported"] == 2


def test_a_core_proposal_never_lands_in_the_world_s_fragment(client, session):
    """⚑ The licence boundary, on the lane that leaves the building.

    One fragment for everything below the gate put core-routed proposals into a
    file rendered under `world:<id>` — relabelled as that world's material, in
    that world's repository, under that world's licence. LM-10 routes to `core`
    precisely the entries that are *not* the world's.
    """
    entry_at(session, st.PROPOSED, lemma="sazba")  # core
    store.create_entry(
        session,
        lemma="Kaufland",
        upos="PROPN",
        layer=LAYER_WORLD,
        vzor="hrad-proper",
        status_=st.AUTO_VALIDATED,
    )

    files = client.post("/v1/export/proposals", json={}).json()["files"]
    core, _ = read_layer_text(files[f"{CORE_LAYER_ID}-proposed.morph.yaml"])
    world, _ = read_layer_text(files[f"{WORLD}-proposed.morph.yaml"])

    assert {e.lemma for e in core.entries} == {"sazba"}
    assert {e.lemma for e in world.entries} == {"Kaufland"}
    assert core.license == LICENSE_SUITE
    assert world.license == f"world:{WORLD}"


def test_the_proposals_file_says_what_it_is(client, session):
    entry_at(session, st.PROPOSED, lemma="sazba")

    text = client.post("/v1/export/proposals", json={}).json()["files"][
        f"{CORE_LAYER_ID}-proposed.morph.yaml"
    ]
    assert "NOT a publishable layer" in text
    assert "model-validator CLI" in text


def test_a_provisional_proposal_is_marked_in_the_fragment(client, session):
    """The DFP reviewer has to know which rows rejecting one would retract."""
    store.create_entry(
        session,
        lemma="Kaufland",
        upos="PROPN",
        layer=LAYER_WORLD,
        vzor="hrad-proper",
        status_=st.AUTO_VALIDATED,
        provisional=True,
    )
    session.commit()

    text = client.post("/v1/export/proposals", json={}).json()["files"][
        f"{WORLD}-proposed.morph.yaml"
    ]
    document = yaml.safe_load(text)
    (entry,) = document["entries"]
    assert entry["provisional"] is True


# ── helper ───────────────────────────────────────────────────────────────────


def read_layer_text(text: str):
    """Parse an exported layer with `ttr-morph`'s own reader."""
    import tempfile
    from pathlib import Path

    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "layer.morph.yaml"
        path.write_text(text, encoding="utf-8")
        layer, diagnostics = read_layer(path)
    assert layer is not None, [d.message for d in diagnostics]
    return layer, [d for d in diagnostics if d.severity == "ERROR"]
