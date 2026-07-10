from __future__ import annotations

import logging
import secrets
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set

from kadmos_service.config import AppConfig, load_config
from kadmos_service.engines import EngineRegistry
from kadmos_service.engines.base import EngineResult, NerEntity, NlpOp, Token

logger = logging.getLogger(__name__)


class NlpPipelineError(Exception):
    """Base exception for NLP pipeline errors."""
    pass


class EngineNotFoundError(NlpPipelineError):
    """Raised when no engine is available for a requested operation."""
    pass


@dataclass
class AnalyzeResponse:
    """Response object matching the proto AnalyzeResponse shape."""

    language: str
    language_confidence: float
    engine_used: str
    tokens: List[Token] = field(default_factory=list)
    sentences: List[tuple[int, int]] = field(default_factory=list)
    paragraphs: List[tuple[int, int]] = field(default_factory=list)
    entities: List[NerEntity] = field(default_factory=list)
    by_engine: Dict[str, EngineResult] = field(default_factory=dict)
    trace_id: str = ""
    elapsed_ms: int = 0
    messages: List[dict] = field(default_factory=list)


class Orchestrator:
    """Orchestrates NLP analysis across multiple engines in NORMAL mode.

    Handles:
    - Language auto-detection if not provided
    - Per-operation engine routing
    - Result merging from multiple engines
    - Engine hints override
    """

    def __init__(self, config: AppConfig | None = None, registry: EngineRegistry | None = None):
        self._config = config or load_config()
        self._registry = registry or EngineRegistry(self._config)

    def analyze(
        self,
        text: str,
        language: str,
        ops: Set[NlpOp],
        mode: str = "NORMAL",
        engine_hints: Optional[Dict[str, str]] = None,
    ) -> AnalyzeResponse:
        """Run NLP analysis on the input text.

        Args:
            text: Input text to analyze
            language: Language code (empty triggers auto-detection)
            ops: Set of requested operations
            mode: NORMAL or COMPARE
            engine_hints: Optional {op: engine_name} overrides

        Returns:
            AnalyzeResponse with tokens, entities, sentences, etc.
        """
        start_time = time.perf_counter()
        trace_id = _generate_trace_id()
        engine_hints = engine_hints or {}

        messages: List[dict] = []

        # Handle language detection
        detected_lang = language
        _detected_confidence = 1.0  # Used in NORMAL mode for language detection confidence

        if not language and NlpOp.DETECT_LANGUAGE in ops:
            lang_result = self._detect_language(text)
            if lang_result.error:
                messages.append({
                    "severity": "WARNING",
                    "code": "lang_detection_failed",
                    "message": f"Language detection failed: {lang_result.error}. Defaulting to Czech."
                })
                detected_lang = "cs"
            else:
                detected_lang = lang_result.detected_language or self._config.default_language
                _detected_confidence = lang_result.language_confidence or 1.0
            # Remove DETECT_LANGUAGE from ops after we've detected
            ops = ops - {NlpOp.DETECT_LANGUAGE}

        if not detected_lang:
            detected_lang = self._config.default_language

        if mode == "COMPARE":
            return self._analyze_compare(
                text=text,
                language=detected_lang,
                ops=ops,
                engine_hints=engine_hints,
                trace_id=trace_id,
                start_time=start_time,
                messages=messages,
            )
        else:
            return self._analyze_normal(
                text=text,
                language=detected_lang,
                ops=ops,
                engine_hints=engine_hints,
                trace_id=trace_id,
                start_time=start_time,
                messages=messages,
            )

    def _analyze_normal(
        self,
        text: str,
        language: str,
        ops: Set[NlpOp],
        engine_hints: Dict[str, str],
        trace_id: str,
        start_time: float,
        messages: List[dict],
    ) -> AnalyzeResponse:
        """NORMAL mode: route ops to primary engine per config, merge results."""
        # Group operations by engine for efficient batching
        ops_by_engine: Dict[str, Set[NlpOp]] = {}

        for op in ops:
            # Check for engine hint for this op
            hint = engine_hints.get(op.value, None)
            engine = self._registry.route_op(op, language, hint)

            if engine is None:
                messages.append({
                    "severity": "WARNING",
                    "code": "engine_not_found",
                    "message": f"No engine available for {op.value} in {language}"
                })
                continue

            engine_name = engine.name
            if engine_name not in ops_by_engine:
                ops_by_engine[engine_name] = set()
            ops_by_engine[engine_name].add(op)

        # Run each engine that has pending operations
        engine_results: Dict[str, EngineResult] = {}
        primary_engine = ""
        all_tokens: List[Token] = []
        all_entities: List[NerEntity] = []
        all_sentences: List[tuple[int, int]] = []
        all_paragraphs: List[tuple[int, int]] = []

        logger.debug(
            "Orchestrator dispatching | text_len=%d language=%s ops_by_engine=%s",
            len(text),
            language,
            {k: sorted(o.value for o in v) for k, v in ops_by_engine.items()},
        )

        for engine_name, engine_ops in ops_by_engine.items():
            engine = self._registry.get_engine(engine_name)
            if not engine:
                logger.debug("Orchestrator: engine '%s' not in registry — skipping", engine_name)
                continue

            logger.debug(
                "Orchestrator → %s.analyze(ops=%s)",
                engine_name,
                sorted(o.value for o in engine_ops),
            )
            result = engine.analyze(text, language, engine_ops)
            logger.debug(
                "Orchestrator ← %s.analyze | tokens=%d entities=%d sentences=%d error=%r",
                engine_name,
                len(result.tokens),
                len(result.entities),
                len(result.sentences),
                result.error,
            )

            if result.error and not result.tokens and not result.entities:
                messages.append({
                    "severity": "ERROR",
                    "code": "engine_error",
                    "message": f"{engine_name} failed: {result.error}"
                })
                continue

            engine_results[engine_name] = result

            # Use first engine with results as primary
            if not primary_engine and result.tokens:
                primary_engine = engine_name

            # Merge results
            all_tokens.extend(result.tokens)
            all_entities.extend(result.entities)
            all_sentences.extend(result.sentences)
            all_paragraphs.extend(result.paragraphs)

        # Deduplicate entities by char span
        entities_by_span: Dict[tuple[int, int], NerEntity] = {}
        for ent in all_entities:
            span = (ent.char_start, ent.char_end)
            # Keep entity from primary engine or highest priority
            if span not in entities_by_span:
                entities_by_span[span] = ent
            elif primary_engine and ent.source_engine == primary_engine:
                entities_by_span[span] = ent

        final_entities = list(entities_by_span.values())

        # Sort tokens by char_start
        final_tokens = sorted(all_tokens, key=lambda t: t.char_start)

        # Sort sentences
        final_sentences = sorted(set(all_sentences), key=lambda s: s[0])
        final_paragraphs = sorted(set(all_paragraphs), key=lambda p: p[0])

        elapsed_ms = int((time.perf_counter() - start_time) * 1000)

        return AnalyzeResponse(
            language=language,
            language_confidence=1.0,
            engine_used=primary_engine,
            tokens=final_tokens,
            sentences=final_sentences,
            paragraphs=final_paragraphs,
            entities=final_entities,
            by_engine={},  # Empty in NORMAL mode
            trace_id=trace_id,
            elapsed_ms=elapsed_ms,
            messages=messages,
        )

    def _analyze_compare(
        self,
        text: str,
        language: str,
        ops: Set[NlpOp],
        engine_hints: Dict[str, str],
        trace_id: str,
        start_time: float,
        messages: List[dict],
    ) -> AnalyzeResponse:
        """COMPARE mode: fan out to ALL engines that support (language, op) in parallel.

        Collects per-engine results in by_engine map. Top-level fields reflect
        the configured primary engine (first engine with results).
        Partial results (some engines fail) are still returned.
        """
        # Collect all (engine, op) pairs for all ops
        engine_ops: Dict[str, Set[NlpOp]] = {}
        for op in ops:
            # In COMPARE mode, ignore engine hints - run ALL engines that support the op
            for engine in self._registry.get_all_engines_for_op(op, language):
                engine_name = engine.name
                if engine_name not in engine_ops:
                    engine_ops[engine_name] = set()
                engine_ops[engine_name].add(op)

        # Run all engines in parallel using thread pool
        engine_results: Dict[str, EngineResult] = {}
        primary_engine = ""

        def run_engine(engine_name: str, engine_ops: Set[NlpOp]) -> tuple[str, EngineResult]:
            engine = self._registry.get_engine(engine_name)
            if not engine:
                return (engine_name, EngineResult(error=f"Engine {engine_name} not found"))
            try:
                return (engine_name, engine.analyze(text, language, engine_ops))
            except Exception as e:
                logger.exception(f"Engine {engine_name} error: {e}")
                return (engine_name, EngineResult(error=str(e)))

        with ThreadPoolExecutor(max_workers=len(engine_ops)) as executor:
            futures = {
                executor.submit(run_engine, name, ops): name
                for name, ops in engine_ops.items()
            }
            for future in as_completed(futures):
                engine_name, result = future.result()
                engine_results[engine_name] = result
                if result.error:
                    messages.append({
                        "severity": "ERROR",
                        "code": "engine_error",
                        "message": f"{engine_name} failed: {result.error}"
                    })
                elif not primary_engine and result.tokens:
                    primary_engine = engine_name

        # Build merged results from primary engine
        primary_result = engine_results.get(primary_engine, EngineResult())

        # For tokens/entities, use primary engine's output as reference
        # but also track all entities across engines for the by_engine map
        all_entities: List[NerEntity] = []
        for eng_result in engine_results.values():
            all_entities.extend(eng_result.entities)

        # Deduplicate entities by char span (prefer primary engine)
        entities_by_span: Dict[tuple[int, int], NerEntity] = {}
        for ent in all_entities:
            span = (ent.char_start, ent.char_end)
            if span not in entities_by_span:
                entities_by_span[span] = ent
            elif primary_engine and ent.source_engine == primary_engine:
                entities_by_span[span] = ent

        final_tokens = sorted(primary_result.tokens, key=lambda t: t.char_start) if primary_result.tokens else []
        final_sentences = sorted(set(primary_result.sentences), key=lambda s: s[0])
        final_paragraphs = sorted(set(primary_result.paragraphs), key=lambda p: p[0])
        final_entities = list(entities_by_span.values())

        elapsed_ms = int((time.perf_counter() - start_time) * 1000)

        return AnalyzeResponse(
            language=language,
            language_confidence=1.0,
            engine_used=primary_engine,
            tokens=final_tokens,
            sentences=final_sentences,
            paragraphs=final_paragraphs,
            entities=final_entities,
            by_engine=engine_results,  # Populated in COMPARE mode
            trace_id=trace_id,
            elapsed_ms=elapsed_ms,
            messages=messages,
        )

    def _detect_language(self, text: str) -> EngineResult:
        """Detect the language of the input text."""
        langid_engine = self._registry.get_engine("langid")
        if not langid_engine:
            return EngineResult(error="Language detection engine not available")
        return langid_engine.analyze(text, "", {NlpOp.DETECT_LANGUAGE})


def _generate_trace_id() -> str:
    """Generate a simple trace ID."""
    return secrets.token_hex(16)