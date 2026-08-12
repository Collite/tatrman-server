# SPDX-License-Identifier: Apache-2.0
"""The FastAPI surface (contracts §7; NLS-P9.2 T1/T6).

Thin by design: parse, call `store`/`export`/`provisional`, serialise. Every
rule about the lexicon lives behind those, so that the NLS-P9.3 bootstrap batch
— which drives the same cascade over thousands of tokens without an HTTP
request in sight — cannot reach a state these endpoints could not.

**REST, not gRPC** (⚑LMP-D5). This is an internal editorial tool: its consumer
is a Vue frontend generated from this OpenAPI document, and it is not on the
gRPC estate any runtime service talks to. The one place it crosses into the
estate is outward — `ReloadPacks` to the front, over the wheel's client.

**Errors are the machine's, not HTTP's, translated once.** An illegal status
change is 409 (the caller is out of date, and retrying after a refresh is
sensible); an unknown status or an unknown layer is 400 (the caller is wrong,
and retrying is not); a missing row is 404. Doing that per-endpoint is how a
UI ends up showing "500" for a reviewer double-clicking Verify.
"""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from sqlalchemy import func, select
from sqlalchemy.orm import Session
from ttrmorph.engine.tables import load as load_tables
from ttrmorph.enrich.cascade import LAYER_CORE, LAYER_WORLD
from ttrmorph.enrich.guesser import Proposal, paradigm, validates
from ttrmorph.enrich.llm import LlmError, LlmLeg

from morph_studio import export as export_module
from morph_studio import provisional as provisional_module
from morph_studio import status as st
from morph_studio import store
from morph_studio.config import Settings
from morph_studio.db import create_all, make_engine, make_sessionmaker
from morph_studio.models import Entry, QueueItem
from morph_studio.schemas import (
    CascadeModel,
    CorrectForms,
    EntryModel,
    ExportRequest,
    ExportResponse,
    FormModel,
    IngestRequest,
    IngestResponse,
    LookupResponse,
    NewEntry,
    ParadigmModel,
    QueueItemModel,
    QueueResponse,
    SetStatus,
    StatusResponse,
    TryPattern,
    Verdict,
    VerdictResponse,
)
from morph_studio.schemas import (
    ParadigmModel as ParadigmResponse,
)

logger = logging.getLogger(__name__)

def get_session(request: Request):
    """One session, one transaction, per request.

    ⚑ Module level, not a closure inside `create_app`. FastAPI resolves the
    string annotations `from __future__ import annotations` produces against the
    *module's* globals, so an `Annotated[Session, Depends(...)]` alias defined
    inside the factory is unresolvable — and FastAPI's fallback for an
    unresolvable parameter is to treat it as a query parameter, which turns
    every endpoint into a 422 asking the caller for `?session=`.
    """
    factory = request.app.state.sessionmaker
    session = factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


Db = Annotated[Session, Depends(get_session)]

ACTION_VERIFY = "verify"
ACTION_REJECT = "reject"
ACTION_ROUTE = "route"
ACTIONS = (ACTION_VERIFY, ACTION_REJECT, ACTION_ROUTE)


