# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S3.T4 — the degrade floor (test-first).

An unsupported language returns TOKENIZE + fold + DETECT_LANGUAGE only, with an
`RG-NLP-010` info per dropped op, the matrix reporting the gap, and `used[]`
still naming what produced the tokens (the floor). No 500, no silence — the
resolver (RG-P5, the first consumer) reads the matrix and branches.
"""

from __future__ import annotations

from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
)
from nlp_service.diagnostics import RG_NLP_010
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import NlpOp
from nlp_service.floor import fold
from nlp_service.pipeline.orchestrator import Orchestrator


def _config() -> AppConfig:
    return AppConfig(
        engines=EnginesConfig(
            morphodita=BackendConfig(url="http://m:8080/tag", model="cs-model", model_version="v1"),
            nametag3=BackendConfig(url="http://n:8001/recognize", model="ner-model", model_version="v1"),
            langid=LangidEngineConfig(model_version="lingua-2.0"),
        ),
        op_routing={"LEMMATIZE.cs": "morphodita", "NER.cs": "nametag3", "DETECT_LANGUAGE": "langid"},
        default_language="cs",
    )


class TestFold:
    def test_fold_lowercases_and_strips_diacritics(self):
        assert fold("Křižík") == "krizik"
        assert fold("Octavie") == "octavie"
        assert fold("PRAŽSKÝCH") == "prazskych"


class TestDegradeFloor:
    def test_unsupported_language_tokenizes_via_floor(self):
        orch = Orchestrator(_config())
        result = orch.analyze("Hallo Welt", "de", {NlpOp.TOKENIZE, NlpOp.LEMMATIZE})

        # TOKENIZE served by the floor — tokens produced, no exception.
        assert [t.text for t in result.tokens] == ["Hallo", "Welt"]
        # fold applied (the S-2 normalization) as the deterministic floor lemma.
        assert result.tokens[0].lemma == fold("Hallo") == "hallo"
        # the unsupported LEMMATIZE is labelled, not silently dropped.
        assert RG_NLP_010 in [m["code"] for m in result.messages]

    def test_floor_stamps_used_with_producer(self):
        orch = Orchestrator(_config())
        result = orch.analyze("Hallo Welt", "de", {NlpOp.TOKENIZE})
        # S-1 holds on the floor: used[] names the floor producer, no blank model.
        tokenize_used = [ev for ev in result.used if ev.op == "TOKENIZE"]
        assert tokenize_used
        assert tokenize_used[0].engine == "floor"
        assert tokenize_used[0].model  # never blank

    def test_detect_language_still_works_on_unsupported(self):
        orch = Orchestrator(_config())
        result = orch.analyze("Hallo Welt wie geht es dir", "", {NlpOp.DETECT_LANGUAGE, NlpOp.TOKENIZE})
        # langid detects (de), tokens via floor, both stamped.
        engines = {ev.engine for ev in result.used}
        assert "langid" in engines
        assert "floor" in engines

    def test_matrix_reports_the_gap(self):
        """A config with no NER engine → the matrix marks cs NER as floor."""
        cfg = _config()
        cfg.op_routing.pop("NER.cs")
        cfg.engines.nametag3.enabled = False
        registry = EngineRegistry(cfg)
        rows = {(r["language"], r["op"]): r for r in registry.capability_matrix()}
        ner_cs = rows[("cs", NlpOp.NER)]
        assert ner_cs["is_floor"] is True
        assert RG_NLP_010 in ner_cs["info"]
