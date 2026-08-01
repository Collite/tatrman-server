# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S3.T1 — BatchLemmatize holds at both hops (test-first).

`Orchestrator.batch_lemmatize` fans a batched request to the MorphoDiTa backend
in ONE call (front → backend), not a per-string HTTP loop (the pilot's
`nlp.enabled=false` cause). Returns positional `results[]` + the S-1 `used[]`
echo. Sized to the Q-10.T5 curve (≤100k short strings → one raised-limit call).
"""

from __future__ import annotations

from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
)
from nlp_service.contract import is_s1_clean
from nlp_service.engines import EngineRegistry
from nlp_service.pipeline.orchestrator import Orchestrator


def _config() -> AppConfig:
    return AppConfig(
        engines=EnginesConfig(
            morphodita=BackendConfig(
                url="http://morphodita:8080/tag",
                model="czech-morfflex2.0-pdtc1.0-220710",
                model_version="czech-morfflex2.0-pdtc1.0-220710",
            ),
            langid=LangidEngineConfig(model_version="lingua-2.0"),
        ),
        op_routing={"LEMMATIZE.cs": "morphodita", "DETECT_LANGUAGE": "langid"},
        default_language="cs",
    )


def test_batch_is_positional_and_single_backend_call(monkeypatch):
    registry = EngineRegistry(_config())
    calls = {"n": 0, "sizes": []}

    def fake_batch(texts, lang):
        calls["n"] += 1
        calls["sizes"].append(len(texts))
        # positional: one lemma list per input.
        return [[t.lower().rstrip("aeiy")] for t in texts]

    monkeypatch.setattr(registry.get_engine("morphodita"), "batch_lemmatize", fake_batch)
    orch = Orchestrator(_config(), registry)

    texts = ["Octavie", "pobočkách", "tržby"]
    result = orch.batch_lemmatize(texts, "cs")

    # BOTH HOPS: exactly ONE backend call for all N texts (not per-string).
    assert calls["n"] == 1
    assert calls["sizes"] == [3]
    assert len(result.results) == len(texts)  # positional
    # S-1: used[] names the engine+model, no blank.
    assert result.used and result.used[0].engine == "morphodita"
    assert is_s1_clean(result.used)


def test_batch_scales_to_q10_curve(monkeypatch):
    """A 10k-string batch is still ONE backend call (Q-10 §4 batched path)."""
    registry = EngineRegistry(_config())
    calls = {"n": 0}

    def fake_batch(texts, lang):
        calls["n"] += 1
        return [[t] for t in texts]

    monkeypatch.setattr(registry.get_engine("morphodita"), "batch_lemmatize", fake_batch)
    orch = Orchestrator(_config(), registry)

    result = orch.batch_lemmatize([f"slovo{i}" for i in range(10_000)], "cs")
    assert calls["n"] == 1
    assert len(result.results) == 10_000


def test_empty_batch_is_noop(monkeypatch):
    registry = EngineRegistry(_config())
    orch = Orchestrator(_config(), registry)
    result = orch.batch_lemmatize([], "cs")
    assert result.results == []
