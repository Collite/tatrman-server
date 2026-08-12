# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.2 T6 — FI-7 surfaces 1 and 2: lookup, the editor, try-pattern, ask-LLM."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from ttrmorph.enrich.cascade import LAYER_CORE, LAYER_WORLD
from ttrmorph.enrich.llm import LlmUnavailable

from morph_studio import status as st
from morph_studio.api import create_app

from .conftest import a_leg


def new_entry(client, **body) -> dict:
    payload = {"lemma": "tržba", "upos": "NOUN", "layer": LAYER_CORE, "vzor": "žena"}
    response = client.post("/v1/entries", json={**payload, **body})
    assert response.status_code == 201, response.text
    return response.json()


# ── surface 1: look a word up ────────────────────────────────────────────────


def test_lookup_finds_an_inflected_form_of_a_stored_entry(client):
    new_entry(client)

    body = client.get("/v1/lookup/tržbami").json()
    assert body["matched_via"] == "exact"
    assert [e["lemma"] for e in body["entries"]] == ["tržba"]


def test_lookup_says_when_it_answered_through_the_fold(client):
    """The case where a wrong entry looks right, so it is never merged in."""
    new_entry(client)

    body = client.get("/v1/lookup/trzbami").json()
    assert body["matched_via"] == "folded"
    assert [e["lemma"] for e in body["entries"]] == ["tržba"]


def test_lookup_of_an_unknown_form_is_an_empty_answer_not_a_404(client):
    body = client.get("/v1/lookup/nikdynevidene").json()
    assert body["entries"] == []


# ── surface 2: the entry editor ──────────────────────────────────────────────


def test_a_new_entry_arrives_at_proposed_with_its_paradigm(client):
    entry = new_entry(client)

    assert entry["status"] == st.PROPOSED
    assert entry["source"] == "human"
    assert {f["form"] for f in entry["forms"]} >= {"tržba", "tržby", "tržbami"}


def test_a_new_entry_may_be_typed_out_by_hand(client):
    """The third affordance: no pattern fits, so a person writes the forms."""
    entry = new_entry(
        client,
        lemma="abych",
        upos="SCONJ",
        vzor=None,
        forms=[{"form": "abych", "feats": "Mood=Cnd|Number=Sing|Person=1"}],
    )

    assert entry["vzor"] is None
    assert [f["form"] for f in entry["forms"]] == ["abych"]


def test_an_invented_pattern_is_a_400_and_says_where_the_list_is(client):
    response = client.post(
        "/v1/entries",
        json={"lemma": "tržba", "upos": "NOUN", "layer": LAYER_CORE, "vzor": "zena"},
    )
    assert response.status_code == 400
    assert "/v1/vzory" in response.json()["detail"]


def test_an_invented_layer_is_a_400(client):
    response = client.post(
        "/v1/entries",
        json={"lemma": "tržba", "upos": "NOUN", "layer": "domain", "vzor": "žena"},
    )
    assert response.status_code == 400


def test_creating_the_same_lexeme_twice_returns_the_one_that_exists(client):
    first = new_entry(client)
    again = new_entry(client)
    assert first["id"] == again["id"]


def test_the_pattern_inventory_is_served_for_the_ui_to_build_its_picker(client):
    body = client.get("/v1/vzory").json()

    names = {vzor["name"] for vzor in body["vzory"]}
    assert {"žena", "hrad-proper", "adj-ova"} <= names
    assert "fleeting-e" in body["flags"]
    hrad_proper = next(v for v in body["vzory"] if v["name"] == "hrad-proper")
    assert hrad_proper["parent"] == "hrad"
    assert hrad_proper["hints"]["capitalized"] is True


# ── try-pattern ──────────────────────────────────────────────────────────────


def test_try_pattern_generates_a_table_without_saving_it(client):
    entry = new_entry(client, lemma="Kaufland", upos="PROPN", layer=LAYER_WORLD,
                      vzor="hrad")

    body = client.post(
        f"/v1/entries/{entry['id']}/try-pattern", json={"vzor": "hrad-proper"}
    ).json()

    assert {f["form"] for f in body["forms"]} >= {"Kaufland", "Kauflandu"}
    assert client.get(f"/v1/entries/{entry['id']}").json()["vzor"] == "hrad"


