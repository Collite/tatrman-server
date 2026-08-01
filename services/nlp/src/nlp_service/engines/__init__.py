# SPDX-License-Identifier: Apache-2.0
"""Engine registry + routing (RG-P1.S1).

Builds the engine-free front's adapters from config and resolves each
(language, op) to a `Route` that names an engine + explicit model + version +
pinning tier (S-1). Unsupported (language, op) resolves to the degrade floor
(RG-NLP-010). Also builds the capability matrix that `GetStatus` returns (RS-7).
"""

from __future__ import annotations

from typing import Dict, List, Optional, Tuple

from nlp_service.config import AppConfig, BackendConfig, LangidEngineConfig, load_config
from nlp_service.diagnostics import RG_NLP_002, RG_NLP_003, RG_NLP_010
from nlp_service.engines.base import NlpEngine, NlpOp
from nlp_service.engines.langid_engine import LangidEngine
from nlp_service.engines.morphodita_engine import MorphoditaEngine
from nlp_service.engines.nametag_engine import Nametag3Engine
from nlp_service.engines.spacy_engine import SpacyEngine
from nlp_service.engines.stanza_engine import StanzaEngine
from nlp_service.routing import (
    FLOOR_ENGINE,
    FLOOR_MODEL,
    FLOOR_MODEL_VERSION,
    Route,
)

# Engines that never carry a routable model op but participate in the floor.
_MODEL_BEARING = ("morphodita", "nametag3", "stanza", "spacy")


class EngineRegistry:
    """Registry of front-side engine adapters with per-op-per-language routing."""

    def __init__(self, config: AppConfig | None = None):
        self._config = config or load_config()
        self._engines: Dict[str, NlpEngine] = {}
        self._backends: Dict[str, BackendConfig] = {}
        self._op_routing: Dict[str, str] = dict(self._config.op_routing)
        self._register_engines()

    # ---- construction -----------------------------------------------------

    def _register_engines(self) -> None:
        e = self._config.engines
        if e.morphodita.enabled:
            self._engines["morphodita"] = MorphoditaEngine(e.morphodita)
            self._backends["morphodita"] = e.morphodita
        if e.nametag3.enabled:
            self._engines["nametag3"] = Nametag3Engine(e.nametag3)
            self._backends["nametag3"] = e.nametag3
        if e.stanza.enabled:
            self._engines["stanza"] = StanzaEngine(e.stanza)
            self._backends["stanza"] = e.stanza
        if e.spacy.enabled:
            self._engines["spacy"] = SpacyEngine(e.spacy)
            self._backends["spacy"] = e.spacy
        if e.langid.enabled:
            self._engines["langid"] = LangidEngine(e.langid)

    # ---- lookup -----------------------------------------------------------

    def get_engine(self, name: str) -> Optional[NlpEngine]:
        return self._engines.get(name)

    def list_engines(self) -> List[str]:
        return list(self._engines.keys())

    def _engine_model(self, name: str) -> Tuple[str, str, str]:
        """(model, model_version, tier) for an engine, from its config."""
        if name == "langid":
            cfg: LangidEngineConfig = self._config.engines.langid
            return cfg.model, cfg.model_version, "SELF_HOSTED_PINNED"
        backend = self._backends.get(name)
        if backend is None:
            return "", "", "SELF_HOSTED_PINNED"
        return backend.model, backend.model_version, backend.tier

    # ---- routing ----------------------------------------------------------

    def _resolve_engine(self, op: NlpOp, lang: str, engine_hint: str | None) -> Optional[str]:
        """The engine *name* for (op, lang), honoring hint → config → capability."""
        if engine_hint and engine_hint in self._engines:
            if self._engines[engine_hint].supports(lang, op):
                return engine_hint

        if op == NlpOp.DETECT_LANGUAGE:
            key = "DETECT_LANGUAGE"
        else:
            key = f"{op.value}.{lang}"
        name = self._op_routing.get(key)
        if name and name in self._engines and self._engines[name].supports(lang, op):
            return name

        # Configured fallback (e.g. NER.en.fallback: spacy).
        fb = self._op_routing.get(f"{key}.fallback")
        if fb and fb in self._engines and self._engines[fb].supports(lang, op):
            return fb

        # Last resort: any engine that supports it.
        for cand, engine in self._engines.items():
            if engine.supports(lang, op):
                return cand
        return None

    def route(self, language: str, op: NlpOp, engine_hint: str | None = None) -> Route:
        """Resolve (language, op) → a fully-described `Route` (never None).

        Falls back to the degrade floor (RG-NLP-010) when no engine serves the
        (language, op). Attaches RG-NLP-002 for a REMOTE_UNPINNED tier and
        RG-NLP-003 when a resolved model-bearing engine has an empty model id.
        """
        name = self._resolve_engine(op, language, engine_hint)
        if name is None:
            return Route(
                op=op, language=language, engine=FLOOR_ENGINE,
                model=FLOOR_MODEL, model_version=FLOOR_MODEL_VERSION,
                tier="SELF_HOSTED_PINNED", adapter=None, is_floor=True,
                info=(RG_NLP_010,),
            )

        model, version, tier = self._engine_model(name)
        info: list[str] = []
        if tier == "REMOTE_UNPINNED":
            info.append(RG_NLP_002)
        if name in _MODEL_BEARING and not model:
            info.append(RG_NLP_003)
        return Route(
            op=op, language=language, engine=name,
            model=model, model_version=version, tier=tier,
            adapter=self._engines.get(name), is_floor=False, info=tuple(info),
        )

    # ---- capability matrix (RS-7) ----------------------------------------

    def capability_matrix(self) -> List[dict]:
        """One row per routed (language, op), built from config + capabilities
        (not hardcoded). Rows: {language, op, engine, model_version, tier}."""
        languages = self._configured_languages()
        rows: List[dict] = []
        for lang in sorted(languages):
            for op in NlpOp:
                route = self.route(lang, op)
                rows.append(
                    {
                        "language": lang,
                        "op": op,
                        "engine": route.engine,
                        "model_version": route.model_version,
                        "tier": route.tier,
                        "is_floor": route.is_floor,
                        "info": route.info,
                    }
                )
        return rows

    def _configured_languages(self) -> set[str]:
        langs: set[str] = set()
        for key in self._op_routing:
            # keys are "OP.lang" or "OP.lang.fallback" or bare "DETECT_LANGUAGE"
            parts = key.split(".")
            if len(parts) >= 2 and parts[1] not in ("fallback",):
                langs.add(parts[1])
        langs.discard("")
        if not langs:
            langs.add(self._config.default_language)
        return langs

    def get_all_engines_for_op(self, op: NlpOp, lang: str) -> List[NlpEngine]:
        """All engines that support (op, lang) — used by COMPARE mode (debug)."""
        return [e for e in self._engines.values() if e.supports(lang, op)]

    def has_engine_for_language(self, lang: str) -> bool:
        return any(engine.supports(lang, NlpOp.TOKENIZE) for engine in self._engines.values())

    def is_ready(self) -> bool:
        return self.has_engine_for_language(self._config.default_language)
