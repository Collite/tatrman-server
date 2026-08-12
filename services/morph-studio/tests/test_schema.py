# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.2 T2 — the store round-trips, and its constraints are real.

A schema test that only checked columns exist would pass against a schema with
no constraints at all. What is asserted here is the two uniqueness rules that
carry design decisions — entry identity per layer (contracts §3 + LM-10) and one
queue item per `(world, token)` (contracts §6's dedup key) — and that the audit
trail records what a change replaced rather than only that it happened.
"""

from __future__ import annotations

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from ttrmorph.enrich.cascade import LAYER_CORE, LAYER_WORLD

from morph_studio import status as st
from morph_studio import store
from morph_studio.models import Audit, Entry, EntryForm, QueueItem

from .conftest import WORLD


def test_an_entry_round_trips_with_its_generated_paradigm(session):
    entry = store.create_entry(
        session,
        lemma="Kaufland",
        upos="PROPN",
        layer=LAYER_WORLD,
        vzor="hrad-proper",
        status_=st.PROPOSED,
    )
    session.commit()

    read = session.get(Entry, entry.id)
    assert read.identity == ("Kaufland", "PROPN", LAYER_WORLD)
    assert read.status == st.PROPOSED
    forms = {row.form for row in read.forms}
    assert {"Kaufland", "Kauflandu", "Kauflandem"} <= forms
    assert "Kauflandě" not in forms, "hrad-proper's whole narrowing is the locative"


def test_forms_carry_the_fold_so_a_diacritics_less_search_finds_them(session):
    store.create_entry(
        session, lemma="tržba", upos="NOUN", layer=LAYER_CORE, vzor="žena"
    )
    session.commit()

    folded = session.scalars(
        select(EntryForm.folded).where(EntryForm.form == "tržba")
    ).all()
    assert folded == ["trzba"]


def test_one_entry_per_lemma_upos_and_layer(session):
    store.create_entry(
        session, lemma="tržba", upos="NOUN", layer=LAYER_CORE, vzor="žena"
    )
    session.commit()

    # The same lexeme in a DIFFERENT layer is a different entry: LM-10 routing
    # is exactly the decision of which one is the real one.
    store.create_entry(
        session, lemma="tržba", upos="NOUN", layer=LAYER_WORLD, vzor="žena"
    )
    session.commit()

    assert session.scalar(select(Entry).where(Entry.layer == LAYER_WORLD)) is not None

    # ...and a raw duplicate is refused by the database, not merely avoided by
    # the code path that usually creates entries.
    session.add(
        Entry(
            lemma="tržba",
            upos="NOUN",
            layer=LAYER_CORE,
            vzor="žena",
            flags=[],
            status=st.PROPOSED,
        )
    )
    with pytest.raises(IntegrityError):
        session.commit()
    session.rollback()


def test_one_queue_item_per_world_and_token(session):
    store.ingest(session, world=WORLD, token="Kauflandu", run=False)
    session.commit()

    session.add(
        QueueItem(world=WORLD, token="Kauflandu", status=st.PROPOSED, proposal={})
    )
    with pytest.raises(IntegrityError):
        session.commit()
    session.rollback()

    # The same token in another world is another item, with its own history
    # (LM-5/S-4 — queues never cross worlds).
    store.ingest(session, world="other", token="Kauflandu", run=False)
    session.commit()
    assert session.scalar(select(QueueItem).where(QueueItem.world == "other"))


def test_ingesting_a_known_token_moves_the_counter_and_nothing_else(session):
    first, created = store.ingest(session, world=WORLD, token="Kauflandu", run=False)
    first.status = st.REJECTED
    session.commit()

    again, created_again = store.ingest(
        session, world=WORLD, token="Kauflandu", count=4, run=False
    )
    session.commit()

    assert created and not created_again
    assert again.id == first.id
    assert again.count == 5
    assert again.status == st.REJECTED, "a verdict is not undone by seeing the word"


def test_the_audit_row_records_what_the_change_replaced(session):
    entry = store.create_entry(
        session, lemma="Kaufland", upos="PROPN", layer=LAYER_WORLD, vzor="hrad-proper"
    )
    store.set_status(session, entry, st.VERIFIED, actor="bora", reason="checked")
    session.commit()

    rows = session.scalars(
        select(Audit).where(Audit.subject == "entry", Audit.subject_id == entry.id)
    ).all()
    change = next(row for row in rows if row.action == "status")
    assert change.detail["was"] == st.PROPOSED
    assert change.detail["now"] == st.VERIFIED
    assert change.detail["reason"] == "checked"
    assert change.actor == "bora"


def test_deleting_an_entry_takes_its_forms(session):
    entry = store.create_entry(
        session, lemma="tržba", upos="NOUN", layer=LAYER_CORE, vzor="žena"
    )
    session.commit()
    entry_id = entry.id

    session.delete(entry)
    session.commit()

    assert session.scalars(
        select(EntryForm).where(EntryForm.entry_id == entry_id)
    ).all() == []
