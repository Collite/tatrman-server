# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S2.T1 — MorphoDiTa self-hosted backend parity (component tier).

Reproduces the Q-10 method against the self-hosted backend: for every input,
the self-hosted MorphoDiTa's `(form, lemma, xpos)` triples must equal Lindat's
with the **same pinned model** (`czech-morfflex2.0-pdtc1.0-220710`). Plus the
hero-sentence documented-lemma golden (Q-10 §2 ground truth).

`@pytest.mark.component` — skipped in the default tier; run with `-m component`.
Requires the self-hosted backend up (URL from `NLP_MORPHODITA_URL`, default the
in-cluster service) and Lindat reachable. Skips (not fails) when the backend is
unreachable, so the suite stays green until RG-P1.S2 stands the image up.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

import httpx
import pytest

from nlp_service.config import BackendConfig
from nlp_service.engines.base import NlpOp
from nlp_service.engines.morphodita_engine import MorphoditaEngine

pytestmark = pytest.mark.component

_FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"
_CORPUS = Path(__file__).resolve().parent.parent.parent / "eval" / "corpus" / "seed.jsonl"
_PINNED = "czech-morfflex2.0-pdtc1.0-220710"
_LINDAT = "https://lindat.mff.cuni.cz/services/morphodita/api/tag"


def _hero() -> dict:
    return json.loads((_FIXTURES / "parity_hero.json").read_text(encoding="utf-8"))["hero"]


def _inputs(n: int = 22) -> list[str]:
    """Hero + the first cs seed questions → the Q-10 22-case shape."""
    texts = [_hero()["text"]]
    if _CORPUS.exists():
        for line in _CORPUS.read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            if row.get("lang") == "cs":
                texts.append(row["question"])
            if len(texts) >= n:
                break
    return texts


def _self_hosted() -> MorphoditaEngine:
    url = os.getenv("NLP_MORPHODITA_URL", "http://ttr-nlp-morphodita:8080/tag")
    return MorphoditaEngine(BackendConfig(url=url, model=_PINNED, model_version=_PINNED))


def _lindat() -> MorphoditaEngine:
    return MorphoditaEngine(
        BackendConfig(url=_LINDAT, model=_PINNED, model_version=_PINNED, rate_limit_per_minute=5)
    )


def _triples(engine: MorphoditaEngine, text: str) -> list[tuple[str, str, str]]:
    result = engine.analyze(text, "cs", {NlpOp.TOKENIZE, NlpOp.LEMMATIZE, NlpOp.POS_TAG})
    if result.error:
        raise RuntimeError(result.error)
    return [(t.text, t.lemma, t.xpos) for t in result.tokens]


def _require_backend(engine: MorphoditaEngine) -> None:
    try:
        _triples(engine, "Ahoj")
    except (httpx.HTTPError, httpx.NetworkError, RuntimeError) as e:
        pytest.skip(f"MorphoDiTa self-hosted backend not reachable: {e}")


def test_hero_lemmas_match_q10_golden():
    engine = _self_hosted()
    _require_backend(engine)
    hero = _hero()
    result = engine.analyze(hero["text"], "cs", {NlpOp.TOKENIZE, NlpOp.LEMMATIZE})
    by_form = {t.text: t.lemma for t in result.tokens}
    for form, expected_lemma in hero["morphodita_lemmas"].items():
        assert by_form.get(form) == expected_lemma, f"{form} → {by_form.get(form)} != {expected_lemma}"


@pytest.mark.parametrize("text", _inputs())
def test_self_hosted_matches_lindat(text):
    self_hosted = _self_hosted()
    _require_backend(self_hosted)
    lindat = _lindat()
    try:
        expected = _triples(lindat, text)
    except (httpx.HTTPError, RuntimeError) as e:
        pytest.skip(f"Lindat reference not reachable: {e}")
    assert _triples(self_hosted, text) == expected
