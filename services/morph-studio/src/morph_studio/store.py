# SPDX-License-Identifier: Apache-2.0
"""Everything that reads or writes the store (NLS-P9.2 T3/T6).

The endpoints in `api.py` are thin on purpose: they parse, they call one of
these, they serialise. Every rule about *what the database may hold* lives here
— the status machine is consulted here, the paradigm is generated here, the
audit row is written here — so that a second caller (the bootstrap batch of
NLS-P9.3, a migration script, a test) cannot reach a state the API cannot.
"""

from __future__ import annotations

import logging
from collections.abc import Iterable, Sequence
from dataclasses import replace

from sqlalchemy import select
from sqlalchemy.orm import Session
from ttrmorph.enrich.cascade import (
    LAYER_CORE,
    LAYER_WORLD,
    STATUS_AUTO_VALIDATED,
    STATUS_PROPOSED,
    CascadeResult,
    run_cascade,
)
from ttrmorph.enrich.guesser import Proposal, paradigm, validates
from ttrmorph.enrich.llm import LlmLeg
from ttrnlp.morph import fold

from morph_studio import status as st
from morph_studio.models import Audit, Entry, EntryForm, QueueItem

logger = logging.getLogger(__name__)

#: `queue_item.routed_by` — whether LM-10's default or a person decided.
ROUTED_AUTO = "auto"
ROUTED_HUMAN = "human"

#: Editorial provenance for an entry the cascade produced without a human
#: (contracts §3's vocabulary). `manual` would be a lie and `llm` would be one
#: for a guesser-only answer, so the leg decides: the guesser is the suite's own
#: engine and its output is `manual` material in the licence sense — it derives
#: from the vzor tables, which are ours — while an LLM answer is `llm`.
PROVENANCE_BY_SOURCE = {"guesser": "manual", "inflector": "manual", "llm": "llm"}

#: The cascade's own ladder, low to high — the only statuses a *re*-proposal may
#: move an existing entry along (`_raise_standing`). Everything above these is a
#: human act or an export, and a second sighting of a word is neither: an ingest
#: that could push a lexeme to `verified` would be the export gate's one hole,
#: and one that could push it to `rejected` would let the enrichment loop
#: overrule the reviewer who rejected it.
_LADDER = (STATUS_PROPOSED, STATUS_AUTO_VALIDATED)


# ── audit ────────────────────────────────────────────────────────────────────


def audit(
    session: Session,
    subject: str,
    subject_id: int,
    action: str,
    *,
    actor: str = "system",
    **detail: object,
) -> Audit:
    row = Audit(
        subject=subject,
        subject_id=subject_id,
        action=action,
        actor=actor,
        detail=dict(detail),
    )
    session.add(row)
    return row


# ── entries ──────────────────────────────────────────────────────────────────


def find_entry(session: Session, lemma: str, upos: str, layer: str) -> Entry | None:
    return session.scalar(
        select(Entry).where(
            Entry.lemma == lemma, Entry.upos == upos, Entry.layer == layer
        )
    )


