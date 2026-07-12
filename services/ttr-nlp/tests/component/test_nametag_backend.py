# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S2.T1 — NameTag 3 self-hosted backend parity (component tier).

Reproduces the Q-10 method: for every input, the self-hosted NameTag 3's
`(entity_text, raw_cnec_tag)` spans must equal Lindat's with the same pinned
model (`nametag3-czech-cnec2.0-240830`). Plus the hero-sentence documented
entity (`pražských pobočkách` → LOCATION, Q-10 §2).

`@pytest.mark.component` — run with `-m component`. Requires the self-hosted
backend up (`NLP_NAMETAG3_URL`, default in-cluster) + Lindat reachable; skips
otherwise so the default suite stays green until the image is stood up.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

import httpx
import pytest

from nlp_service.config import BackendConfig
from nlp_service.engines.base import NlpOp
from nlp_service.engines.nametag_engine import Nametag3Engine

pytestmark = pytest.mark.component

_FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"
_CORPUS = Path(__file__).resolve().parent.parent.parent / "eval" / "corpus" / "seed.jsonl"
_PINNED = "nametag3-czech-cnec2.0-240830"
_LINDAT = "https://lindat.mff.cuni.cz/services/nametag/api/recognize"


def _hero() -> dict:
    return json.loads((_FIXTURES / "parity_hero.json").read_text(encoding="utf-8"))["hero"]


def _inputs(n: int = 22) -> list[str]:
    texts = [_hero()["text"]]
    if _CORPUS.exists():
        for line in _CORPUS.read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            if row.get("lang") == "cs":
                texts.append(row["question"])
            if len(texts) >= n:
                break
    return texts


def _self_hosted() -> Nametag3Engine:
    url = os.getenv("NLP_NAMETAG3_URL", "http://ttr-nlp-nametag3:8001/recognize")
    return Nametag3Engine(BackendConfig(url=url, model=_PINNED, model_version=_PINNED))


def _lindat() -> Nametag3Engine:
    return Nametag3Engine(
        BackendConfig(url=_LINDAT, model=_PINNED, model_version=_PINNED, rate_limit_per_minute=5)
    )


def _spans(engine: Nametag3Engine, text: str) -> list[tuple[str, str]]:
    """(entity_text, raw CNEC tag) — the model-faithful parity key."""
    result = engine.analyze(text, "cs", {NlpOp.NER})
    if result.error:
        raise RuntimeError(result.error)
    return [(e.text, e.normalized_value) for e in result.entities]


def _require_backend(engine: Nametag3Engine) -> None:
    try:
        _spans(engine, "Praha")
    except (httpx.HTTPError, httpx.NetworkError, RuntimeError) as e:
        pytest.skip(f"NameTag 3 self-hosted backend not reachable: {e}")


def test_hero_product_entity_survives_as_misc():
    """The hero's NER: `Octavie` (CNEC `op`, product) → MISC, NOT dropped.

    NameTag does not tag lowercase `pražských pobočkách` — that phrase is
    grounded via geo containment in RG-P3, not by NER. So the hero's only NER
    entity is the product name, which must survive as MISC (the leading-letter
    trap this guards) for the resolver's domain path to catch it.
    """
    engine = _self_hosted()
    _require_backend(engine)
    hero = _hero()
    result = engine.analyze(hero["text"], "cs", {NlpOp.NER})
    labels_by_text = {e.text: e.label for e in result.entities}
    assert "Octavie" in labels_by_text, f"Octavie dropped; got {labels_by_text}"
    assert labels_by_text["Octavie"] == "MISC"


@pytest.mark.parametrize("text", _inputs())
def test_self_hosted_matches_lindat(text):
    self_hosted = _self_hosted()
    _require_backend(self_hosted)
    lindat = _lindat()
    try:
        expected = _spans(lindat, text)
    except (httpx.HTTPError, RuntimeError) as e:
        pytest.skip(f"Lindat reference not reachable: {e}")
    assert _spans(self_hosted, text) == expected