def create_app(
    settings: Settings,
    *,
    llm: LlmLeg | None = None,
    schema: bool = False,
) -> FastAPI:
    """The app for one world.

    Args:
        settings: This instance's world and connections.
        llm: The classifier leg, or None (air-gapped, or a test).
        schema: Create the tables from the models. For SQLite and tests; a
            deployment runs `alembic upgrade head` (see `db.create_all`).
    """
    engine = make_engine(settings.db_url)
    factory = make_sessionmaker(engine)
    if schema:
        create_all(engine)

    app = FastAPI(
        title="morph-studio",
        version="0.1.0",
        description=(
            "Editorial service for the Czech morphology lexicon (LM). PG working "
            "store, enrichment cascade, layer-file export. One instance per "
            "world (LM-5)."
        ),
    )
    app.state.settings = settings
    app.state.llm = llm
    app.state.engine = engine
    app.state.sessionmaker = factory

    # ── health ──────────────────────────────────────────────────────────────

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok", "world": settings.world}

    @app.get("/readyz")
    def readyz(session: Db) -> dict[str, str]:
        """Ready means the store answers. A studio that cannot reach its
        database is not degraded, it is unusable — every endpoint writes."""
        session.execute(select(func.count(Entry.id)))
        return {"status": "ok", "world": settings.world}

    @app.get("/v1/status", response_model=StatusResponse)
    def status_(session: Db) -> StatusResponse:
        return StatusResponse(
            world=settings.world,
            mode=settings.mode,
            ready=True,
            entries=_counts(session, Entry.status, Entry.id),
            queue=_counts(session, QueueItem.status, QueueItem.id),
            llm=app.state.llm is not None,
            provisional=settings.provisional,
        )

    @app.get("/v1/vzory")
    def vzory() -> dict[str, object]:
        """The closed inventory the UI's pattern picker is built from.

        Served rather than hard-coded in the frontend for the reason the
        inventory is data in the first place (LM-2): a sub-vzor added to
        `vzory.yaml` has to reach the picker without a frontend release, or the
        analyst who needs it cannot choose it.
        """
        tables = load_tables("cs")
        return {
            "language": tables.language,
            "flags": list(tables.flag_order),
            "vzory": [
                {
                    "name": name,
                    "upos": vzor.upos,
                    "parent": vzor.parent,
                    "implied_flags": list(vzor.implied_flags),
                    "hints": dict(vzor.hints),
                }
                for name, vzor in tables.vzory.items()
            ],
        }

    # ── FI-7 surface 1: look a word up ──────────────────────────────────────

    @app.get("/v1/lookup/{form}", response_model=LookupResponse)
    def lookup(form: str, session: Db) -> LookupResponse:
        matched_via, entries = store.lookup(session, form)
        return LookupResponse(
            form=form,
            matched_via=matched_via,
            entries=[_entry(entry) for entry in entries],
        )

    # ── FI-7 surface 2: the entry editor ────────────────────────────────────

    @app.get("/v1/entries", response_model=list[EntryModel])
    def list_entries(
        session: Db,
        status_filter: Annotated[str | None, Query(alias="status")] = None,
        layer: str | None = None,
        limit: int = 100,
    ) -> list[EntryModel]:
        query = select(Entry).order_by(Entry.lemma, Entry.upos).limit(limit)
        if status_filter:
            query = query.where(Entry.status == status_filter)
        if layer:
            query = query.where(Entry.layer == layer)
        return [_entry(entry) for entry in session.scalars(query).unique()]

    @app.post("/v1/entries", response_model=EntryModel, status_code=201)
    def create_entry(body: NewEntry, session: Db) -> EntryModel:
        _check_layer(body.layer)
        if body.vzor:
            _check_vzor(body.vzor, body.flags)
        entry = store.create_entry(
            session,
            lemma=body.lemma,
            upos=body.upos,
            layer=body.layer,
            vzor=body.vzor,
            flags=body.flags,
            forms=[(f.form, f.feats) for f in body.forms],
            status_=st.PROPOSED,
            source="human",
            provenance=body.provenance,
            actor=body.actor,
        )
        return _entry(entry)

    @app.get("/v1/entries/{entry_id}", response_model=EntryModel)
    def get_entry(entry_id: int, session: Db) -> EntryModel:
        return _entry(_require(session, Entry, entry_id))

    @app.post("/v1/entries/{entry_id}/try-pattern", response_model=ParadigmResponse)
    def try_pattern(entry_id: int, body: TryPattern, session: Db) -> ParadigmModel:
        """Generate a table for correction — contracts §7's `try-pattern`."""
        entry = _require(session, Entry, entry_id)
        _check_vzor(body.vzor, body.flags)
        proposal = Proposal(
            lemma=entry.lemma,
            upos=entry.upos,
            vzor=body.vzor,
            flags=tuple(body.flags),
        )
        forms = paradigm(proposal)
        if not forms:
            raise HTTPException(
                400,
                f"{entry.lemma!r} cannot take vzor {body.vzor!r} — the pattern "
                "expects a different citation form. The engine says so; this is "
                "not a validation rule the studio invented",
            )
        observed = _observed(session, entry)
        if body.apply:
            entry.vzor = body.vzor
            entry.flags = list(body.flags)
            store.set_forms(session, entry)
            store.audit(
                session,
                "entry",
                entry.id,
                "try-pattern",
                actor="human",
                vzor=body.vzor,
                flags=list(body.flags),
            )
        return ParadigmModel(
            vzor=body.vzor,
            flags=list(body.flags),
            forms=[FormModel(form=f, feats=feats) for f, feats in forms],
            validates=bool(observed) and validates(proposal, observed),
        )

    @app.post("/v1/entries/{entry_id}/ask-llm", response_model=ParadigmResponse)
    def ask_llm(entry_id: int, session: Db) -> ParadigmModel:
        """The classifier's proposal for an entry a human is looking at."""
        entry = _require(session, Entry, entry_id)
        leg = app.state.llm
        if leg is None:
            raise HTTPException(
                503,
                "no LLM leg is configured (MORPH_LLM_URL) — this deployment "
                "runs guesser → human, which is a supported arrangement and not "
                "a fault",
            )
        try:
            proposal = leg.classify(_observed(session, entry) or entry.lemma)
        except LlmError as exc:
            raise HTTPException(502, str(exc)) from exc
        store.audit(
            session,
            "entry",
            entry.id,
            "ask-llm",
            actor="human",
            proposal=proposal.as_dict(),
        )
        return ParadigmModel(
            vzor=proposal.vzor,
            flags=list(proposal.flags),
            forms=[FormModel(form=f, feats=feats) for f, feats in paradigm(proposal)],
            validates=validates(proposal, _observed(session, entry) or entry.lemma),
        )

    @app.post("/v1/entries/{entry_id}/forms", response_model=EntryModel)
    def correct_forms(entry_id: int, body: CorrectForms, session: Db) -> EntryModel:
        entry = _require(session, Entry, entry_id)
        store.set_forms(
            session,
            entry,
            [(f.form, f.feats) for f in body.forms],
            corrected=True,
        )
        store.audit(
            session,
            "entry",
            entry.id,
            "forms-corrected",
            actor=body.actor,
            forms=len(body.forms),
        )
        return _entry(entry)

    @app.post("/v1/entries/{entry_id}/status", response_model=EntryModel)
    def set_entry_status(entry_id: int, body: SetStatus, session: Db) -> EntryModel:
        entry = _require(session, Entry, entry_id)
        with _machine():
            store.set_status(
                session, entry, body.status, actor=body.actor, reason=body.reason
            )
        return _entry(entry)

    # ── FI-7 surfaces 3+4: the queue ────────────────────────────────────────

    @app.post("/v1/ingest", response_model=IngestResponse)
    async def ingest(body: IngestRequest, session: Db) -> IngestResponse:
        """The front's spool, or a direct POST (contracts §6/§7)."""
        response = IngestResponse()
        touched = False
        for report in body.reports:
            if report.world != settings.world:
                # LM-5/S-4: queues never cross worlds, and this instance is one
                # world's. Rejected per row rather than for the batch, so a
                # misrouted line does not discard the ones that were right.
                response.rejected.append(
                    f"{report.token}: world {report.world!r} is not this "
                    f"instance's ({settings.world!r})"
                )
                continue
            item, created = store.ingest(
                session,
                world=report.world,
                token=report.token,
                verdict=report.verdict,
                count=report.count,
                context_span=report.context_span,
                first_seen=report.first_seen,
                last_seen=report.last_seen,
                llm=app.state.llm,
                vocabulary=settings.vocabulary,
            )
            response.accepted += 1
            response.created += int(created)
            response.updated += int(not created)
            touched = touched or (created and item.status == st.AUTO_VALIDATED)

        if touched:
            session.commit()
            emitted, detail = await _refresh_overlay(session)
            response.overlay_emitted = emitted
            response.reload = detail
        return response

    @app.get("/v1/queue", response_model=QueueResponse)
    def queue(
        session: Db,
        world: str | None = None,
        status_filter: Annotated[str | None, Query(alias="status")] = None,
        limit: int = 100,
    ) -> QueueResponse:
        if world and world != settings.world:
            raise HTTPException(
                400,
                f"this instance serves world {settings.world!r} (LM-5) — there "
                "is no cross-world queue to ask for",
            )
        query = select(QueueItem).where(QueueItem.world == settings.world)
        if status_filter:
            query = query.where(QueueItem.status == status_filter)
        total = session.scalar(
            select(func.count()).select_from(query.subquery())
        )
        items = session.scalars(
            query.order_by(QueueItem.count.desc(), QueueItem.token).limit(limit)
        ).unique()
        return QueueResponse(
            world=settings.world,
            items=[_item(item) for item in items],
            total=int(total or 0),
        )

    @app.post("/v1/queue/{item_id}/verdict", response_model=VerdictResponse)
    async def verdict(item_id: int, body: Verdict, session: Db) -> VerdictResponse:
        item = _require(session, QueueItem, item_id)
        if body.action not in ACTIONS:
            raise HTTPException(400, f"{body.action!r} is not one of {list(ACTIONS)}")

        entry = session.get(Entry, item.entry_id) if item.entry_id else None

        if body.action == ACTION_ROUTE:
            _check_layer(body.layer or "")
            store.reroute(session, item, body.layer or "", actor=body.actor)

        elif body.action == ACTION_VERIFY:
            entry = entry or _entry_for(session, item, body.actor)
            if entry is None:
                raise HTTPException(
                    400,
                    "there is nothing to verify: the cascade proposed nothing "
                    "for this token, so a person has to author the entry first "
                    "(POST /entries) and then verify it",
                )
            if not store.confirm(session, entry, item.token):
                raise HTTPException(
                    409,
                    f"the entry's paradigm does not contain {item.token!r} — "
                    "verifying it would publish a pattern that cannot make the "
                    "word this queue item is about (LM-14). Correct the pattern "
                    "or the forms first",
                )
            with _machine():
                store.set_status(
                    session, entry, st.VERIFIED, actor=body.actor, reason=body.reason
                )
                store.set_status_queue(
                    session, item, st.VERIFIED, actor=body.actor, reason=body.reason
                )

        else:  # reject
            with _machine():
                if entry is not None:
                    store.set_status(
                        session,
                        entry,
                        st.REJECTED,
                        actor=body.actor,
                        reason=body.reason,
                    )
                store.set_status_queue(
                    session, item, st.REJECTED, actor=body.actor, reason=body.reason
                )

        session.commit()
        retractions = (
            [f"{entry.lemma} ({entry.upos})"]
            if body.action == ACTION_REJECT and entry is not None
            else []
        )
        emitted, detail = await _refresh_overlay(session, retractions=retractions)
        return VerdictResponse(
            item=_item(item),
            entry=_entry(entry) if entry is not None else None,
            overlay_emitted=emitted,
            reload=detail,
        )

    # ── export ──────────────────────────────────────────────────────────────

    @app.post("/v1/export", response_model=ExportResponse)
    def export_(body: ExportRequest, session: Db) -> ExportResponse:
        result = export_module.export_layers(session, settings)
        written = (
            export_module.write(result, settings.export_dir) if body.write else []
        )
        return ExportResponse(
            files=result.files,
            written=written,
            exported=result.exported,
            withheld=result.withheld,
        )

    @app.post("/v1/export/proposals", response_model=ExportResponse)
    def export_proposals(body: ExportRequest, session: Db) -> ExportResponse:
        result = export_module.export_proposals(session, settings)
        written = (
            export_module.write(result, settings.export_dir) if body.write else []
        )
        return ExportResponse(
            files=result.files, written=written, exported=result.exported
        )

    # ── helpers that need the app's state ───────────────────────────────────

    async def _refresh_overlay(
        session: Session, *, retractions: list[str] | None = None
    ) -> tuple[bool, str]:
        """Re-emit the world overlay and ask the front to reload (Q-7)."""
        if not settings.overlay_dir:
            return False, ""
        result = provisional_module.emit_overlay(
            session, settings, retractions=retractions
        )
        if not result.written:
            return False, "; ".join(result.notes)
        ok, detail = await provisional_module.reload_front(settings)
        return True, detail if ok else "; ".join([*result.notes, detail])

    def _entry_for(session: Session, item: QueueItem, actor: str) -> Entry | None:
        """The entry a verdict acts on, created from the top proposal if needed.

        A reviewer can verify a `proposed` item straight from the queue without
        opening the editor — that is FI-7 surface 3's whole ergonomics — and
        that means promoting the proposal the cascade already showed them.
        """
        proposals = (item.proposal or {}).get("proposals") or []
        if not proposals:
            return None
        best = Proposal.from_dict(proposals[0])
        entry = store.entry_from_proposal(
            session,
            best,
            layer=item.layer,
            status_=st.PROPOSED,
            provisional=False,
            actor=actor,
        )
        item.entry_id = entry.id
        session.flush()
        return entry

    return app