def create_entry(
    session: Session,
    *,
    lemma: str,
    upos: str,
    layer: str,
    vzor: str | None = None,
    flags: Sequence[str] = (),
    forms: Sequence[tuple[str, str]] = (),
    status_: str = st.PROPOSED,
    source: str = "human",
    confidence: float = 0.0,
    provisional: bool = False,
    provenance: str = "manual",
    actor: str = "system",
) -> Entry:
    """A new entry with its paradigm materialised, or the existing one, raised.

    Identity is `(lemma, upos, layer)` (contracts §3). A second proposal for a
    word already in the store does not create a twin — it is the same lexeme,
    and two rows for it would each be exportable and disagree.

    ⚑ But it does not return the existing row *untouched* either. A cascade that
    auto-validates the second token of a lexeme already sitting at `proposed`
    has raised that lexeme's standing, and Q-7 reads `entry.provisional` to
    decide what goes live: leave both fields at what the first token wrote and
    the entry the cascade just auto-validated never reaches the front, with
    nothing anywhere saying why. See `_raise_standing` for how far that goes,
    which is: forwards only, and never past a human.
    """
    existing = find_entry(session, lemma, upos, layer)
    if existing is not None:
        return _raise_standing(
            session,
            existing,
            status_=status_,
            provisional=provisional,
            actor=actor,
        )

    entry = Entry(
        lemma=lemma,
        upos=upos,
        layer=layer,
        vzor=vzor,
        flags=list(flags),
        status=status_,
        source=source,
        confidence=confidence,
        provisional=1 if provisional else 0,
        provenance=provenance,
    )
    session.add(entry)
    session.flush()
    set_forms(session, entry, forms)
    audit(
        session,
        "entry",
        entry.id,
        "created",
        actor=actor,
        status=status_,
        layer=layer,
        vzor=vzor,
        source=source,
    )
    return entry


def _raise_standing(
    session: Session,
    entry: Entry,
    *,
    status_: str,
    provisional: bool,
    actor: str,
) -> Entry:
    """Apply a re-proposal's standing to the lexeme already in the store.

    Forwards along `_LADDER` and no further. Two rules, both of them about not
    letting the loop overwrite a person:

    * the status moves only `proposed → auto-validated`. An entry a reviewer
      already took to `verified` is not pulled back to whatever a fresh cascade
      would say about it, and a `rejected` one is not reopened by the word being
      typed again — that is what makes `rejected` terminal worth anything;
    * `provisional` is set only while the entry is still below the export gate.
      A `verified` entry is permanent by definition (`set_status` clears the
      mark when it gets there), and re-marking it would put a row back into
      Q-7's unverified half after a human had signed it off.
    """
    if entry.status not in _LADDER or status_ not in _LADDER:
        return entry

    changes: dict[str, object] = {}
    if _LADDER.index(status_) > _LADDER.index(entry.status):
        was, entry.status = entry.status, status_
        changes |= {"was": was, "now": status_}
    if provisional and not entry.provisional and status_ not in st.EXPORTABLE:
        entry.provisional = 1
        changes["provisional"] = True

    if not changes:
        return entry
    session.flush()
    audit(session, "entry", entry.id, "re-proposed", actor=actor, **changes)
    return entry


def set_forms(
    session: Session,
    entry: Entry,
    forms: Sequence[tuple[str, str]] = (),
    *,
    corrected: bool = False,
) -> list[EntryForm]:
    """Replace the entry's forms: the given ones, or the pattern's own.

    Passing nothing regenerates from `(lemma, vzor, flags)`, which is what
    creating or re-patterning an entry does. Passing forms is a reviewer having
    corrected the table, and those are marked `corrected` so export knows to
    write a full-form entry rather than a compact one the compiler would
    regenerate differently (`LM-MORPH-005`).
    """
    entry.forms.clear()
    session.flush()

    generated = list(forms)
    if not generated and entry.vzor:
        generated = paradigm(
            Proposal(
                lemma=entry.lemma,
                upos=entry.upos,
                vzor=entry.vzor,
                flags=tuple(entry.flags or ()),
            )
        )

    rows = [
        EntryForm(
            form=form,
            folded=fold(form),
            feats=feats,
            corrected=1 if corrected else 0,
        )
        for form, feats in sorted(set(generated))
    ]
    # Through the relationship, not `add_all(entry_id=...)`: the in-session
    # entry keeps a loaded `forms` collection, and rows inserted behind its back
    # are invisible to the object the endpoint is about to serialise — and to
    # the delete cascade.
    entry.forms.extend(rows)
    session.flush()
    return rows


