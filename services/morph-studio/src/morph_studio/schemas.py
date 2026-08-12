# SPDX-License-Identifier: Apache-2.0
"""Request and response models (contracts §7; NLS-P9.2 T6).

These are the OpenAPI document, and the OpenAPI document is the frontend's
codegen input at NLS-P9.3 — so every field here becomes a TypeScript property
somebody writes a component against. Two consequences worth stating: the names
match the database and the contracts rather than being prettified for the UI,
and nothing is `dict[str, Any]` where a shape is known, because a generated
client renders that as `any` and the type-checking stops there.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class Strict(BaseModel):
    #: ⚑ `json_schema_serialization_defaults_required` is what makes the
    #: generated client usable. A field with a default is *optional in a
    #: request* and **always present in a response** — this service never
    #: serialises with `exclude_unset` — but pydantic describes both with one
    #: schema, so without this every defaulted field generates as `T |
    #: undefined` and the frontend writes `?? []` a dozen times. Each of those
    #: is a place where a genuinely missing field is silently absorbed, which
    #: is the opposite of what generated types are for. FastAPI emits separate
    #: `-Input`/`-Output` schemas where a model is used both ways, so request
    #: bodies keep their optional fields.
    model_config = ConfigDict(
        extra="forbid", json_schema_serialization_defaults_required=True
    )


# ── proposals ────────────────────────────────────────────────────────────────


class ProposalModel(Strict):
    """Contracts §7's `proposal` jsonb, one element."""

    lemma: str
    upos: str
    vzor: str = ""
    flags: list[str] = Field(default_factory=list)
    confidence: float = 0.0
    source: str = "guesser"


class CascadeModel(Strict):
    """What the cascade concluded about a queue item."""

    proposals: list[ProposalModel] = Field(default_factory=list)
    tier: str = "human"
    agreed: bool = False
    notes: list[str] = Field(default_factory=list)


# ── entries ──────────────────────────────────────────────────────────────────


class FormModel(Strict):
    form: str
    feats: str = ""
    corrected: bool = False


class EntryModel(Strict):
    id: int
    lemma: str
    upos: str
    layer: str
    vzor: str | None = None
    flags: list[str] = Field(default_factory=list)
    status: str
    provenance: str
    source: str
    confidence: float
    provisional: bool
    forms: list[FormModel] = Field(default_factory=list)


class NewEntry(Strict):
    """`POST /entries` — the "new word" affordance of FI-7 surface 2.

    A `vzor` OR explicit `forms`, exactly as a layer file entry (contracts §3).
    Neither is required at creation: an analyst who has typed the word and its
    part of speech and wants to *try a pattern* next has entered something
    worth keeping.
    """

    lemma: str
    upos: str
    layer: str = "core"
    vzor: str | None = None
    flags: list[str] = Field(default_factory=list)
    forms: list[FormModel] = Field(default_factory=list)
    provenance: str = "manual"
    actor: str = "human"


class TryPattern(Strict):
    """`POST /entries/{id}/try-pattern` — generate, and optionally keep."""

    vzor: str
    flags: list[str] = Field(default_factory=list)
    #: Write the pattern onto the entry, not just preview its table. The UI's
    #: "try" button previews; its "use this" button saves.
    apply: bool = False


class CorrectForms(Strict):
    """`POST /entries/{id}/forms` — the edited table (FI-7 surface 2).

    Sending a corrected table converts the entry to a full-form one at export:
    the pattern no longer describes it, and pretending otherwise is
    `LM-MORPH-005`.
    """

    forms: list[FormModel]
    actor: str = "human"


class SetStatus(Strict):
    status: str
    reason: str = ""
    actor: str = "human"


class ParadigmModel(Strict):
    """A generated table, for review."""

    vzor: str
    flags: list[str] = Field(default_factory=list)
    forms: list[FormModel] = Field(default_factory=list)
    #: LM-14's rule, evaluated against the entry's own observed token when there
    #: is one: would this pattern auto-validate?
    validates: bool = False


class LookupResponse(Strict):
    """Contracts §1's `LookupResult`, over HTTP."""

    form: str
    matched_via: str
    entries: list[EntryModel] = Field(default_factory=list)


# ── the queue ────────────────────────────────────────────────────────────────


class PrecomputedCascade(CascadeModel):
    """A cascade that was already run — the NLS-P9.3 bootstrap batch's output.

    `CascadeModel` plus the two fields the batch decided and the service would
    otherwise re-derive. They are separate here because on a *queue item* the
    status and the layer live on the item's own columns; this model is a
    request, and a request has to carry them.
    """

    #: `proposed` or `auto-validated` — the only two a cascade reaches.
    status: str = "proposed"
    #: LM-10's routing, as the batch computed it.
    layer: str = "core"


class Report(Strict):
    """One row of the front's spool (contracts §6), as `POST /ingest` takes it."""

    world: str
    token: str
    verdict: str = "miss"
    count: int = 1
    context_span: str = ""
    first_seen: str = ""
    last_seen: str = ""
    #: ⚑ Present only for the bootstrap lane. The front never sends this: it
    #: reports a token and the studio works it out. A batch that already ran the
    #: same cascade sends its conclusions, so the report a person reviewed and
    #: the queue they then act on are the same run.
    cascade: PrecomputedCascade | None = None


