# SPDX-License-Identifier: Apache-2.0
"""The loaded morph snapshot, and how it is replaced (LM contracts §5, NL-15).

The sibling of `packs_state.PackState`, deliberately down to the method names.
Two immutable snapshots, two owners, one reload — and a shape a reader who has
already understood the pack half does not have to learn twice.

**A failed morph load does not stop the service, and it does not stop the pack
half either.** `Analyze` and `BatchLemmatize` never touch morphology;
`RunPipeline` on a pipeline that is *not* `morph: true` never touches it either.
So a snapshot volume that failed to mount costs exactly the Czech morph
pipelines, reports `LM-MORPH-001` on `GetStatus.morph_state`, and leaves
everything else serving. That distinction is why the boot error in
`config.validate_morph` is narrow: a *typo* (a `morph: true` pipeline with no
sources declared anywhere) can never come good and refuses to boot, while a
*mount* comes good the moment the volume appears and a `ReloadPacks` is sent.

**`ready` means "no configured source failed".** A deployment with no morph
sources at all is ready — nothing asked for morphology, and the pipelines that
would have needed it do not exist either (`validate_morph` guarantees the pair).
Only a load that was attempted and failed makes this false, which is the same
rule `PackState.ready` follows.

**The diagnostics are the wheel's own.** `ttrnlp.morph.diag` extends the
`NLS-PACK-*` family in the same five-field `Diagnostic` shape rather than
starting a second one, so an `LM-MORPH-001` reaches `GetStatus` through the
existing `PackDiagnostic` conversion with nothing translated at the boundary.
"""

from __future__ import annotations

import asyncio
import logging

from ttrnlp.morph import LoadError, MorphState, load_morph
from ttrnlp.morph.diag import LM_MORPH_001, Diagnostic
from ttrnlp.morph.diag import error as diagnostic

from nlp_service.config import AppConfig, validate_morph

logger = logging.getLogger(__name__)

#: The engine name a morph-served capability row and `used[]` entry carry.
#:
#: Not `"morph"`: the row answers "what kind of thing produced this lemma", and
#: the contract's own wording for the answer is the tier — `lexicon` today,
#: `lexicon+statistical` once Wave C routes a CAC-trained backend into the seam
#: (LM contracts §5, architecture §2). Naming the tier here means the day that
#: happens there is one constant to change and one test to read.
MORPH_ENGINE = "lexicon"
MORPH_ENGINE_WITH_STATISTICAL = "lexicon+statistical"


