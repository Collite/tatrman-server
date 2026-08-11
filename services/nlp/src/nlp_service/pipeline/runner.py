# SPDX-License-Identifier: Apache-2.0
"""One named pipeline, run end to end (contracts §7, NL-4/NL-14).

Engine ops, then gazetteer lists, then rule phases — in that order, and within
each stage in the order the pipeline wrote. The order is not a detail: the
gazetteer matches on `lemma`, which an engine op has to have produced, and the
rules match on `Lookup`, which the gazetteer has to have produced. A pipeline that
listed them the other way round would run, match nothing, and look exactly like a
pack with a typo in it.

**Degrade is a message, not a failure** (NL-14). An op the active lane cannot
route is skipped, `NLS-NLP-011` is appended, and every remaining phase still runs.
The Czech invoices hero is the reason: without cs NER the pack's fallback rule
reaches the same `QueryPattern` through morphology, and aborting the request would
throw away an answer the pack author explicitly arranged to still be reachable.

**Every stage is traced** (`PhaseTrace`, contracts §2.2). One entry per executed
step with what it added and how long it took, because "the rules produced nothing"
has at least four causes — no tokens, no lemmas, no Lookups, no match — and the
trace is what tells them apart without a second request.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any

from ttrnlp.doc import Document, build_document
from ttrnlp.packs.loader import LoadedState
from ttrnlp.rules.pipeline import run_phases

from nlp_service.config import AppConfig, PipelineConfig
from nlp_service.diagnostics import NLS_NLP_011, message
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import EngineVersion, NlpOp
from nlp_service.pipeline.orchestrator import Orchestrator

logger = logging.getLogger(__name__)

KIND_ENGINE = "engine"
KIND_GAZETTEER = "gazetteer"
KIND_RULES = "rules"


@dataclass
class Trace:
    """One executed step (contracts §2.2 `PhaseTrace`)."""

    phase: str
    kind: str
    annotations_added: int
    elapsed_ms: int


@dataclass
class PipelineResult:
    document: Document
    language: str
    language_confidence: float = 1.0
    used: list[EngineVersion] = field(default_factory=list)
    traces: list[Trace] = field(default_factory=list)
    messages: list[dict] = field(default_factory=list)
    trace_id: str = ""
    elapsed_ms: int = 0


class UnknownPipelineError(LookupError):
    """The requested pipeline is not configured. Carries what is."""

    def __init__(self, name: str, available: list[str]):
        self.name = name
        self.available = available
        configured = ", ".join(sorted(available)) or "none configured"
        super().__init__(f"unknown pipeline {name!r} — configured: {configured}")


def _ops_of(spec: PipelineConfig) -> list[NlpOp]:
    """The spec's ops as enum members, skipping names this build does not know.

    An unknown op name is a config error, but not one worth failing a request
    over: the pipeline table is validated at boot, and by the time a request
    arrives the useful thing is to run what is recognisable.
    """
    ops: list[NlpOp] = []
    for name in spec.ops:
        try:
            ops.append(NlpOp(name))
        except ValueError:
            logger.warning("pipeline op %r is not a known NlpOp — skipped", name)
    return ops


class PipelineRunner:
    """Runs configured pipelines against the current pack snapshot."""

    def __init__(
        self,
        config: AppConfig,
        registry: EngineRegistry,
        orchestrator: Orchestrator | None = None,
    ):
        self._config = config
        self._registry = registry
        self._orchestrator = orchestrator or Orchestrator(config, registry)

    def pipeline_names(self) -> list[str]:
        return sorted(self._config.pipelines)

    def unrouted_ops(self, language: str, ops: list[NlpOp]) -> list[NlpOp]:
        """Ops the active lane cannot serve — the NL-14 degrade set.

        "Cannot serve" means the route fell through to the degrade floor AND the
        floor cannot produce the op either. A floor-served TOKENIZE is a real
        (if plain) result and not a degrade.
        """
        return [
            op
            for op in ops
            if self._registry.route(language, op).is_floor
            and not self._registry.floor_serves(language, op)
        ]

    def run(
        self,
        text: str,
        pipeline: str,
        state: LoadedState,
        *,
        language: str = "",
    ) -> PipelineResult:
        """Run `pipeline` over `text` against `state`.

        Raises:
            UnknownPipelineError: If `pipeline` is not configured.
        """
        spec = self._config.pipelines.get(pipeline)
        if spec is None:
            raise UnknownPipelineError(pipeline, self.pipeline_names())

        started = time.perf_counter()
        traces: list[Trace] = []
        messages: list[dict] = []

        doc, language, confidence, used, engine_traces = self._run_engines(
            text, language, spec, messages
        )
        traces.extend(engine_traces)
        traces.extend(self._run_gazetteer(doc, spec, state))
        traces.extend(self._run_rules(doc, spec, state))

        doc.features["pipeline"] = pipeline
        doc.features["lane"] = self._config.lane

        return PipelineResult(
            document=doc,
            language=language,
            language_confidence=confidence,
            used=used,
            traces=traces,
            messages=messages,
            elapsed_ms=int((time.perf_counter() - started) * 1000),
        )

    # ---- stages -----------------------------------------------------------

    def _run_engines(
        self, text: str, language: str, spec: PipelineConfig, messages: list[dict]
    ) -> tuple[Document, str, float, list[EngineVersion], list[Trace]]:
        """Engine ops through the existing orchestrator, then into a Document.

        The orchestrator is reused rather than reimplemented: it already does
        lane-routed grouping, one call per backend, the token merge and the S-1
        `used[]` stamping, and a second path through the same backends would
        drift from `Analyze` in ways only a live cluster would show.
        """
        ops = _ops_of(spec)
        wanted = set(ops)

        for op in self.unrouted_ops(language or self._config.default_language, ops):
            messages.append(
                message(NLS_NLP_011, f"{language or self._config.default_language}/{op.value}")
            )
            wanted.discard(op)

        # No language given ⇒ ask for detection too, exactly as Analyze does when
        # `language` is empty (contracts §2.2).
        requested = wanted if language else wanted | {NlpOp.DETECT_LANGUAGE}

        started = time.perf_counter()
        analysis = self._orchestrator.analyze(
            text=text, language=language, ops=requested
        )
        elapsed = int((time.perf_counter() - started) * 1000)

        resolved = analysis.language or language or self._config.default_language
        doc = build_document(
            text, _as_engine_results(analysis), language=resolved
        )

        # The orchestrator's own diagnostics ride along — a Lindat tier or an S-1
        # violation matters just as much on this rpc as on Analyze.
        messages.extend(analysis.messages)

        traces = [
            Trace(
                phase=",".join(sorted(op.value for op in wanted)) or "none",
                kind=KIND_ENGINE,
                annotations_added=len(doc.annset("")),
                elapsed_ms=elapsed,
            )
        ]
        return doc, resolved, analysis.language_confidence, list(analysis.used), traces

    def _run_gazetteer(
        self, doc: Document, spec: PipelineConfig, state: LoadedState
    ) -> list[Trace]:
        traces: list[Trace] = []
        for list_id in spec.gazetteer:
            started = time.perf_counter()
            added = state.gazetteer.annotate(doc, lists=[list_id])
            traces.append(
                Trace(
                    phase=list_id,
                    kind=KIND_GAZETTEER,
                    annotations_added=added,
                    elapsed_ms=int((time.perf_counter() - started) * 1000),
                )
            )
        return traces

    def _run_rules(
        self, doc: Document, spec: PipelineConfig, state: LoadedState
    ) -> list[Trace]:
        traces: list[Trace] = []
        for ref in spec.rules:
            pack = state.packs.get(ref.pack)
            if pack is None:
                # Boot validated every ref (NLS-PACK-004), so reaching this means
                # the snapshot changed under a request. Skip and say so rather
                # than raising: the phases that did run produced real annotations.
                logger.warning("pipeline references missing pack %r", ref.pack)
                continue
            started = time.perf_counter()
            before = len(doc.annset(""))
            report = run_phases(doc, [pack], phases=[ref.phase])
            traces.append(
                Trace(
                    phase=f"{ref.pack}:{ref.phase}",
                    kind=KIND_RULES,
                    annotations_added=len(doc.annset("")) - before,
                    elapsed_ms=int((time.perf_counter() - started) * 1000),
                )
            )
            logger.debug("phase %s:%s fired %d rule(s)", ref.pack, ref.phase, report.firings)
        return traces


def _as_engine_results(analysis) -> list[dict[str, Any]]:
    """The orchestrator's merged analysis in the importers' uniform shape.

    One result, not one per engine: the orchestrator has already merged tokens to
    one per span and resolved which engine's lemma/POS/parse won, and handing the
    importers the pre-merge streams would put every word in the document once per
    engine that saw it.
    """
    return [
        {
            "engine": analysis.engine_used,
            "modelVersion": next(
                (ev.model_version for ev in analysis.used if ev.model_version), ""
            ),
            "tokens": [
                {
                    "text": t.text,
                    "charStart": t.char_start,
                    "charEnd": t.char_end,
                    "lemma": t.lemma,
                    "upos": t.upos,
                    "xpos": t.xpos,
                    "feats": dict(t.feats or {}),
                    "depHead": t.dep_head,
                    "depRelation": t.dep_relation,
                }
                for t in analysis.tokens
            ],
            "sentences": [
                {"charStart": start, "charEnd": end} for start, end in analysis.sentences
            ],
            "entities": [
                {
                    "text": e.text,
                    "label": e.label,
                    "charStart": e.char_start,
                    "charEnd": e.char_end,
                    "normalizedValue": e.normalized_value,
                    "sourceEngine": e.source_engine,
                }
                for e in analysis.entities
            ],
        }
    ]


__all__ = [
    "KIND_ENGINE",
    "KIND_GAZETTEER",
    "KIND_RULES",
    "PipelineResult",
    "PipelineRunner",
    "Trace",
    "UnknownPipelineError",
]
