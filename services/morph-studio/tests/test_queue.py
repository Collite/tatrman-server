# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.2 T2/T6 — ingest, the cascade over the store, routing, verdicts.

This is the life of *Kauflandu* (detailed-design §9) as a service test: a miss
arrives, the cascade proposes, the engine confirms, LM-10 routes it, and a
reviewer's verdict decides what it becomes.
"""

from __future__ import annotations

from sqlalchemy import select
from ttrmorph.enrich.cascade import LAYER_CORE, LAYER_WORLD

from morph_studio import status as st
from morph_studio.api import create_app
from morph_studio.models import Audit, Entry, QueueItem

from .conftest import KAUFLANDU, WORLD, a_leg, report


def ingest(client, *reports) -> dict:
    response = client.post("/v1/ingest", json={"reports": list(reports)})
    assert response.status_code == 200, response.text
    return response.json()


# ── ingest ───────────────────────────────────────────────────────────────────


def test_a_miss_becomes_a_queue_item_with_the_cascade_s_answer(client):
    body = ingest(client, report(KAUFLANDU))
    assert body == {
        "accepted": 1,
        "created": 1,
        "updated": 0,
        "rejected": [],
        "overlay_emitted": False,
        "reload": "",
    }

    (item,) = client.get("/v1/queue").json()["items"]
    assert item["token"] == KAUFLANDU
    assert item["status"] == st.AUTO_VALIDATED
    assert item["layer"] == LAYER_WORLD
    assert item["routed_by"] == "auto"
    assert item["cascade"]["tier"] == "guesser"
    assert item["cascade"]["proposals"][0]["lemma"] == "Kaufland"


def test_the_auto_validated_proposal_becomes_a_provisional_world_entry(client, session):
    ingest(client, report(KAUFLANDU))

    entry = session.scalar(select(Entry).where(Entry.lemma == "Kaufland"))
    assert entry.layer == LAYER_WORLD
    assert entry.status == st.AUTO_VALIDATED
    assert entry.provisional == 1, "Q-7: live in the world overlay, unverified"
    assert entry.source == "guesser"
    assert entry.provenance == "manual", "the suite's own engine, not an LLM answer"
    assert {row.form for row in entry.forms} >= {"Kaufland", "Kauflandu"}


def test_a_token_the_cascade_cannot_settle_waits_at_proposed(client, session):
    ingest(client, report("loňském"))

    (item,) = client.get("/v1/queue").json()["items"]
    assert item["status"] == st.PROPOSED
    assert item["cascade"]["tier"] == "human"
    assert item["cascade"]["proposals"], "unsure is not the same as nothing to show"
    assert session.scalar(select(Entry)) is None, "nothing is created until a verdict"


def test_a_typo_queues_with_nothing_to_show_and_is_not_an_error(client):
    ingest(client, report("%%%"))

    (item,) = client.get("/v1/queue").json()["items"]
    assert item["status"] == st.PROPOSED
    assert item["cascade"]["proposals"] == []


def test_a_second_sighting_only_moves_the_counter(client):
    ingest(client, report(KAUFLANDU))
    body = ingest(client, report(KAUFLANDU, count=3))

    assert (body["created"], body["updated"]) == (0, 1)
    (item,) = client.get("/v1/queue").json()["items"]
    assert item["count"] == 4


def test_the_cascade_does_not_run_twice_on_one_token(client, session):
    """Re-running it would overwrite the proposal a reviewer is looking at."""
    ingest(client, report(KAUFLANDU))
    ingest(client, report(KAUFLANDU))

    rows = session.scalars(
        select(Audit).where(Audit.subject == "queue_item", Audit.action == "cascade")
    ).all()
    assert len(rows) <= 1


def test_another_world_s_row_is_rejected_per_row(client):
    """LM-5/S-4 — and a misrouted line must not discard the ones that were right."""
    body = ingest(
        client, {"world": "other", "token": "Kauflandu"}, report("Microsoftu")
    )

    assert body["accepted"] == 1
    assert body["created"] == 1
    assert len(body["rejected"]) == 1
    assert "is not this instance's" in body["rejected"][0]


def test_there_is_no_cross_world_queue_to_ask_for(client):
    assert client.get("/v1/queue", params={"world": "other"}).status_code == 400


# ── the LLM tie-break, through the service ───────────────────────────────────


def test_the_configured_leg_breaks_a_tie_and_auto_validates(settings, session):
    answer = {"lemma": "loňský", "upos": "ADJ", "vzor": "mladý", "flags": []}
    app = create_app(settings, llm=a_leg(answer), schema=True)
    from fastapi.testclient import TestClient

    with TestClient(app) as client:
        ingest(client, report("loňském"))
        (item,) = client.get("/v1/queue").json()["items"]

    assert item["status"] == st.AUTO_VALIDATED
    assert item["cascade"]["tier"] == "llm"
    assert item["cascade"]["agreed"] is True
    assert item["layer"] == LAYER_CORE, "an adjective is core vocabulary, not a name"


def test_a_deployment_with_no_leg_says_so_rather_than_pretending(client):
    created = client.post(
        "/v1/entries",
        json={"lemma": "tržba", "upos": "NOUN", "layer": LAYER_CORE, "vzor": "žena"},
    ).json()

    response = client.post(f"/v1/entries/{created['id']}/ask-llm")
    assert response.status_code == 503
    assert "supported arrangement" in response.json()["detail"]


# ── verdicts ─────────────────────────────────────────────────────────────────


def verdict(client, item_id: int, **body) -> dict:
    response = client.post(f"/v1/queue/{item_id}/verdict", json=body)
    assert response.status_code == 200, response.text
    return response.json()


def test_verify_promotes_the_entry_and_makes_it_permanent(client, session):
    ingest(client, report(KAUFLANDU))
    (item,) = client.get("/v1/queue").json()["items"]

    body = verdict(client, item["id"], action="verify", actor="bora")

    assert body["item"]["status"] == st.VERIFIED
    assert body["entry"]["status"] == st.VERIFIED
    assert body["entry"]["provisional"] is False


def test_verify_from_the_queue_creates_the_entry_a_reviewer_was_shown(client, session):
    """FI-7 surface 3's ergonomics: verify without opening the editor."""
    ingest(client, report("loňském"))
    (item,) = client.get("/v1/queue").json()["items"]
    assert item["entry_id"] is None

    body = verdict(client, item["id"], action="verify")

    assert body["entry"]["lemma"] == item["cascade"]["proposals"][0]["lemma"]
    assert body["entry"]["status"] == st.VERIFIED