# ── module-level helpers ─────────────────────────────────────────────────────


class _machine:
    """Translate the status machine's refusals into HTTP, in one place."""

    def __enter__(self) -> _machine:
        return self

    def __exit__(self, kind, value, tb) -> bool:
        if isinstance(value, st.IllegalTransition):
            raise HTTPException(409, str(value)) from value
        if isinstance(value, st.UnknownStatus):
            raise HTTPException(400, str(value)) from value
        return False


def _require(session: Session, model, row_id: int):
    row = session.get(model, row_id)
    if row is None:
        raise HTTPException(404, f"no {model.__tablename__} {row_id}")
    return row


def _check_layer(layer: str) -> None:
    if layer not in (LAYER_CORE, LAYER_WORLD):
        raise HTTPException(
            400, f"{layer!r} is not a layer — {LAYER_CORE!r} or {LAYER_WORLD!r} (LM-10)"
        )


def _check_vzor(vzor: str, flags: list[str]) -> None:
    tables = load_tables("cs")
    if vzor not in tables.vzory:
        raise HTTPException(
            400,
            f"{vzor!r} is not a pattern in the cs tables — the inventory is "
            "closed and served at GET /v1/vzory",
        )
    unknown = [flag for flag in flags if flag not in tables.flags]
    if unknown:
        raise HTTPException(400, f"unknown flags {unknown}")


