# SPDX-License-Identifier: Apache-2.0
"""`ReloadPacks` over both snapshots at once (NL-15, LM contracts §5).

The front owns two immutable snapshots now — the rule packs and the morph
artifact — and `ReloadPacks` re-reads both. That makes the atomic-swap promise
harder than it was with one, in a way worth having a module for:

    validate BOTH, then swap BOTH, or swap NEITHER.

Reloading them one after another would let the packs apply while the morph
snapshot refuses, leaving a front serving a *new* pack tree against an *old*
lexicon — a combination nobody authored, nobody tested, and nobody can name from
the response, since `applied=false` would be the only signal and it would be
attached to the half that worked. The failure would look like a rule that
stopped matching.

So each owner exposes `prepare()` (validate into a new snapshot, mutate nothing)
and `commit()` (one pointer assignment). This runs every `prepare` first, and
only reaches the commits if all of them succeeded.

A refusal is still not an error *state*: `applied=false` plus `NLS-PACK-010` and
the diagnostics is the whole outcome, and the service is exactly as healthy as it
was a moment before.
"""

from __future__ import annotations

import logging
from contextlib import AsyncExitStack

from ttrnlp.packs.diag import Diagnostic
from ttrnlp.packs.diag import error as diagnostic

from nlp_service.diagnostics import NLS_PACK_010, meaning
from nlp_service.morph_state import MorphSnapshot
from nlp_service.packs_state import PackState

logger = logging.getLogger(__name__)


async def reload_all(
    packs: PackState, morph: MorphSnapshot | None = None
) -> tuple[bool, str, list[Diagnostic]]:
    """Re-read every snapshot. Returns `(applied, state_id, diagnostics)`.

    `state_id` is the pack snapshot's, before or after — a caller comparing it
    across a refused reload must see it unchanged, or "the reload did nothing"
    is not something it can establish from the response. The morph artifact's own
    version is on `GetStatus.morph_state`, where the rest of it already is.
    """
    async with AsyncExitStack() as stack:
        await stack.enter_async_context(packs.lock)
        if morph is not None:
            await stack.enter_async_context(morph.lock)

        pack_state, pack_diagnostics = packs.prepare()
        morph_state, morph_diagnostics = (
            morph.prepare() if morph is not None else (None, [])
        )

        refused: list[Diagnostic] = []
        if pack_state is None:
            refused.extend(pack_diagnostics)
        if morph is not None and morph.configured and morph_state is None:
            refused.extend(morph_diagnostics)

        if refused:
            logger.error(
                "reload refused (%d diagnostic(s)) — snapshot %s still serving",
                len(refused),
                packs.state_id,
            )
            return (
                False,
                packs.state_id,
                [diagnostic(NLS_PACK_010, meaning(NLS_PACK_010)), *refused],
            )

        packs.commit(pack_state, pack_diagnostics)
        if morph is not None:
            morph.commit(morph_state, morph_diagnostics)
        logger.info(
            "reload applied: state_id=%s morph=%s",
            packs.state_id,
            morph.version if morph is not None else "-",
        )
        # `LM-MORPH-002` shadow notices survive a successful reload on purpose:
        # they are INFO, they describe what is now serving, and they are the
        # first thing to check when one deployment's acceptance cases fail.
        return True, packs.state_id, list(morph_diagnostics)


__all__ = ["reload_all"]