class IngestRequest(Strict):
    reports: list[Report]


class IngestResponse(Strict):
    accepted: int = 0
    created: int = 0
    updated: int = 0
    rejected: list[str] = Field(default_factory=list)
    #: Q-7 fired: the overlay was re-emitted and the front asked to reload.
    overlay_emitted: bool = False
    reload: str = ""


class QueueItemModel(Strict):
    id: int
    world: str
    token: str
    verdict: str
    count: int
    context_span: str
    status: str
    layer: str
    routed_by: str
    entry_id: int | None = None
    cascade: CascadeModel = Field(default_factory=CascadeModel)


class QueueResponse(Strict):
    world: str
    items: list[QueueItemModel] = Field(default_factory=list)
    total: int = 0


class Verdict(Strict):
    """`POST /queue/{id}/verdict` — FI-7 surface 3's three buttons.

    `verify` promotes the entry (and makes any provisional overlay rows
    permanent); `reject` retracts; `route` is LM-10's human override and moves
    the entry between the core queue and the world's layer without judging it.
    """

    action: str  # verify | reject | route
    layer: str | None = None
    reason: str = ""
    actor: str = "human"


class VerdictResponse(Strict):
    item: QueueItemModel
    entry: EntryModel | None = None
    overlay_emitted: bool = False
    reload: str = ""


# ── export ───────────────────────────────────────────────────────────────────


class ExportRequest(Strict):
    #: Write to `MORPH_STUDIO_EXPORT_DIR`. False returns the files in the body
    #: and touches no disk — which is what a reviewer previewing a diff wants,
    #: and what every test wants.
    write: bool = False


class ExportResponse(Strict):
    files: dict[str, str] = Field(default_factory=dict)
    written: list[str] = Field(default_factory=list)
    exported: int = 0
    #: Status -> how many entries the gate held back.
    withheld: dict[str, int] = Field(default_factory=dict)


class StatusResponse(Strict):
    world: str
    mode: str
    ready: bool
    entries: dict[str, int] = Field(default_factory=dict)
    queue: dict[str, int] = Field(default_factory=dict)
    llm: bool = False
    provisional: bool = False


class VzorModel(Strict):
    """One pattern of the closed inventory (LM-2, B-O5)."""

    name: str
    upos: str
    parent: str | None = None
    implied_flags: list[str] = Field(default_factory=list)
    #: The B-O5 guesser prior — `capitalized` (bool), `lemma_pattern` (regex).
    #: Shown in the picker because it is what tells an analyst which sub-vzor
    #: they want. The keys stay open and the *values* are typed: a hint added to
    #: `vzory.yaml` must reach the picker (that is why the inventory is data),
    #: and `dict[str, str]` would coerce `capitalized: true` to the string
    #: `"True"` — which is truthy in TypeScript, so the picker would show every
    #: pattern as capitalized and no test outside this one would notice.
    hints: dict[str, str | bool] = Field(default_factory=dict)


class VzoryResponse(Strict):
    """`GET /vzory` — the pattern picker's whole world.

    Typed rather than returned as a bare object: the picker is *constrained to
    the engine inventory*, and a response that generates as an index signature
    makes that a runtime hope instead of something the compiler checks.
    """

    language: str
    flags: list[str] = Field(default_factory=list)
    vzory: list[VzorModel] = Field(default_factory=list)


class MachineResponse(Strict):
    """The LM-14 status machine, as data (contracts §8).

    Served for the same reason the pattern inventory is (`GET /v1/vzory`): a
    frontend that hard-codes the states draws chips for states that do not
    exist and offers buttons for edges the backend refuses, and the p9-3 task
    list asks for chips that "match the §8 machine exactly (no invented
    states)". A copy cannot be exact — only the thing itself can. So the UI
    renders what this returns, and a state added to `status.py` reaches the
    chips and the verdict buttons without a frontend release.
    """

    statuses: list[str] = Field(default_factory=list)
    #: from -> the statuses it may become. The *absent* edges are the point:
    #: `proposed` has no `published`, and that is the export gate.
    transitions: dict[str, list[str]] = Field(default_factory=dict)
    #: The statuses `POST /export` emits — everything below is working memory.
    exportable: list[str] = Field(default_factory=list)
    #: The statuses with no way out. `rejected` is one, deliberately.
    terminal: list[str] = Field(default_factory=list)
    #: The three verdict actions of FI-7 surface 3.
    actions: list[str] = Field(default_factory=list)
    #: The layers LM-10 routes between.
    layers: list[str] = Field(default_factory=list)


__all__ = [
    "CascadeModel",
    "CorrectForms",
    "EntryModel",
    "ExportRequest",
    "ExportResponse",
    "FormModel",
    "IngestRequest",
    "IngestResponse",
    "LookupResponse",
    "MachineResponse",
    "NewEntry",
    "ParadigmModel",
    "PrecomputedCascade",
    "ProposalModel",
    "QueueItemModel",
    "QueueResponse",
    "Report",
    "SetStatus",
    "StatusResponse",
    "TryPattern",
    "Verdict",
    "VerdictResponse",
    "VzorModel",
    "VzoryResponse",
]