def _observed(session: Session, entry: Entry) -> str:
    """The token that put this entry in the store, if a queue item did."""
    item = session.scalar(select(QueueItem).where(QueueItem.entry_id == entry.id))
    return item.token if item is not None else ""


def _counts(session: Session, column, key) -> dict[str, int]:
    rows = session.execute(select(column, func.count(key)).group_by(column)).all()
    return {str(value): int(count) for value, count in rows}


def _entry(entry: Entry) -> EntryModel:
    return EntryModel(
        id=entry.id,
        lemma=entry.lemma,
        upos=entry.upos,
        layer=entry.layer,
        vzor=entry.vzor,
        flags=list(entry.flags or ()),
        status=entry.status,
        provenance=entry.provenance,
        source=entry.source,
        confidence=entry.confidence,
        provisional=bool(entry.provisional),
        forms=[
            FormModel(form=row.form, feats=row.feats, corrected=bool(row.corrected))
            for row in sorted(entry.forms, key=lambda r: (r.form, r.feats))
        ],
    )


def _item(item: QueueItem) -> QueueItemModel:
    return QueueItemModel(
        id=item.id,
        world=item.world,
        token=item.token,
        verdict=item.verdict,
        count=item.count,
        context_span=item.context_span,
        status=item.status,
        layer=item.layer,
        routed_by=item.routed_by,
        entry_id=item.entry_id,
        cascade=CascadeModel(**(item.proposal or {})),
    )


__all__ = ["ACTIONS", "ACTION_REJECT", "ACTION_ROUTE", "ACTION_VERIFY", "create_app"]
