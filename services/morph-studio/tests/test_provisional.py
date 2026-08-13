# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.2 T7 — Q-7: the provisional overlay, and the life of *Kauflandu*.

The claim under test is not "a file was written". It is that the file the studio
writes is one **the front's own loader reads**, that the row carries
`provisional` provenance into `Lookup` so a consumer can tell, that verifying
makes it permanent, that rejecting retracts it — and that none of it ever
touches a core snapshot.

So `ttrnlp.morph.load_morph` — the runtime's loader, not a parser written for
this test — is what every assertion goes through. A test that read the YAML back
would prove the studio agrees with itself.
"""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml
from fastapi.testclient import TestClient
from ttrmorph.enrich.cascade import LAYER_CORE, LAYER_WORLD
from ttrnlp.morph import load_morph

from morph_studio import status as st
from morph_studio import store
from morph_studio.api import create_app
from morph_studio.config import Settings
from morph_studio.provisional import emit_overlay, overlay_name, source_name

from .conftest import KAUFLANDU, WORLD, report

PROVENANCE_PROVISIONAL = "provisional"
PROVENANCE_LEXICON = "lexicon"


@pytest.fixture
def overlay_dir(tmp_path) -> Path:
    return tmp_path / "overlay"


@pytest.fixture
def q7(tmp_path, overlay_dir):
    """A studio with Q-7 on and nowhere to send a reload (as every test is)."""
    settings = Settings(
        world=WORLD,
        db_url="sqlite+pysqlite:///:memory:",
        export_dir=str(tmp_path / "export"),
        overlay_dir=str(overlay_dir),
        provisional=True,
    )
    app = create_app(settings, schema=True)
    with TestClient(app) as client:
        yield client, settings, app


def served(core_snapshot: Path, overlay_dir: Path):
    """What the FRONT would see: its own loader over core + this overlay.

    Core first, overlay second — the order `load_morph` requires, and the rule
    that stops a deployment serving a world's vocabulary as the published
    lexicon (NL-15).
    """
    compiled = overlay_dir / overlay_name(WORLD)
    if not compiled.exists():
        return None
    return load_morph([str(core_snapshot), str(compiled)])


def ingest(client, *reports):
    response = client.post("/v1/ingest", json={"reports": list(reports)})
    assert response.status_code == 200, response.text
    return response.json()


# ── the lifecycle ────────────────────────────────────────────────────────────


def test_an_auto_validated_name_goes_live_provisionally(q7, overlay_dir, core_snapshot):
    """detailed-design §9: the miss is answered before anybody reviews it."""
    client, _, _ = q7
    body = ingest(client, report(KAUFLANDU))

    assert body["overlay_emitted"] is True
    state = served(core_snapshot, overlay_dir)
    result = state.lookup("Kauflandu")

    assert result is not None, "the front now resolves the word that missed"
    analyses = [a for a in result.analyses if a.lemma == "Kaufland"]
    assert analyses, "the overlay carries the lemma the guesser proposed"
    assert {a.upos for a in analyses} == {"PROPN"}
    assert {a.provenance for a in analyses} == {PROVENANCE_PROVISIONAL}


def test_verifying_makes_the_same_rows_permanent(q7, overlay_dir, core_snapshot):
    client, _, _ = q7
    ingest(client, report(KAUFLANDU))
    (item,) = client.get("/v1/queue").json()["items"]

    response = client.post(
        f"/v1/queue/{item['id']}/verdict", json={"action": "verify", "actor": "bora"}
    )
    assert response.status_code == 200
    assert response.json()["overlay_emitted"] is True

    result = served(core_snapshot, overlay_dir).lookup("Kauflandu")
    analyses = [a for a in result.analyses if a.lemma == "Kaufland"]
    assert analyses
    assert {a.provenance for a in analyses} == {PROVENANCE_LEXICON}, (
        "verified is not provisional"
    )


def test_rejecting_retracts_the_rows_from_the_served_overlay(
    q7, overlay_dir, core_snapshot
):
    client, _, _ = q7
    ingest(client, report(KAUFLANDU))
    assert served(core_snapshot, overlay_dir).lookup("Kauflandu") is not None

    (item,) = client.get("/v1/queue").json()["items"]
    client.post(
        f"/v1/queue/{item['id']}/verdict",
        json={"action": "reject", "reason": "not ours"},
    )

    assert served(core_snapshot, overlay_dir).lookup("Kauflandu") is None


def test_a_retraction_is_named_in_the_file_it_disappeared_from(q7, overlay_dir):
    client, _, _ = q7
    ingest(client, report(KAUFLANDU))
    (item,) = client.get("/v1/queue").json()["items"]
    client.post(f"/v1/queue/{item['id']}/verdict", json={"action": "reject"})

    text = (overlay_dir / source_name(WORLD)).read_text(encoding="utf-8")
    assert "RETRACTED in this emission:" in text
    assert "Kaufland (PROPN)" in text


def test_routing_a_name_to_the_core_takes_it_out_of_the_overlay(
    q7, overlay_dir, core_snapshot
):
    """LM-10's override, and Q-7's narrowness, in one move."""
    client, _, _ = q7
    ingest(client, report(KAUFLANDU))
    (item,) = client.get("/v1/queue").json()["items"]

    client.post(
        f"/v1/queue/{item['id']}/verdict",
        json={"action": "route", "layer": LAYER_CORE},
    )

    assert served(core_snapshot, overlay_dir).lookup("Kauflandu") is None


# ── the narrow ruling ────────────────────────────────────────────────────────


def test_a_core_entry_is_never_in_the_overlay_whatever_its_status(
    q7, overlay_dir, core_snapshot
):
    """⚑ The whole of what makes Q-7 narrow, asserted rather than intended."""
    client, settings, app = q7
    factory = app.state.sessionmaker
    session = factory()
    # A lemma the core fixture does NOT carry, so anything found is the
    # overlay's doing and not the snapshot's.
    store.create_entry(
        session,
        lemma="sazba",
        upos="NOUN",
        layer=LAYER_CORE,
        vzor="žena",
        status_=st.AUTO_VALIDATED,
        provisional=True,  # a lie, and the emitter must not believe it
    )
    session.commit()

    emit_overlay(session, settings)
    session.close()

    assert served(core_snapshot, overlay_dir).lookup("sazba") is None


def test_an_unverified_entry_that_is_not_provisional_does_not_serve(
    q7, overlay_dir, core_snapshot
):
    client, settings, app = q7
    session = app.state.sessionmaker()
    store.create_entry(
        session,
        lemma="Microsoft",
        upos="PROPN",
        layer=LAYER_WORLD,
        vzor="hrad-proper",
        status_=st.PROPOSED,
        provisional=False,
    )
    session.commit()

    emit_overlay(session, settings)
    session.close()

    assert served(core_snapshot, overlay_dir).lookup("Microsoftu") is None


def test_q7_off_serves_only_what_a_human_verified(tmp_path, core_snapshot):
    settings = Settings(
        world=WORLD,
        db_url="sqlite+pysqlite:///:memory:",
        overlay_dir=str(tmp_path / "overlay"),
        provisional=False,
    )
    app = create_app(settings, schema=True)
    with TestClient(app) as client:
        ingest(client, report(KAUFLANDU))

    state = served(core_snapshot, tmp_path / "overlay")
    assert state is None or state.lookup("Kauflandu") is None


def test_no_overlay_dir_means_no_emission_and_no_error(client):
    """The default. Plenty of deployments publish through the layer-file lane."""
    body = ingest(client, report(KAUFLANDU))
    assert body["overlay_emitted"] is False
    assert body["reload"] == ""


# ── the emitted file itself ──────────────────────────────────────────────────


def test_the_overlay_source_is_a_world_layer_the_compiler_accepts(q7, overlay_dir):
    client, _, _ = q7
    ingest(client, report(KAUFLANDU))

    document = yaml.safe_load(
        (overlay_dir / source_name(WORLD)).read_text(encoding="utf-8")
    )
    assert document["license"] == f"world:{WORLD}"
    (entry,) = document["entries"]
    assert entry["lemma"] == "Kaufland"
    assert entry["provisional"] is True


def test_the_emission_reports_what_it_did(q7, overlay_dir):
    client, settings, app = q7
    ingest(client, report(KAUFLANDU))

    session = app.state.sessionmaker()
    result = emit_overlay(session, settings)
    session.close()

    assert result.enabled and result.provisional == 1 and result.permanent == 0
    assert any(name.endswith(overlay_name(WORLD)) for name in result.written)


def test_a_reload_target_that_is_not_there_is_reported_not_raised(
    tmp_path, core_snapshot
):
    """The overlay on disk is the durable half; a failed reload costs latency."""
    settings = Settings(
        world=WORLD,
        db_url="sqlite+pysqlite:///:memory:",
        overlay_dir=str(tmp_path / "overlay"),
        front_target="127.0.0.1:1",  # nothing listens here
    )
    app = create_app(settings, schema=True)
    with TestClient(app) as client:
        body = ingest(client, report(KAUFLANDU))

    assert body["overlay_emitted"] is True
    assert "reload" in body["reload"] or "front" in body["reload"]
    assert served(core_snapshot, tmp_path / "overlay").lookup("Kauflandu") is not None


# ── what the review of 2026-08-13 found ──────────────────────────────────────


def test_an_entry_with_no_vzor_still_compiles_into_the_overlay(
    q7, overlay_dir, core_snapshot
):
    """⚑ The one that freezes a world.

    `emit_overlay` rendered every entry as a `vzor:` document. An entry with no
    vzor — an LLM-proposed irregular, anything a reviewer authored as bare forms
    — therefore emitted `vzor: ""`, which the compiler refuses as "neither
    'vzor' nor 'forms'". That is not a bad emission but a permanent one: the
    overlay stops compiling, so it stops being replaced, and the world is stuck
    on whatever it last served for as long as that entry exists.
    """
    client, settings, app = q7
    created = client.post(
        "/v1/entries",
        json={
            "lemma": "dveře",
            "upos": "NOUN",
            "layer": LAYER_WORLD,
            "forms": [
                {"form": "dveře", "feats": "Case=Nom|Number=Plur"},
                {"form": "dveří", "feats": "Case=Gen|Number=Plur"},
            ],
            "actor": "bora",
        },
    )
    assert created.status_code == 201, created.text
    entry_id = created.json()["id"]
    assert client.post(
        f"/v1/entries/{entry_id}/status",
        json={"status": st.VERIFIED, "actor": "bora"},
    ).status_code == 200

    session = app.state.sessionmaker()
    result = emit_overlay(session, settings)
    session.close()

    assert result.compiled, result.notes
    state = served(core_snapshot, overlay_dir)
    assert state is not None
    hit = state.lookup("dveří")
    assert hit is not None and any(a.lemma == "dveře" for a in hit.analyses)


def test_corrected_forms_are_what_the_overlay_serves(q7, overlay_dir, core_snapshot):
    """A reviewer's correction is the entry's truth. Re-emitting the pattern
    they overruled would serve the exact paradigm they rejected."""
    client, settings, app = q7
    ingest(client, report(KAUFLANDU))
    (entry,) = [
        e for e in client.get("/v1/entries").json() if e["lemma"] == "Kaufland"
    ]

    corrected = client.post(
        f"/v1/entries/{entry['id']}/forms",
        json={
            "forms": [
                {"form": "Kaufland", "feats": "Case=Nom|Number=Sing"},
                {"form": "Kauflandu", "feats": "Case=Gen|Number=Sing"},
                {"form": "KAUFLANDEM", "feats": "Case=Ins|Number=Sing"},
            ],
            "actor": "bora",
        },
    )
    assert corrected.status_code == 200, corrected.text

    state = served(core_snapshot, overlay_dir)
    forms = {
        form
        for form, analyses in state.exact.items()
        if any(a.lemma == "Kaufland" for a in analyses)
    }
    assert forms == {"Kaufland", "Kauflandu", "KAUFLANDEM"}, (
        "exactly the corrected table — not the eleven-form paradigm the vzor "
        "makes, which is what the reviewer overruled by correcting it"
    )


def test_an_editor_rejection_retracts_from_the_overlay(q7, overlay_dir, core_snapshot):
    """The queue's verdict endpoint always re-emitted; the entry editor's status
    endpoint did not, so a rejection made there left the front serving the
    retracted word until some unrelated ingest happened to fire."""
    client, _, _ = q7
    ingest(client, report(KAUFLANDU))
    assert served(core_snapshot, overlay_dir).lookup("Kauflandu") is not None
    (entry,) = [
        e for e in client.get("/v1/entries").json() if e["lemma"] == "Kaufland"
    ]

    response = client.post(
        f"/v1/entries/{entry['id']}/status",
        json={"status": st.REJECTED, "actor": "bora", "reason": "not ours"},
    )
    assert response.status_code == 200, response.text
    assert served(core_snapshot, overlay_dir).lookup("Kauflandu") is None


def test_a_compile_failure_leaves_every_served_file_untouched(q7, overlay_dir):
    """⚑ Both halves: nothing on disk changes, and the caller is not told the
    front has a new lexicon. The source used to be written *before* the compile
    that rejected it, so the file the docstring promised to leave alone was the
    first casualty."""
    client, settings, app = q7
    ingest(client, report(KAUFLANDU))
    before = {
        path.name: path.read_text(encoding="utf-8")
        for path in overlay_dir.iterdir()
        if path.is_file()
    }
    assert before

    # A world entry the compiler must refuse: `LM-MORPH-001`, unknown pattern.
    session = app.state.sessionmaker()
    entry = store.create_entry(
        session,
        lemma="rozvaha",
        upos="NOUN",
        layer=LAYER_WORLD,
        vzor="žena",
        status_=st.VERIFIED,
    )
    entry.vzor = "no-such-vzor"
    session.commit()
    result = emit_overlay(session, settings)
    session.close()

    assert not result.compiled
    assert any("did NOT compile" in note for note in result.notes)
    after = {
        path.name: path.read_text(encoding="utf-8")
        for path in overlay_dir.iterdir()
        if path.is_file()
    }
    assert after == before, "not the compiled overlay, and not the layer source"


def test_the_overlay_directory_never_holds_a_partial_file(q7, overlay_dir):
    """The front is fail-all: a torn read costs the world its whole lexicon.
    Files arrive by `os.replace`, so a reader sees the old one or the new one —
    and nothing is left behind by the staging that produced them."""
    client, _, _ = q7
    ingest(client, report(KAUFLANDU))

    names = sorted(path.name for path in overlay_dir.iterdir())
    assert names == sorted([overlay_name(WORLD), source_name(WORLD)])
    assert not any(path.name.startswith(".") for path in overlay_dir.iterdir())
