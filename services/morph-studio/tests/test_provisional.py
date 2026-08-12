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