def test_try_pattern_saves_when_asked_and_replaces_the_forms(client):
    entry = new_entry(client, lemma="Kaufland", upos="PROPN", layer=LAYER_WORLD,
                      vzor="hrad")

    client.post(
        f"/v1/entries/{entry['id']}/try-pattern",
        json={"vzor": "hrad-proper", "apply": True},
    )

    saved = client.get(f"/v1/entries/{entry['id']}").json()
    assert saved["vzor"] == "hrad-proper"
    forms = {f["form"] for f in saved["forms"]}
    assert "Kauflandě" not in forms, "the old pattern's forms are gone, not merged"


def test_a_pattern_the_citation_form_cannot_take_is_the_engine_s_refusal(client):
    """`žena` expects a citation form ending in -a. *Kaufland* does not."""
    entry = new_entry(client, lemma="Kaufland", upos="PROPN", layer=LAYER_WORLD,
                      vzor="hrad")

    response = client.post(
        f"/v1/entries/{entry['id']}/try-pattern", json={"vzor": "žena"}
    )
    assert response.status_code == 400
    assert "not a validation rule the studio invented" in response.json()["detail"]


def test_correcting_the_table_marks_the_forms_as_a_human_s(client):
    entry = new_entry(client)

    corrected = client.post(
        f"/v1/entries/{entry['id']}/forms",
        json={"forms": [{"form": "tržba", "feats": "Case=Nom|Number=Sing"}]},
    ).json()

    assert [f["form"] for f in corrected["forms"]] == ["tržba"]
    assert corrected["forms"][0]["corrected"] is True


# ── ask-LLM ──────────────────────────────────────────────────────────────────


@pytest.fixture
def with_leg(settings):
    answer = {"lemma": "Kaufland", "upos": "PROPN", "vzor": "hrad-proper", "flags": []}
    app = create_app(settings, llm=a_leg(answer), schema=True)
    with TestClient(app) as client:
        yield client


def test_ask_llm_returns_a_proposal_with_its_generated_table(with_leg):
    entry = new_entry(with_leg, lemma="Kaufland", upos="PROPN", layer=LAYER_WORLD,
                      vzor="hrad")

    body = with_leg.post(f"/v1/entries/{entry['id']}/ask-llm").json()

    assert body["vzor"] == "hrad-proper"
    assert {f["form"] for f in body["forms"]} >= {"Kauflandu"}
    assert body["validates"] is True


def test_ask_llm_does_not_save_anything_by_itself(with_leg):
    """The UI shows the proposal as a diff first (FI-7 surface 2)."""
    entry = new_entry(with_leg, lemma="Kaufland", upos="PROPN", layer=LAYER_WORLD,
                      vzor="hrad")

    with_leg.post(f"/v1/entries/{entry['id']}/ask-llm")

    assert with_leg.get(f"/v1/entries/{entry['id']}").json()["vzor"] == "hrad"


def test_a_gateway_that_will_not_answer_is_a_502(settings):
    app = create_app(settings, llm=a_leg(LlmUnavailable("gateway down")), schema=True)
    with TestClient(app) as client:
        entry = new_entry(client)
        response = client.post(f"/v1/entries/{entry['id']}/ask-llm")

    assert response.status_code == 502


def test_asking_about_an_entry_that_is_not_there_is_a_404(with_leg):
    assert with_leg.post("/v1/entries/999/ask-llm").status_code == 404


# ── health ───────────────────────────────────────────────────────────────────


def test_health_and_ready_name_the_world_this_instance_serves(client):
    assert client.get("/healthz").json()["world"] == "dfp"
    assert client.get("/readyz").json()["status"] == "ok"


def test_the_openapi_document_is_served_for_the_frontend_codegen(client):
    """NLS-P9.3 T1 generates its API client from exactly this."""
    document = client.get("/openapi.json").json()

    assert document["info"]["title"] == "morph-studio"
    assert "/v1/queue/{item_id}/verdict" in document["paths"]
    assert "QueueItemModel" in document["components"]["schemas"]