def set_status(
    session: Session,
    entry: Entry,
    wanted: str,
    *,
    actor: str = "system",
    reason: str = "",
) -> Entry:
    """Move an entry through the machine, or raise `IllegalTransition`.

    Asking for the status it already has is a no-op, not a refusal. The machine
    has no self-edges and should not grow any — `verified → verified` is not a
    claim about the lexicon and does not belong in a table whose whole value is
    that every edge in it is one. But it *is* something a reviewer does: verify
    a queue item whose lexeme a second, earlier item already took to `verified`,
    and the honest answer is that the entry is where they wanted it, not a 409
    they can do nothing about.
    """
    if entry.status == wanted:
        return entry
    st.check(entry.status, wanted)
    was = entry.status
    entry.status = wanted
    if wanted in (st.VERIFIED, st.PUBLISHED):
        # Q-7 ends where verification begins: a verified entry is permanent,
        # and the overlay it may already be living in is re-emitted without the
        # provisional mark.
        entry.provisional = 0
    session.flush()
    audit(
        session,
        "entry",
        entry.id,
        "status",
        actor=actor,
        was=was,
        now=wanted,
        reason=reason,
    )
    return entry


def lookup(session: Session, form: str, *, limit: int = 20) -> tuple[str, list[Entry]]:
    """`(matched_via, entries)` for a surface form — contracts §1's shape.

    Exact first, folded second, and the two are never merged: a reviewer
    searching *zena* needs to know the answer came through the fold, because
    that is exactly the case where a wrong entry looks right.
    """
    exact = session.scalars(
        select(Entry).join(EntryForm).where(EntryForm.form == form).limit(limit)
    ).unique()
    entries = list(exact)
    if entries:
        return "exact", entries

    folded = session.scalars(
        select(Entry).join(EntryForm).where(EntryForm.folded == fold(form)).limit(limit)
    ).unique()
    return "folded", list(folded)


def exportable_entries(session: Session, layer: str | None = None) -> list[Entry]:
    """THE GATE (contracts §7): `verified` and above, and nothing else."""
    query = select(Entry).where(Entry.status.in_(sorted(st.EXPORTABLE)))
    if layer is not None:
        query = query.where(Entry.layer == layer)
    return list(session.scalars(query.order_by(Entry.lemma, Entry.upos)).unique())


def provisional_entries(session: Session) -> list[Entry]:
    """Everything currently live in the world overlay unverified (Q-7)."""
    query = select(Entry).where(
        Entry.layer == LAYER_WORLD,
        Entry.provisional == 1,
        Entry.status.in_([st.PROPOSED, st.AUTO_VALIDATED]),
    )
    return list(session.scalars(query.order_by(Entry.lemma, Entry.upos)).unique())


# ── the queue ────────────────────────────────────────────────────────────────


def find_queue_item(session: Session, world: str, token: str) -> QueueItem | None:
    return session.scalar(
        select(QueueItem).where(QueueItem.world == world, QueueItem.token == token)
    )


def ingest(
    session: Session,
    *,
    world: str,
    token: str,
    verdict: str = "miss",
    count: int = 1,
    context_span: str = "",
    first_seen: str = "",
    last_seen: str = "",
    llm: LlmLeg | None = None,
    vocabulary: Iterable[str] = (),
    run: bool = True,
    cascade: CascadeResult | None = None,
) -> tuple[QueueItem, bool]:
    """One spool row into the queue. Returns `(item, is_new)`.

    Dedup is on `(world, token)` — the same key the front's sink dedups on, so
    a spool re-read is idempotent and a re-POST only moves the counter.

    **The cascade runs once, when the item is new.** A token seen a thousand
    times does not deserve a thousand guesses, and re-running it over a verdict
    a human already gave would be worse than wasteful: it would overwrite the
    proposal they were looking at.
    """
    item = find_queue_item(session, world, token)
    if item is not None:
        item.count += max(count, 0)
        item.last_seen = last_seen or item.last_seen
        if context_span and not item.context_span:
            item.context_span = context_span
        session.flush()
        return item, False

    item = QueueItem(
        world=world,
        token=token,
        verdict=verdict,
        count=max(count, 1),
        context_span=context_span,
        first_seen=first_seen,
        last_seen=last_seen,
        status=st.PROPOSED,
        proposal={},
        layer=LAYER_CORE,
    )
    session.add(item)
    session.flush()
    audit(session, "queue_item", item.id, "ingested", world=world, token=token)

    if run:
        apply_cascade(session, item, llm=llm, vocabulary=vocabulary, result=cascade)
    return item, True


