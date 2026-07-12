# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S2.T4 — the front's UFAL adapters repoint by config, not code.

Default = the self-hosted in-cluster backends (`SELF_HOSTED_PINNED`). An
env-gated toggle (`NLP_UFAL_ENDPOINT_MODE=lindat`) flips MorphoDiTa + NameTag 3
to Lindat as a `REMOTE_UNPINNED` dev/eval tier — the model id stays pinned
(S-1), only the endpoint + tier + rate-limit change, and the tier surfaces in
the matrix as `RG-NLP-002`.
"""

from __future__ import annotations


from nlp_service.config import AppConfig, BackendConfig, EnginesConfig, apply_env_overrides
from nlp_service.diagnostics import RG_NLP_002
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import NlpOp


def _base() -> AppConfig:
    return AppConfig(
        engines=EnginesConfig(
            morphodita=BackendConfig(
                url="http://ttr-nlp-morphodita:8080/tag",
                model="czech-morfflex2.0-pdtc1.0-220710",
                model_version="czech-morfflex2.0-pdtc1.0-220710",
                tier="SELF_HOSTED_PINNED",
            ),
            nametag3=BackendConfig(
                url="http://ttr-nlp-nametag3:8001/recognize",
                model="nametag3-czech-cnec2.0-240830",
                model_version="nametag3-czech-cnec2.0-240830",
                tier="SELF_HOSTED_PINNED",
            ),
        ),
        op_routing={"LEMMATIZE.cs": "morphodita", "NER.cs": "nametag3"},
    )


class TestSelfHostedDefault:
    def test_default_is_self_hosted_pinned(self, monkeypatch):
        monkeypatch.delenv("NLP_UFAL_ENDPOINT_MODE", raising=False)
        cfg = apply_env_overrides(_base())
        assert cfg.engines.morphodita.tier == "SELF_HOSTED_PINNED"
        assert "lindat" not in cfg.engines.morphodita.url
        assert cfg.engines.nametag3.tier == "SELF_HOSTED_PINNED"


class TestLindatToggle:
    def test_lindat_mode_flips_to_remote_unpinned(self, monkeypatch):
        monkeypatch.setenv("NLP_UFAL_ENDPOINT_MODE", "lindat")
        cfg = apply_env_overrides(_base())
        assert cfg.engines.morphodita.tier == "REMOTE_UNPINNED"
        assert "lindat.mff.cuni.cz" in cfg.engines.morphodita.url
        assert cfg.engines.nametag3.tier == "REMOTE_UNPINNED"
        assert "lindat.mff.cuni.cz" in cfg.engines.nametag3.url
        # S-1: the model id stays explicitly pinned even on the remote tier.
        assert cfg.engines.morphodita.model == "czech-morfflex2.0-pdtc1.0-220710"
        assert cfg.engines.nametag3.model == "nametag3-czech-cnec2.0-240830"
        # Lindat is rate-limited.
        assert cfg.engines.morphodita.rate_limit_per_minute > 0

    def test_lindat_mode_surfaces_rg_nlp_002_in_matrix(self, monkeypatch):
        monkeypatch.setenv("NLP_UFAL_ENDPOINT_MODE", "lindat")
        registry = EngineRegistry(apply_env_overrides(_base()))
        route = registry.route("cs", NlpOp.LEMMATIZE)
        assert route.tier == "REMOTE_UNPINNED"
        assert RG_NLP_002 in route.info


class TestPerBackendUrlOverride:
    def test_explicit_url_env_override(self, monkeypatch):
        monkeypatch.delenv("NLP_UFAL_ENDPOINT_MODE", raising=False)
        monkeypatch.setenv("NLP_MORPHODITA_URL", "http://custom-morphodita:9000/tag")
        cfg = apply_env_overrides(_base())
        assert cfg.engines.morphodita.url == "http://custom-morphodita:9000/tag"
        # Tier unchanged (still self-hosted) when only the URL is overridden.
        assert cfg.engines.morphodita.tier == "SELF_HOSTED_PINNED"
