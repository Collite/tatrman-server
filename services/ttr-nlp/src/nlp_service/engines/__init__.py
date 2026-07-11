from __future__ import annotations

from typing import Dict, List, Optional

from nlp_service.config import AppConfig, load_config
from nlp_service.engines.base import NlpEngine, NlpOp
from nlp_service.engines.langid_engine import LangidEngine
from nlp_service.engines.morphodita_engine import MorphoditaEngine
from nlp_service.engines.nametag_engine import NametagEngine
from nlp_service.engines.spacy_engine import SpacyEngine
from nlp_service.engines.stanza_engine import StanzaEngine


class EngineRegistry:
    """Registry of available NLP engines with routing configuration."""

    def __init__(self, config: AppConfig | None = None):
        self._config = config or load_config()
        self._engines: Dict[str, NlpEngine] = {}
        self._op_routing: Dict[str, str] = {}  # {op}.{lang} -> engine_name

        self._register_engines()
        self._load_routing()

    def _register_engines(self):
        """Register all available engines."""
        # Stanza - primary engine for most ops
        if self._config.engines.stanza.enabled:
            self._engines["stanza"] = StanzaEngine(self._config)

        # spaCy - fallback for English NER
        if self._config.engines.spacy.enabled:
            self._engines["spacy"] = SpacyEngine(self._config)

        # NameTag - primary for Czech NER
        if self._config.engines.nametag.enabled:
            self._engines["nametag"] = NametagEngine(self._config)

        # MorphoDiTa - for Czech tokenization/lemmatization/POS (eval only)
        if self._config.engines.morphodita.enabled:
            self._engines["morphodita"] = MorphoditaEngine(self._config)

        # langid - for language detection
        if self._config.engines.langid.enabled:
            self._engines["langid"] = LangidEngine()

    def _load_routing(self):
        """Load operation routing from config."""
        self._op_routing = dict(self._config.op_routing)

    def get_engine(self, name: str) -> Optional[NlpEngine]:
        """Get engine by name."""
        return self._engines.get(name)

    def route_op(self, op: NlpOp, lang: str, engine_hint: str | None = None) -> Optional[NlpEngine]:
        """Route an operation to the appropriate engine.

        Args:
            op: The NLP operation
            lang: Language code
            engine_hint: Optional engine override from request

        Returns:
            The engine to use for this operation, or None if not supported
        """
        # Check engine hint first
        if engine_hint and engine_hint in self._engines:
            engine = self._engines[engine_hint]
            if engine.supports(lang, op):
                return engine

        # Look up routing config
        route_key = f"{op.value}.{lang}"
        engine_name = self._op_routing.get(route_key)

        if engine_name and engine_name in self._engines:
            return self._engines[engine_name]

        # Try to find any engine that supports this op/lang
        for engine in self._engines.values():
            if engine.supports(lang, op):
                return engine

        return None

    def get_all_engines_for_op(self, op: NlpOp, lang: str) -> List[NlpEngine]:
        """Return all engines that support the given op and language.

        Used by COMPARE mode to fan out to all capable engines.
        """
        engines = []
        for engine in self._engines.values():
            if engine.supports(lang, op):
                engines.append(engine)
        return engines

    def get_engine_info(self) -> Dict[str, dict]:
        """Get information about all registered engines for /version endpoint."""
        info = {}
        for name, engine in self._engines.items():
            # Pick a representative language from engine's supported set
            supported_langs = engine.supported_languages()
            rep_lang = next(iter(supported_langs), "")
            info[name] = {
                "languages": list(supported_langs),
                "ops": [op.value for op in NlpOp if rep_lang and engine.supports(rep_lang, op)],
            }
        return info

    def has_engine_for_language(self, lang: str) -> bool:
        """Check if at least one engine supports the given language for any operation."""
        for engine in self._engines.values():
            if engine.supports(lang, NlpOp.TOKENIZE):
                return True
        return False

    def is_ready(self) -> bool:
        """Check if at least one engine is available for the configured default language."""
        return self.has_engine_for_language(self._config.default_language)

    def list_engines(self) -> List[str]:
        """List all registered engine names."""
        return list(self._engines.keys())