def apply_cascade(
    session: Session,
    item: QueueItem,
    *,
    llm: LlmLeg | None = None,
    vocabulary: Iterable[str] = (),
    result: CascadeResult | None = None,
) -> CascadeResult:
    """Record what the cascade concluded, running it unless handed the answer.

    ⚑ `result` is how the NLS-P9.3 bootstrap batch lands. It ran this same
    cascade over a target list, a person read the report it produced, and the
    rows they approved arrive carrying their own conclusions. Re-deriving them
    here would mean the report somebody reviewed and the queue they are now
    looking at came from two different runs — and the LLM leg is not a pure
    function. It would also pay for every classification twice.

    Nothing about the *rules* changes: the status a precomputed result carries
    is one the cascade produces (`proposed` or `auto-validated`), and the
    entry it creates goes through `entry_from_proposal` exactly as a live one
    does. What is skipped is the computation, not the gate — and `_validated`
    is what makes that sentence true rather than merely intended.
    """
    if result is None:
        result = run_cascade(
            item.token,
            llm=llm,
            vocabulary=vocabulary,
            context=item.context_span,
        )
    else:
        result = _validated(result, item.token)
    item.proposal = {
        "proposals": [p.as_dict() for p in result.proposals],
        "tier": result.tier,
        "agreed": result.agreed,
        "notes": list(result.notes),
    }
    item.layer = result.layer
    item.routed_by = ROUTED_AUTO
    session.flush()

    if result.status == STATUS_AUTO_VALIDATED and result.best is not None:
        set_status_queue(session, item, st.AUTO_VALIDATED, actor=result.tier)
        entry = entry_from_proposal(
            session,
            result.best,
            layer=result.layer,
            status_=st.AUTO_VALIDATED,
            provisional=result.layer == LAYER_WORLD,
            actor=result.tier,
        )
        item.entry_id = entry.id
        session.flush()
    else:
        audit(
            session,
            "queue_item",
            item.id,
            "cascade",
            tier=result.tier,
            proposals=len(result.proposals),
            notes=list(result.notes),
        )
    return result


def _validated(result: CascadeResult, token: str) -> CascadeResult:
    """LM-14's rule, re-run over a cascade result somebody else computed.

    ⚑ This is the gate, and it is here rather than in `api._precomputed`
    because the API is not the only caller: the NLS-P9.3 bootstrap batch hands
    `apply_cascade` its own results directly, and a check that lived in an
    endpoint would be a check the batch does not get.

    `api._precomputed` already rejects a status no cascade reaches, so the
    remaining lie a body can tell is the one that matters most: *this token is
    in the paradigm this pattern generates*. Believed, it puts a paradigm no
    engine ever produced straight into the world's live overlay past
    `provisional=True` — which is exactly the claim the docstring above says is
    never taken on anybody's word, `validates()` being the only thing in this
    loop that can make it true.

    A result that fails is not refused. It is held at `proposed`, with the
    reason in the notes where a reviewer reads it: the token was really seen and
    the proposal may well be right, so the queue item belongs in front of a
    person, not in a 400 that loses it.
    """
    if result.status != STATUS_AUTO_VALIDATED:
        return result
    best = result.best
    if best is not None and validates(best, token):
        return result
    why = (
        "it carried no proposal"
        if best is None
        else (
            f"vzor {best.vzor!r} does not generate {token!r} from lemma "
            f"{best.lemma!r}"
        )
    )
    logger.warning(
        "precomputed cascade for %r claimed %s but %s — held at %s",
        token,
        STATUS_AUTO_VALIDATED,
        why,
        STATUS_PROPOSED,
    )
    return replace(
        result,
        status=STATUS_PROPOSED,
        notes=(
            *result.notes,
            f"auto-validation was NOT re-derivable here: {why}. Held at "
            f"{STATUS_PROPOSED!r} for a person (LM-14) — the engine check is "
            "re-run on every precomputed result, never trusted from the body.",
        ),
    )


