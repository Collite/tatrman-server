# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S1.T5 — GetStatus capability matrix.

One `Capability{language, op, engine, model_version, tier}` per routed
(language, op), built from config + engine capabilities (not hardcoded). A
Lindat-pointed route reports `REMOTE_UNPINNED` (+ `RG-NLP-002`); a self-hosted
route reports `SELF_HOSTED_PINNED`.
"""

from __future__ import annotations


from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
)
from nlp_service.diagnostics import RG_NLP_002
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import NlpOp


def _config(morphodita_tier: str = "SELF_HOSTED_PINNED", morphodita_url: str = "http://morphodita:8080/tag") -> AppConfig:
    return AppConfig(
        engines=EnginesConfig(
            morphodita=BackendConfig(
                url=morphodita_url,
                model="czech-morfflex2.0-pdtc1.0-220710",
                model_version="czech-morfflex2.0-pdtc1.0-220710",
                tier=morphodita_tier,
            ),
            nametag3=BackendConfig(
                url="http://nametag3:8001/recognize",
                model="nametag3-czech-cnec2.0-240830",
                model_version="nametag3-czech-cnec2.0-240830",
                tier="SELF_HOSTED_PINNED",
            ),
            stanza=BackendConfig(url="http://stanza:8090", model="stanza-cs-en", model_version="1.10.0"),
            spacy=BackendConfig(url="http://spacy:8091", model="en_core_web_md", model_version="3.8.0"),
            langid=LangidEngineConfig(model_version="lingua-2.0"),
        ),
        op_routing={
            "TOKENIZE.cs": "morphodita",
            "LEMMATIZE.cs": "morphodita",
            "POS_TAG.cs": "morphodita",
            "DEP_PARSE.cs": "stanza",
            "NER.cs": "nametag3",
            "TOKENIZE.en": "stanza",
            "NER.en": "stanza",
            "DETECT_LANGUAGE": "langid",
        },
        default_language="cs",
    )


class TestCapabilityMatrix:
    def test_one_row_per_routed_lang_op(self):
        registry = EngineRegistry(_config())
        rows = registry.capability_matrix()
        # Both configured languages appear.
        langs = {r["language"] for r in rows}
        assert langs == {"cs", "en"}
        # Every NlpOp is represented for each language.
        cs_ops = {r["op"] for r in rows if r["language"] == "cs"}
        assert set(NlpOp) == cs_ops

    def test_self_hosted_route_reports_pinned_with_real_version(self):
        registry = EngineRegistry(_config())
        rows = registry.capability_matrix()
        lemma_cs = next(r for r in rows if r["language"] == "cs" and r["op"] == NlpOp.LEMMATIZE)
        assert lemma_cs["engine"] == "morphodita"
        assert lemma_cs["tier"] == "SELF_HOSTED_PINNED"
        assert lemma_cs["model_version"] == "czech-morfflex2.0-pdtc1.0-220710"
        assert RG_NLP_002 not in lemma_cs["info"]

    def test_lindat_route_reports_remote_unpinned_with_rg_nlp_002(self):
        registry = EngineRegistry(
            _config(
                morphodita_tier="REMOTE_UNPINNED",
                morphodita_url="https://lindat.mff.cuni.cz/services/morphodita/api/tag",
            )
        )
        rows = registry.capability_matrix()
        lemma_cs = next(r for r in rows if r["language"] == "cs" and r["op"] == NlpOp.LEMMATIZE)
        assert lemma_cs["tier"] == "REMOTE_UNPINNED"
        assert RG_NLP_002 in lemma_cs["info"]

    def test_matrix_not_hardcoded_reflects_config_engine(self):
        """Re-route cs LEMMATIZE to stanza in config → matrix follows."""
        cfg = _config()
        cfg.op_routing["LEMMATIZE.cs"] = "stanza"
        registry = EngineRegistry(cfg)
        rows = registry.capability_matrix()
        lemma_cs = next(r for r in rows if r["language"] == "cs" and r["op"] == NlpOp.LEMMATIZE)
        assert lemma_cs["engine"] == "stanza"