class MorphSnapshot:
    """Owns the current `MorphState`. Built at boot, replaced by `reload()`."""

    def __init__(self, config: AppConfig):
        # Here rather than in `load_config` for the reason the registry
        # validates routing in its own constructor: this is the first moment
        # both halves exist — the morph block AND the pipeline table it has to
        # agree with — and every construction path goes through it, including
        # the ones tests build by hand.
        validate_morph(config)
        self._config = config
        self._lock = asyncio.Lock()
        self._state: MorphState | None = None
        self._diagnostics: list[Diagnostic] = []
        self.load()

    # ---- reading ----------------------------------------------------------

    @property
    def state(self) -> MorphState | None:
        """The serving snapshot, or `None` if nothing is loaded."""
        return self._state

    @property
    def configured(self) -> bool:
        return bool(self._config.morph.sources)

    @property
    def diagnostics(self) -> list[Diagnostic]:
        """Why the load failed, plus any `LM-MORPH-002` shadow notices.

        Shadow notices ride along on a *successful* load: a world quietly
        overriding curated core vocabulary is the first thing to check when the
        core's own acceptance cases start failing in one deployment only, and
        `GetStatus` is where an operator looks for it.
        """
        return list(self._diagnostics)

    @property
    def ready(self) -> bool:
        """False only when a configured source failed to load."""
        return self._state is not None or not self.configured

    @property
    def language(self) -> str:
        return self._state.manifest.language if self._state else ""

    @property
    def world(self) -> str:
        """The world this deployment's pipeline misses belong to."""
        return self._config.morph.world

    def sources(self) -> list[str]:
        return list(self._config.morph.sources)

    def capabilities(self) -> list[dict]:
        """The `GetStatus` capability rows morphology serves (contracts §2.4).

        One row: `LEMMATIZE.<snapshot language>`, served by `lexicon`, pinned by
        the artifact's own version. It is **added beside** the engine's row for
        the same (language, op) rather than replacing it, and both are true at
        once: `Analyze` still routes `LEMMATIZE.cs` to whatever the lane says,
        and only a `morph: true` pipeline reads the snapshot. A consumer that
        needs to tell them apart reads `engine`, which is the field that exists
        for exactly that.

        `SELF_HOSTED_PINNED` is not a courtesy — the artifact carries a content
        hash the loader verifies, which makes it the most pinned model identity
        in the estate (S-1).
        """
        if self._state is None:
            return []
        stats = self._state.stats()
        return [
            {
                "language": stats.language,
                "op": _lemmatize_op(),
                "engine": MORPH_ENGINE,
                "model_version": stats.version,
                "tier": "SELF_HOSTED_PINNED",
                "is_floor": False,
                "info": (),
            }
        ]

    # ---- loading ----------------------------------------------------------

    def prepare(self) -> tuple[MorphState | None, list[Diagnostic]]:
        """Try to build a snapshot. Never mutates anything.

        Returns `(None, [])` when nothing is configured — "no snapshot" and
        "the snapshot failed" are different outcomes and the caller distinguishes
        them by the diagnostics, not by the state being `None`.
        """
        if not self.configured:
            return None, []
        try:
            state = load_morph(self.sources())
        except LoadError as exc:
            return None, list(exc.diagnostics)
        except Exception as exc:  # noqa: BLE001
            # The loader turns unreadable sources into diagnostics rather than
            # exceptions, so reaching here means it met something unexpected.
            # That is a bug to fix, not a reason to take the pack half down with
            # it — loud, and reported through the same channel.
            logger.exception("unexpected error loading the morph snapshot: %s", exc)
            return None, [
                diagnostic(
                    LM_MORPH_001,
                    f"unexpected error loading the morph snapshot: "
                    f"{type(exc).__name__}: {exc}",
                )
            ]
        return state, list(state.diagnostics)

    def commit(self, state: MorphState | None, diagnostics: list[Diagnostic]) -> None:
        """Swap in a prepared snapshot. One pointer assignment, as `PackState`."""
        self._state = state
        self._diagnostics = list(diagnostics)

    def load(self) -> bool:
        """Boot load. Returns whether the configured snapshot is serving."""
        state, diagnostics = self.prepare()
        if self.configured and state is None:
            self._diagnostics = diagnostics
            logger.error(
                "morph snapshot load failed (%d diagnostic(s)) — `morph: true` "
                "pipelines will answer FAILED_PRECONDITION; every other "
                "pipeline, Analyze and BatchLemmatize are unaffected. First: %s",
                len(diagnostics),
                diagnostics[0].message if diagnostics else "?",
            )
            return False

        self.commit(state, diagnostics)
        if state is not None:
            stats = state.stats()
            logger.info(
                "morph snapshot loaded: version=%s language=%s rows=%d forms=%d "
                "worlds=%s",
                stats.version,
                stats.language,
                stats.rows,
                stats.forms,
                ",".join(stats.worlds) or "-",
            )
        return True

    async def reload(self) -> tuple[bool, str, list[Diagnostic]]:
        """Validate-then-swap, alone. `(applied, version, diagnostics)`.

        `ReloadPacks` reloads both halves together (`reload.py`) so that neither
        can half-apply; this exists for the same reason `PackState.reload` does —
        one owner, testable on its own.
        """
        async with self._lock:
            state, diagnostics = self.prepare()
            if self.configured and state is None:
                logger.error(
                    "morph reload refused (%d diagnostic(s)) — snapshot %s "
                    "still serving",
                    len(diagnostics),
                    self.version,
                )
                return False, self.version, diagnostics
            self.commit(state, diagnostics)
            return True, self.version, diagnostics

    @property
    def version(self) -> str:
        return self._state.manifest.version if self._state else ""

    @property
    def lock(self) -> asyncio.Lock:
        return self._lock


def _lemmatize_op():
    """`NlpOp.LEMMATIZE`, imported where it is used.

    `nlp_service.engines.base` pulls in the whole engine surface, and this
    module is imported by the status path; keeping the import local keeps the
    dependency direction (state → engines) out of module scope, where it would
    be one more edge in an import graph the engine-free test walks.
    """
    from nlp_service.engines.base import NlpOp

    return NlpOp.LEMMATIZE


__all__ = ["MORPH_ENGINE", "MORPH_ENGINE_WITH_STATISTICAL", "MorphSnapshot"]