def test_verify_refuses_an_entry_whose_paradigm_lacks_the_observed_token(client):
    """LM-14, re-run at the verdict — whoever proposed it, the engine decides."""
    ingest(client, report(KAUFLANDU))
    (item,) = client.get("/v1/queue").json()["items"]
    entry_id = item["entry_id"]

    # A reviewer re-patterns it as an indeclinable, whose whole paradigm is the
    # citation form — so `Kauflandu` is no longer one of its forms.
    applied = client.post(
        f"/v1/entries/{entry_id}/try-pattern",
        json={"vzor": "indeclinable-m", "flags": [], "apply": True},
    )
    assert applied.status_code == 200

    refused = client.post(
        f"/v1/queue/{item['id']}/verdict", json={"action": "verify"}
    )
    assert refused.status_code == 409
    assert "does not contain" in refused.json()["detail"]


def test_there_is_nothing_to_verify_when_nothing_was_proposed(client):
    ingest(client, report("%%%"))
    (item,) = client.get("/v1/queue").json()["items"]

    response = client.post(f"/v1/queue/{item['id']}/verdict", json={"action": "verify"})
    assert response.status_code == 400
    assert "author the entry first" in response.json()["detail"]


def test_reject_is_terminal_for_the_token_as_well_as_the_entry(client, session):
    ingest(client, report(KAUFLANDU))
    (item,) = client.get("/v1/queue").json()["items"]

    body = verdict(client, item["id"], action="reject", reason="not ours")
    assert body["item"]["status"] == st.REJECTED
    assert body["entry"]["status"] == st.REJECTED

    # The front keeps reporting the token; the verdict has to survive that.
    ingest(client, report(KAUFLANDU, count=9))
    (again,) = client.get("/v1/queue").json()["items"]
    assert again["status"] == st.REJECTED
    assert again["count"] == 10


def test_route_is_the_human_override_and_is_audited(client, session):
    ingest(client, report(KAUFLANDU))
    (item,) = client.get("/v1/queue").json()["items"]
    assert item["layer"] == LAYER_WORLD

    body = verdict(client, item["id"], action="route", layer=LAYER_CORE, actor="bora")

    assert body["item"]["layer"] == LAYER_CORE
    assert body["item"]["routed_by"] == "human"
    assert body["entry"]["layer"] == LAYER_CORE
    assert body["entry"]["provisional"] is False, "Q-7 is narrow: never the core"

    rows = session.scalars(select(Audit).where(Audit.action == "reroute")).all()
    assert rows and rows[0].actor == "bora"


def test_an_unknown_verdict_is_a_400(client):
    ingest(client, report(KAUFLANDU))
    (item,) = client.get("/v1/queue").json()["items"]

    response = client.post(
        f"/v1/queue/{item['id']}/verdict", json={"action": "maybe"}
    )
    assert response.status_code == 400


def test_a_verdict_on_a_missing_item_is_a_404(client):
    assert (
        client.post("/v1/queue/999/verdict", json={"action": "verify"}).status_code
        == 404
    )


def test_the_queue_is_ordered_by_how_often_the_front_saw_the_word(client):
    ingest(client, report("Microsoftu"), report(KAUFLANDU, count=7))

    tokens = [item["token"] for item in client.get("/v1/queue").json()["items"]]
    assert tokens[0] == KAUFLANDU


def test_status_counts_what_is_in_the_store(client):
    ingest(client, report(KAUFLANDU), report("%%%"))

    body = client.get("/v1/status").json()
    assert body["world"] == WORLD
    assert body["queue"][st.AUTO_VALIDATED] == 1
    assert body["queue"][st.PROPOSED] == 1
    assert body["entries"][st.AUTO_VALIDATED] == 1
    assert body["llm"] is False


def test_the_queue_item_keeps_its_link_to_the_entry(client, session):
    ingest(client, report(KAUFLANDU))
    item = session.scalar(select(QueueItem))
    assert item.entry_id is not None
    assert session.get(Entry, item.entry_id).lemma == "Kaufland"