def entry_from_proposal(
    session: Session,
    proposal: Proposal,
    *,
    layer: str,
    status_: str,
    provisional: bool = False,
    actor: str = "system",
) -> Entry:
    return create_entry(
        session,
        lemma=proposal.lemma,
        upos=proposal.upos,
        layer=layer,
        vzor=proposal.vzor,
        flags=proposal.flags,
        status_=status_,
        source=proposal.source,
        confidence=proposal.confidence,
        provisional=provisional,
        provenance=PROVENANCE_BY_SOURCE.get(proposal.source, "manual"),
        actor=actor,
    )


def set_status_queue(
    session: Session,
    item: QueueItem,
    wanted: str,
    *,
    actor: str = "system",
    reason: str = "",
) -> QueueItem:
    if item.status == wanted:  # idempotent, for `set_status`'s reason
        return item
    st.check(item.status, wanted)
    was = item.status
    item.status = wanted
    session.flush()
    audit(
        session,
        "queue_item",
        item.id,
        "status",
        actor=actor,
        was=was,
        now=wanted,
        reason=reason,
    )
    return item


def reroute(
    session: Session, item: QueueItem, layer: str, *, actor: str = "human"
) -> QueueItem:
    """LM-10's human override. Always audited — it overrules a rule."""
    if layer not in (LAYER_CORE, LAYER_WORLD):
        raise ValueError(
            f"{layer!r} is not a layer — {LAYER_CORE!r} or {LAYER_WORLD!r}"
        )
    was, item.layer, item.routed_by = item.layer, layer, ROUTED_HUMAN
    if item.entry_id is not None:
        entry = session.get(Entry, item.entry_id)
        if entry is not None:
            entry.layer = layer
            # A core entry can never be provisional (Q-7 is narrow, and the
            # compiler refuses it as `LM-MORPH-003` from the other side).
            if layer == LAYER_CORE:
                entry.provisional = 0
    session.flush()
    audit(session, "queue_item", item.id, "reroute", actor=actor, was=was, now=layer)
    return item


def confirm(session: Session, entry: Entry, token: str) -> bool:
    """LM-14's rule, re-run against the store rather than trusted.

    Called before a `verify` verdict lands: whatever produced the proposal — a
    guesser, a model, a person typing into the UI — the observed form has to be
    in the paradigm the entry actually generates.
    """
    corrected = any(row.corrected for row in entry.forms)
    if not entry.vzor or corrected:
        # A corrected table is the entry's truth — it is what export writes as a
        # full-form entry, and validating against the pattern the reviewer just
        # overruled would confirm forms nobody is going to ship.
        return any(row.form == token for row in entry.forms)
    return validates(
        Proposal(
            lemma=entry.lemma,
            upos=entry.upos,
            vzor=entry.vzor,
            flags=tuple(entry.flags or ()),
        ),
        token,
    )


__all__ = [
    "PROVENANCE_BY_SOURCE",
    "ROUTED_AUTO",
    "ROUTED_HUMAN",
    "apply_cascade",
    "audit",
    "confirm",
    "create_entry",
    "entry_from_proposal",
    "exportable_entries",
    "find_entry",
    "find_queue_item",
    "ingest",
    "lookup",
    "provisional_entries",
    "reroute",
    "set_forms",
    "set_status",
    "set_status_queue",
]
