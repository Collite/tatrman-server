# SPDX-License-Identifier: Apache-2.0
"""The PG working store, in the B-F5 shape (contracts §7; NLS-P9.2 T3).

*"DB works, files publish"* (detailed-design §10). Nothing here is the truth
about the lexicon — the truth is a layer file in a repository, reviewable and
diffable, and this database is the working memory an editorial loop needs
between a miss arriving and an entry being fit to publish. That is why `export`
has a gate and why nothing reads this store at query time.

**Four tables, and each exists for a reason the others cannot serve.**

`entry` is the lexeme, in exactly the shape a layer file's entry has: identity
is `(lemma, upos)` per contracts §3, plus `layer` — the same lexeme can be
proposed for the core AND held in a world's own layer, and those are two
entries with two statuses, not one row with a flag.

`entry_form` is the generated paradigm, materialised. It could be recomputed on
every lookup from `(lemma, vzor, flags)` in microseconds, and it is stored
anyway for two reasons: `GET /lookup/{form}` is a search *by form*, which
without this table is a scan over every entry in the database; and a form a
reviewer *corrected* by hand has no pattern that produces it, so there has to be
somewhere for it to live (contracts §3's full-form entry).

`queue_item` is one observed miss per `(world, token)` — the front's spool row,
plus what the cascade concluded about it. It is deliberately not merged into
`entry`: most queue items never become entries, and the ones that do keep their
own history of having been asked about.

`audit` is who did what. An editorial loop whose verdicts cannot be attributed
is one nobody will trust a bootstrap batch to.
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import (
    JSON,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

#: jsonb on Postgres, plain JSON elsewhere. The variant is what lets the unit
#: suite run the whole service on SQLite while the deployment gets the indexable
#: column contracts §7 asks for — one model, two dialects, no branch in the code.
JsonB = JSON().with_variant(JSONB, "postgresql")


def now() -> datetime:
    """Timezone-aware UTC. Naive timestamps in an audit trail are a trap."""
    return datetime.now(UTC)


class Base(DeclarativeBase):
    pass


class Entry(Base):
    """One lexeme, in a layer, at a status."""

    __tablename__ = "entry"
    __table_args__ = (
        # contracts §3: entry identity is (lemma, upos) — per layer, because the
        # core and a world may each hold their own view of one word, and LM-10
        # routing is precisely the decision of which.
        UniqueConstraint("lemma", "upos", "layer", name="uq_entry_identity"),
        Index("ix_entry_status", "status"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    lemma: Mapped[str] = mapped_column(String(128), nullable=False)
    upos: Mapped[str] = mapped_column(String(16), nullable=False)
    #: `core` or `world` (LM-10). Not the layer *file* id — that is decided at
    #: export time, and an entry that carried a filename would have to be
    #: rewritten every time a world renamed one.
    layer: Mapped[str] = mapped_column(String(16), nullable=False)

    #: The compact form (contracts §3). Null for a full-form entry.
    vzor: Mapped[str | None] = mapped_column(String(64))
    flags: Mapped[list] = mapped_column(JsonB, default=list, nullable=False)

    status: Mapped[str] = mapped_column(String(32), nullable=False)
    #: Editorial provenance, per contracts §3: wiktionary | cac | manual | llm.
    provenance: Mapped[str] = mapped_column(
        String(16), nullable=False, default="manual"
    )
    #: Which leg reached it — guesser | inflector | llm | human. Distinct from
    #: `provenance`, which is about where the *material* came from and is what
    #: the licence boundary is drawn on.
    source: Mapped[str] = mapped_column(String(16), nullable=False, default="human")
    confidence: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)

    #: Q-7: this entry is live in the world overlay without being verified. Only
    #: ever true for `layer == world`; the compiler enforces the same rule from
    #: the other side (`LM-MORPH-003`).
    provisional: Mapped[bool] = mapped_column(Integer, default=0, nullable=False)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=now, onupdate=now
    )

    forms: Mapped[list[EntryForm]] = relationship(
        back_populates="entry", cascade="all, delete-orphan", lazy="selectin"
    )

    @property
    def identity(self) -> tuple[str, str, str]:
        return (self.lemma, self.upos, self.layer)


class EntryForm(Base):
    """One generated — or hand-corrected — surface form of an entry."""

    __tablename__ = "entry_form"
    __table_args__ = (
        UniqueConstraint("entry_id", "form", "feats", name="uq_entry_form"),
        Index("ix_entry_form_form", "form"),
        # The folded form, so `GET /lookup` answers a diacritics-less search the
        # way the runtime does. One fold definition, imported from the wheel.
        Index("ix_entry_form_folded", "folded"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    entry_id: Mapped[int] = mapped_column(
        ForeignKey("entry.id", ondelete="CASCADE"), nullable=False
    )
    form: Mapped[str] = mapped_column(String(128), nullable=False)
    folded: Mapped[str] = mapped_column(String(128), nullable=False)
    feats: Mapped[str] = mapped_column(String(256), nullable=False, default="")
    #: A form the pattern does NOT generate, typed by a reviewer. On export the
    #: entry becomes a full-form entry rather than a `vzor:` one, because a
    #: compact entry that regenerates differently is `LM-MORPH-005`.
    corrected: Mapped[bool] = mapped_column(Integer, default=0, nullable=False)

    entry: Mapped[Entry] = relationship(back_populates="forms")


class QueueItem(Base):
    """One observed miss, per (world, token) — the front's row plus our verdict."""

    __tablename__ = "queue_item"
    __table_args__ = (
        # LM-5/S-4: queues never cross worlds, and the same token in two worlds
        # is two items with two histories.
        UniqueConstraint("world", "token", name="uq_queue_item"),
        Index("ix_queue_item_status", "world", "status"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    world: Mapped[str] = mapped_column(String(64), nullable=False)
    token: Mapped[str] = mapped_column(String(128), nullable=False)
    #: `miss` | `resolved_wrong` (contracts §6).
    verdict: Mapped[str] = mapped_column(String(32), nullable=False, default="miss")
    #: How often the front saw it. The queue's only priority signal.
    count: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    #: Opt-in per world (contracts §6). Usually empty.
    context_span: Mapped[str] = mapped_column(Text, nullable=False, default="")
    first_seen: Mapped[str] = mapped_column(String(32), nullable=False, default="")
    last_seen: Mapped[str] = mapped_column(String(32), nullable=False, default="")

    status: Mapped[str] = mapped_column(String(32), nullable=False)
    #: Contracts §7's `proposal` jsonb — the cascade's ranked output:
    #: `{proposals: [...], tier, agreed, notes}`. Stored whole rather than
    #: normalised into columns because it is a *record of what was concluded*,
    #: read by a reviewer and by nothing that joins.
    proposal: Mapped[dict] = mapped_column(JsonB, default=dict, nullable=False)
    #: LM-10's precomputed routing, and the human override that beats it.
    layer: Mapped[str] = mapped_column(String(16), nullable=False, default="core")
    routed_by: Mapped[str] = mapped_column(String(16), nullable=False, default="auto")

    #: Set when a verdict turned this item into an entry.
    entry_id: Mapped[int | None] = mapped_column(
        ForeignKey("entry.id", ondelete="SET NULL")
    )

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=now, onupdate=now
    )


class Audit(Base):
    """Who did what to which row, and what it was before."""

    __tablename__ = "audit"
    __table_args__ = (Index("ix_audit_subject", "subject", "subject_id"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    #: `entry` | `queue_item`.
    subject: Mapped[str] = mapped_column(String(32), nullable=False)
    subject_id: Mapped[int] = mapped_column(Integer, nullable=False)
    action: Mapped[str] = mapped_column(String(64), nullable=False)
    #: Free-form, and it is where the *reason* goes: a status change with no
    #: record of what it replaced is not an audit trail, it is a log line.
    detail: Mapped[dict] = mapped_column(JsonB, default=dict, nullable=False)
    actor: Mapped[str] = mapped_column(String(64), nullable=False, default="system")


__all__ = ["Audit", "Base", "Entry", "EntryForm", "JsonB", "QueueItem", "now"]
