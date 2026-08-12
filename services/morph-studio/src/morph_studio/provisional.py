# SPDX-License-Identifier: Apache-2.0
"""Q-7: the provisional world overlay, and the front reload (NLS-P9.2 T7).

The narrow ruling, in one paragraph (architecture §5, detailed-design §9): when
the cascade auto-validates a **proper noun for a world's entity layer**, the
generated forms may go live *immediately* in that world's overlay, flagged
`provisional`, so the next query that mentions *Kauflandu* resolves it instead
of missing it again. A reviewer's `verify` makes those rows permanent; a
`reject` retracts them. Nothing about this touches the core snapshot — that is
the whole of what makes the ruling narrow, and it is asserted rather than
intended (`_world_entries` filters on the layer, and the compiler refuses a
provisional row outside an overlay from the other side as `LM-MORPH-003`).

**Why the file and not an API.** The front loads its morphology from files
(NL-15, fail-all); there is no ingest endpoint and there should not be one — a
runtime that accepted lexicon rows over the wire would be a runtime whose
lexicon nobody can reproduce. So the studio writes the world's overlay into a
directory the front also mounts, and then asks it to reload. Two processes, one
file, one direction.

**The reload is a request, not a guarantee.** `MORPH_STUDIO_FRONT_TARGET` may be
empty — plenty of deployments reload on their own schedule, and every test does
— and an unreachable front is reported, never raised: the overlay on disk is the
durable half of this, and a failed reload costs latency in the enrichment loop
and nothing else.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session
from ttrmorph.compile.layers import WORLD_LICENSE_PREFIX
from ttrmorph.compile.snapshot import compile_layers
from ttrmorph.enrich.cascade import LAYER_WORLD
from ttrmorph.importers.emit import LayerHeader, render_layer, vzor_entry

from morph_studio import status as st
from morph_studio.config import Settings
from morph_studio.models import Entry

logger = logging.getLogger(__name__)

#: The overlay's compiled name. Matches what the front's fixtures and
#: `services/nlp/tests/conftest.py` already use, because the front's loader
#: reads a compiled overlay and not the layer source.
def overlay_name(world: str) -> str:
    return f"{world}.morph.overlay"


def source_name(world: str) -> str:
    return f"{world}-overlay.morph.yaml"


@dataclass
class OverlayResult:
    """What an emission did, and what it could not do."""

    #: Filename -> text, written to `overlay_dir` (empty when disabled).
    files: dict[str, str] = field(default_factory=dict)
    permanent: int = 0
    provisional: int = 0
    retracted: list[str] = field(default_factory=list)
    written: list[str] = field(default_factory=list)
    reloaded: bool = False
    #: Compiler diagnostics and the reload's failure, if any. Never raised.
    notes: list[str] = field(default_factory=list)
    enabled: bool = True


def emit_overlay(
    session: Session, settings: Settings, *, retractions: list[str] | None = None
) -> OverlayResult:
    """Re-emit the world's overlay from the database, and compile it.

    The whole overlay every time, not a delta: it is the file the front loads
    fail-all, so a partial one is not a smaller overlay but a broken world. It
    is also how a retraction happens — the rejected entry is simply not in the
    file that replaces the old one.
    """
    result = OverlayResult(retracted=list(retractions or []))
    if not settings.overlay_dir:
        result.enabled = False
        result.notes.append(
            "no MORPH_STUDIO_OVERLAY_DIR — the world overlay is not emitted from "
            "this instance (Q-7 off, or the deployment publishes its overlay "
            "through the layer-file lane instead)"
        )
        return result

    entries = _world_entries(session, provisional=settings.provisional)
    documents = []
    for entry in entries:
        document = vzor_entry(
            entry.lemma,
            entry.upos,
            entry.vzor or "",
            tuple(entry.flags or ()),
            entry.provenance,
        )
        if entry.provisional:
            document["provisional"] = True
            result.provisional += 1
        else:
            result.permanent += 1
        documents.append(document)

    source = render_layer(
        LayerHeader(
            layer=f"{settings.world}-overlay",
            language="cs",
            license=WORLD_LICENSE_PREFIX + settings.world,
            note=_note(settings, result),
        ),
        documents,
    )
    result.files[source_name(settings.world)] = source

    directory = Path(settings.overlay_dir)
    directory.mkdir(parents=True, exist_ok=True)
    layer_path = directory / source_name(settings.world)
    layer_path.write_text(source, encoding="utf-8")
    result.written.append(str(layer_path))

    compiled = compile_layers(
        [str(layer_path)],
        snapshot_version="0.0.0-provisional",
        output=overlay_name(settings.world),
        world=settings.world,
    )
    result.notes.extend(
        f"{d.code}: {d.message}" for d in compiled.diagnostics if d.severity != "INFO"
    )
    if not compiled.ok:
        # The overlay on disk is the front's live lexicon for this world. A
        # compile that failed must not overwrite the last good one with a file
        # assembled from whatever was usable.
        result.notes.append(
            "the overlay did NOT compile — the previously served file is left in "
            "place, which is the only safe thing to do with a world's live lexicon"
        )
        return result

    for name, text in compiled.outputs.items():
        path = directory / name
        path.write_text(text, encoding="utf-8")
        result.written.append(str(path))
        result.files[name] = text
    return result


async def reload_front(settings: Settings) -> tuple[bool, str]:
    """Ask the front to reload. `(ok, detail)` — never raises."""
    if not settings.front_target:
        return False, "no MORPH_STUDIO_FRONT_TARGET — nothing was asked to reload"
    try:
        from ttrnlp.client.grpc import NlpClient

        async with NlpClient(settings.front_target) as client:
            reloaded = await client.reload_packs()
    except Exception as exc:  # noqa: BLE001 — grpc's tree, plus DNS
        logger.warning("front reload failed: %s", exc)
        return False, f"front reload failed: {exc}"
    if not reloaded.applied:
        return False, f"front refused the reload: {reloaded.state_id}"
    return True, f"front reloaded ({reloaded.state_id})"


def _world_entries(session: Session, *, provisional: bool) -> list[Entry]:
    """What the overlay carries: the world's, verified — plus Q-7's if allowed.

    ⚑ `Entry.layer == LAYER_WORLD` is the assertion that Q-7 never reaches a
    core snapshot. It is a filter in a query rather than a comment because the
    consequence of getting it wrong — an unverified generated paradigm inside
    the published artifact — is the one thing the narrow ruling was narrowed
    to prevent.
    """
    allowed = set(st.EXPORTABLE)
    if provisional:
        allowed |= {st.PROPOSED, st.AUTO_VALIDATED}
    query = (
        select(Entry)
        .where(Entry.layer == LAYER_WORLD, Entry.status.in_(sorted(allowed)))
        .order_by(Entry.lemma, Entry.upos)
    )
    entries = list(session.scalars(query).unique())
    if not provisional:
        return entries
    # A `proposed`/`auto-validated` entry only belongs here BECAUSE it is
    # provisional; one that is not has no business being live.
    return [
        entry
        for entry in entries
        if entry.status in st.EXPORTABLE or entry.provisional
    ]


def _note(settings: Settings, result: OverlayResult) -> str:
    lines = [
        f"GENERATED by morph-studio — the {settings.world} world's overlay.",
        "",
        "Re-emitted whole on every change; do not hand-edit. Rows marked "
        "`provisional: true` are Q-7: engine-generated paradigms that "
        "auto-validated and are serving BEFORE a human confirmed them. Their "
        "provenance travels into Lookup, so a consumer can always tell.",
        "",
        "Verifying an entry makes its rows permanent. Rejecting one retracts "
        "them — it leaves this file at the next emission.",
    ]
    if result.retracted:
        lines += ["", "RETRACTED in this emission:"]
        lines += [f"  - {name}" for name in sorted(result.retracted)]
    return "\n".join(lines)


__all__ = [
    "OverlayResult",
    "emit_overlay",
    "overlay_name",
    "reload_front",
    "source_name",
]
