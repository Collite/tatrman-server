# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import os
from pathlib import Path
from typing import Dict, Optional

import yaml
from pydantic import BaseModel, Field


class ServiceConfig(BaseModel):
    host: str = Field(default="0.0.0.0", validation_alias="NLP_SERVICE_HOST")
    port: int = Field(default=7270, validation_alias="NLP_SERVICE_PORT")


class StanzaEngineConfig(BaseModel):
    enabled: bool = True
    model_dir: str = "/opt/nlp-models/stanza"


class SpacyEngineConfig(BaseModel):
    enabled: bool = True
    model_name: str = "en_core_web_md"


class NametagEngineConfig(BaseModel):
    enabled: bool = True
    # `/services/nametag` 301-redirects to a landing page; the REST API is at
    # `/api/recognize` (kept in sync with config.yaml).
    endpoint: str = "https://lindat.mff.cuni.cz/services/nametag/api/recognize"
    timeout_seconds: int = 30
    max_retries: int = 3
    rate_limit_per_minute: int = 5


class LangidEngineConfig(BaseModel):
    enabled: bool = True


class MorphoditaEngineConfig(BaseModel):
    # On by default: Czech tokenize/lemmatize/POS route through morphodita
    # (config.yaml), so a config-less boot must not silently lose cs morphology.
    enabled: bool = True
    # REST API at `/api/tag` (same shape as nametag/api/recognize).
    endpoint: str = "https://lindat.mff.cuni.cz/services/morphodita/api/tag"
    timeout_seconds: int = 30
    max_retries: int = 3
    rate_limit_per_minute: int = 5


class EnginesConfig(BaseModel):
    stanza: StanzaEngineConfig = Field(default_factory=StanzaEngineConfig)
    spacy: SpacyEngineConfig = Field(default_factory=SpacyEngineConfig)
    nametag: NametagEngineConfig = Field(default_factory=NametagEngineConfig)
    morphodita: MorphoditaEngineConfig = Field(default_factory=MorphoditaEngineConfig)
    langid: LangidEngineConfig = Field(default_factory=LangidEngineConfig)


class AppConfig(BaseModel):
    service: ServiceConfig = Field(default_factory=ServiceConfig)
    engines: EnginesConfig = Field(default_factory=EnginesConfig)
    op_routing: Dict[str, str] = Field(default_factory=dict)
    default_language: str = "cs"
    log_level: str = "INFO"


def load_config(config_path: Optional[str] = None) -> AppConfig:
    """Load configuration from YAML file with optional environment variable overrides.

    CONFIG_FILE env var can override the default config path.
    """
    # Determine config file path
    env_cfg = os.getenv("CONFIG_FILE")
    if config_path:
        cfg_path = Path(config_path)
    elif env_cfg:
        cfg_path = Path(env_cfg)
    else:
        # Default to config.yaml in the same directory as this file's parent
        cfg_path = Path(__file__).parent.parent / "config.yaml"

    data = _read_yaml_config(cfg_path)
    return AppConfig(**data) if data else AppConfig()


def _read_yaml_config(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        content = path.read_text(encoding="utf-8")
    except Exception:
        return {}
    try:
        data = yaml.safe_load(content) or {}
        if not isinstance(data, dict):
            return {}
        return data
    except Exception:
        return {}