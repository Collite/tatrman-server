# SPDX-License-Identifier: Apache-2.0
"""ttr-nlp front configuration.

RG-P1.S1: the front is **engine-free** — every model-bearing engine is an
HTTP-adapter client to a separate backend image (`url`), launched with an
**explicit model id** (`model`, S-1). Only `langid` (lingua) runs in-process.
Each backend declares its pinning `tier`: `SELF_HOSTED_PINNED` (in-cluster,
conformant) or `REMOTE_UNPINNED` (Lindat dev/eval — `RG-NLP-002`).
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Dict, Optional

import yaml
from pydantic import BaseModel, Field


class ServiceConfig(BaseModel):
    host: str = Field(default="0.0.0.0", validation_alias="NLP_SERVICE_HOST")
    port: int = Field(default=7270, validation_alias="NLP_SERVICE_PORT")
    grpc_port: int = Field(default=7271, validation_alias="NLP_SERVICE_GRPC_PORT")


class BackendConfig(BaseModel):
    """A model-bearing engine served by its own backend image (or Lindat).

    `model` is the explicit model id sent to the backend and echoed on every
    response (S-1) — never empty for an enabled model-bearing engine.
    """

    enabled: bool = True
    url: str = ""              # backend base URL (in-cluster) or Lindat endpoint
    model: str = ""            # explicit model id (S-1)
    model_version: str = ""    # backend-reported version / handle
    tier: str = "SELF_HOSTED_PINNED"  # or REMOTE_UNPINNED (Lindat dev/eval)
    timeout_seconds: int = 30
    max_retries: int = 3
    # >0 only for the remote (Lindat) dev tier; self-hosted backends are unthrottled.
    rate_limit_per_minute: int = 0


class LangidEngineConfig(BaseModel):
    """The one engine that stays in the front — lingua, tiny, no model files."""

    enabled: bool = True
    model: str = "lingua"
    model_version: str = "lingua-2.0"


class EnginesConfig(BaseModel):
    morphodita: BackendConfig = Field(default_factory=BackendConfig)
    nametag3: BackendConfig = Field(default_factory=BackendConfig)
    stanza: BackendConfig = Field(default_factory=BackendConfig)
    spacy: BackendConfig = Field(default_factory=BackendConfig)
    langid: LangidEngineConfig = Field(default_factory=LangidEngineConfig)


class AppConfig(BaseModel):
    service: ServiceConfig = Field(default_factory=ServiceConfig)
    engines: EnginesConfig = Field(default_factory=EnginesConfig)
    op_routing: Dict[str, str] = Field(default_factory=dict)
    default_language: str = "cs"
    log_level: str = "INFO"


# Lindat dev/eval endpoints (the REMOTE_UNPINNED tier — RG-NLP-002). Selected by
# NLP_UFAL_ENDPOINT_MODE=lindat; the pinned model ids stay explicit (S-1).
_LINDAT_MORPHODITA = "https://lindat.mff.cuni.cz/services/morphodita/api/tag"
_LINDAT_NAMETAG = "https://lindat.mff.cuni.cz/services/nametag/api/recognize"
_LINDAT_RATE_LIMIT = 5


def apply_env_overrides(config: AppConfig) -> AppConfig:
    """Apply the S2.T4 endpoint repoint from the environment (config-only swap).

    - `NLP_UFAL_ENDPOINT_MODE=lindat` flips MorphoDiTa + NameTag 3 to Lindat as a
      `REMOTE_UNPINNED` dev/eval tier (endpoint + tier + rate-limit change; the
      model id stays pinned — S-1).
    - `NLP_MORPHODITA_URL` / `NLP_NAMETAG3_URL` override just the endpoint.
    """
    if os.getenv("NLP_UFAL_ENDPOINT_MODE", "self_hosted").lower() == "lindat":
        m = config.engines.morphodita
        m.url = _LINDAT_MORPHODITA
        m.tier = "REMOTE_UNPINNED"
        m.rate_limit_per_minute = _LINDAT_RATE_LIMIT
        n = config.engines.nametag3
        n.url = _LINDAT_NAMETAG
        n.tier = "REMOTE_UNPINNED"
        n.rate_limit_per_minute = _LINDAT_RATE_LIMIT

    if url := os.getenv("NLP_MORPHODITA_URL"):
        config.engines.morphodita.url = url
    if url := os.getenv("NLP_NAMETAG3_URL"):
        config.engines.nametag3.url = url

    return config


def load_config(config_path: Optional[str] = None) -> AppConfig:
    """Load configuration from YAML with optional `CONFIG_FILE` override, then
    apply the environment endpoint overrides (S2.T4)."""
    env_cfg = os.getenv("CONFIG_FILE")
    if config_path:
        cfg_path = Path(config_path)
    elif env_cfg:
        cfg_path = Path(env_cfg)
    else:
        cfg_path = Path(__file__).parent.parent / "config.yaml"

    data = _read_yaml_config(cfg_path)
    config = AppConfig(**data) if data else AppConfig()
    return apply_env_overrides(config)


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
