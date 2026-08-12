# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.2 T2 — every legal edge of LM-14, and every illegal one.

The illegal half is the half that matters. `proposed → published` is the edge
whose absence is the export gate; if it ever exists, an unreviewed generated
paradigm reaches an artifact that ships to every deployment, and no test of the
happy path notices.
"""

from __future__ import annotations

import itertools

import pytest
from ttrmorph.enrich.cascade import LAYER_CORE, LAYER_WORLD

from morph_studio import status as st
from morph_studio import store

LEGAL = [
    (st.PROPOSED, st.AUTO_VALIDATED),
    (st.PROPOSED, st.VERIFIED),
    (st.PROPOSED, st.REJECTED),
    (st.AUTO_VALIDATED, st.VERIFIED),
    (st.AUTO_VALIDATED, st.REJECTED),
    (st.VERIFIED, st.PUBLISHED),
    (st.VERIFIED, st.REJECTED),
    (st.VERIFIED, st.SHADOWED),
    (st.PUBLISHED, st.REJECTED),
    (st.PUBLISHED, st.SHADOWED),
    (st.SHADOWED, st.PUBLISHED),
    (st.SHADOWED, st.REJECTED),
]

ILLEGAL = [
    pair
    for pair in itertools.product(st.STATUSES, repeat=2)
    if pair not in LEGAL
]


@pytest.mark.parametrize(("current", "wanted"), LEGAL)
def test_every_legal_edge(current, wanted):
    st.check(current, wanted)
    assert st.can(current, wanted)


@pytest.mark.parametrize(("current", "wanted"), ILLEGAL)
def test_every_illegal_edge(current, wanted):
    with pytest.raises(st.IllegalTransition):
        st.check(current, wanted)
    assert not st.can(current, wanted)


def test_the_edge_that_would_defeat_the_export_gate_is_the_one_missing():
    """Stated on its own, because it is the reason the table exists."""
    assert not st.can(st.PROPOSED, st.PUBLISHED)
    assert not st.can(st.AUTO_VALIDATED, st.PUBLISHED)
    assert st.can(st.VERIFIED, st.PUBLISHED)


def test_rejected_is_terminal_and_says_why():
    with pytest.raises(st.IllegalTransition, match="rejected is terminal"):
        st.check(st.REJECTED, st.PROPOSED)


def test_a_status_outside_the_machine_is_a_different_error():
    """400, not 409: the caller is wrong rather than out of date."""
    with pytest.raises(st.UnknownStatus):
        st.check(st.PROPOSED, "approved")
    with pytest.raises(st.UnknownStatus):
        st.check("draft", st.VERIFIED)


def test_the_gate_is_verified_and_above():
    assert not st.exportable(st.PROPOSED)
    assert not st.exportable(st.AUTO_VALIDATED)
    assert not st.exportable(st.REJECTED)
    assert st.exportable(st.VERIFIED)
    assert st.exportable(st.PUBLISHED)
    assert st.exportable(st.SHADOWED)


# ── over HTTP ────────────────────────────────────────────────────────────────


def test_an_illegal_transition_is_409_over_the_api(client):
    created = client.post(
        "/v1/entries",
        json={"lemma": "Kaufland", "upos": "PROPN", "layer": LAYER_WORLD,
              "vzor": "hrad-proper"},
    ).json()

    ok = client.post(
        f"/v1/entries/{created['id']}/status", json={"status": st.VERIFIED}
    )
    assert ok.status_code == 200
    assert ok.json()["status"] == st.VERIFIED

    refused = client.post(
        f"/v1/entries/{created['id']}/status", json={"status": st.AUTO_VALIDATED}
    )
    assert refused.status_code == 409
    assert "cannot become" in refused.json()["detail"]


def test_an_unknown_status_is_400_over_the_api(client):
    created = client.post(
        "/v1/entries",
        json={"lemma": "tržba", "upos": "NOUN", "layer": LAYER_CORE, "vzor": "žena"},
    ).json()

    response = client.post(
        f"/v1/entries/{created['id']}/status", json={"status": "approved"}
    )
    assert response.status_code == 400


def test_verifying_clears_the_provisional_mark(session):
    """Q-7 ends where verification begins."""
    entry = store.create_entry(
        session,
        lemma="Kaufland",
        upos="PROPN",
        layer=LAYER_WORLD,
        vzor="hrad-proper",
        status_=st.AUTO_VALIDATED,
        provisional=True,
    )
    assert entry.provisional == 1

    store.set_status(session, entry, st.VERIFIED)
    assert entry.provisional == 0
