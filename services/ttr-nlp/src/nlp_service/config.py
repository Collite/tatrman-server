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


def load_config(config_path: Optional[str] = None) -> AppConfig:
    """Load configuration from YAML with optional `CONFIG_FILE` override."""
    env_cfg = os.getenv("CONFIG_FILE")
    if config_path:
        cfg_path = Path(config_path)
    elif env_cfg:
        cfg_path = Path(env_cfg)
    else:
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
