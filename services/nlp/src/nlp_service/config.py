# SPDX-License-Identifier: Apache-2.0
"""nlp front configuration.

RG-P1.S1: the front is **engine-free** — every model-bearing engine is an
HTTP-adapter client to a separate backend image (`url`), launched with an
**explicit model id** (`model`, S-1). Only `langid` (lingua) runs in-process.
Each backend declares its pinning `tier`: `SELF_HOSTED_PINNED` (in-cluster,
conformant) or `REMOTE_UNPINNED` (Lindat dev/eval — `RG-NLP-002`).

NLS-P3.2 adds the **lane** switch (NL-4) and the pack/list/pipeline sections
(contracts §7).

**What a lane is.** `default` is the lane a deployment can run anywhere: Stanza
and spaCy, permissively licensed, no UFAL images. `option` adds the UFAL stack
(MorphoDiTa, NameTag 3), whose licence is a per-deployment decision (NL-5). So
the lane is not a preference between engines that are all present — it decides
*which engines are there at all*, and that is why `base op_routing` IS the
default lane rather than a lane-neutral table with the lane picking favourites.

**`NER.cs` is the case the whole design turns on.** Stanza's Czech bundle has no
NER head, so in the default lane nothing can serve it. It is left out of the base
table on purpose, the request still runs every other phase, and the response
carries `NLS-NLP-011` saying so (NL-14: honest degrade, never a silent one).
"""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Dict, List, Literal, Optional

import yaml
from pydantic import BaseModel, ConfigDict, Field

logger = logging.getLogger(__name__)


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


LANE_DEFAULT = "default"
LANE_OPTION = "option"


class SourcesConfig(BaseModel):
    """Where packs or lists come from (NL-15). Dirs and/or `http(s)` URLs."""

    model_config = ConfigDict(extra="forbid")

    sources: List[str] = Field(default_factory=list)


class RuleRef(BaseModel):
    """One `(pack, phase)` step of a pipeline's rule stage."""

    model_config = ConfigDict(extra="forbid")

    pack: str
    phase: str


class PipelineConfig(BaseModel):
    """A named pipeline (contracts §7): engine ops, then gazetteer lists, then
    rule phases — in that order, and in the order written."""

    model_config = ConfigDict(extra="forbid")

    ops: List[str] = Field(default_factory=list)
    gazetteer: List[str] = Field(default_factory=list)
    rules: List[RuleRef] = Field(default_factory=list)

    def steps(self) -> List[str]:
        """The step names `GetStatus` reports (contracts §2.4 `PipelineInfo`)."""
        return [
            *self.ops,
            *self.gazetteer,
            *(f"{ref.pack}:{ref.phase}" for ref in self.rules),
        ]


class AppConfig(BaseModel):
    service: ServiceConfig = Field(default_factory=ServiceConfig)
    engines: EnginesConfig = Field(default_factory=EnginesConfig)
    # Base routing IS the default-lane routing — see the module docstring.
    op_routing: Dict[str, str] = Field(default_factory=dict)
    lane: Literal["default", "option"] = LANE_DEFAULT
    #: lane name -> routing overlay, applied over `op_routing` when active.
    lane_overrides: Dict[str, Dict[str, str]] = Field(default_factory=dict)
    packs: SourcesConfig = Field(default_factory=SourcesConfig)
    lists: SourcesConfig = Field(default_factory=SourcesConfig)
    pipelines: Dict[str, PipelineConfig] = Field(default_factory=dict)
    default_language: str = "cs"
    log_level: str = "INFO"

    # `extra` is left permissive on THIS model, deliberately. The new nested
    # sections above forbid it — their whole shape is ours, and a typo'd
    # `gazeteer:` that silently loaded no lists is the exact failure NL-15 exists
    # to prevent. Tightening the top level too would mean any deployed config
    # carrying a key this build does not know about stops the service from
    # booting, and there are configmaps out there this checkout cannot see.

    def resolved_op_routing(self) -> Dict[str, str]:
        """The routing table for the ACTIVE lane: base, then the overlay."""
        routing = dict(self.op_routing)
        if self.lane != LANE_DEFAULT:
            routing.update(self.lane_overrides.get(self.lane, {}))
        return routing

    def lane_gated_engines(self) -> set[str]:
        """Engines that exist only inside a lane overlay.

        These are the ones the default lane does not have: an engine named
        *exclusively* by an overlay is, by construction, part of that lane's
        stack. Engines the base table names are always available, so a config
        with no `lane_overrides` at all behaves exactly as it did before this
        section existed — which is why every pre-NLS config and test is
        unaffected.

        This matters more than it looks. Routing has a last-resort scan ("any
        engine that supports this op"), so simply *omitting* `NER.cs` from the
        base table would not make it unrouted — the scan would find NameTag 3
        anyway and the NL-14 degrade would never happen. Gating availability is
        what makes "unrouted" real.
        """
        overlaid: set[str] = set()
        for overlay in self.lane_overrides.values():
            overlaid.update(overlay.values())
        return overlaid - set(self.op_routing.values())

    def withheld_engines(self) -> set[str]:
        """Engines the ACTIVE lane does not admit. Empty means "all of them".

        A gated engine is admitted when the active lane's own overlay names it —
        so with two overlays (`option`, say, and something experimental), running
        `option` admits option's engines and still withholds the other's.
        """
        gated = self.lane_gated_engines()
        if not gated:
            return set()
        admitted = set(self.lane_overrides.get(self.lane, {}).values())
        return gated - admitted


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
    - `NLP_LANE=default|option` selects the engine stack (NL-4, NLS-P3.2). An env
      var rather than config-only because the lane is a *deployment* decision —
      the helm chart sets it per environment, and the alternative is a per-cluster
      copy of `config.yaml` differing in one line.
    """
    if lane := os.getenv("NLP_LANE"):
        lane = lane.strip().lower()
        if lane in (LANE_DEFAULT, LANE_OPTION):
            config.lane = lane  # type: ignore[assignment]
        else:
            # Not fatal, and deliberately so: a typo'd lane must not take the
            # front down, and `default` is the lane that needs no licence
            # decision — the safe one to be wrong towards.
            logger.warning(
                "NLP_LANE=%r is not a known lane — keeping %r", lane, config.lane
            )

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
        # config.yaml lives at the SERVICE ROOT (services/nlp/config.yaml),
        # three levels up from this file (src/nlp_service/config.py) — NOT src/.
        # (The container sets CONFIG_FILE explicitly; this default matters for
        # local/non-container boots, where a wrong path silently dropped the
        # pinned models to empty AppConfig() defaults.)
        cfg_path = Path(__file__).resolve().parent.parent.parent / "config.yaml"

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
