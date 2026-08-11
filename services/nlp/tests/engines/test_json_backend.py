# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S3.T2 — the uniform-JSON contract shared by the front adapter and the
Stanza/spaCy backend servers.

Guards that the parser reads exactly the shape the backend `server.py` files emit
(`backends/{stanza,spacy}/server.py`) — token/entity/sentence fields + the
`modelVersion` echo (S-1).

NLS-P3.3 (⚑NLS-D7): the parser moved to `ttrnlp.client.backends.parse_uniform_json`
and takes the client rather than being a private method on the engine. The
assertions are unchanged — this file is the regression harness for that move.
"""

from __future__ import annotations

from ttrnlp.client.backends import parse_uniform_json

from nlp_service.config import BackendConfig
from nlp_service.engines.base import NlpOp
from nlp_service.engines.json_backend import JsonBackendEngine


def _engine() -> JsonBackendEngine:
    return JsonBackendEngine(
        "stanza",
        BackendConfig(url="http://stanza:8090", model="stanza-cs-en", model_version="v0"),
        {"cs": {NlpOp.DEP_PARSE}, "en": {NlpOp.NER}},
    )


_BODY = {
    "tokens": [
        {
            "text": "Octavie", "charStart": 0, "charEnd": 7, "lemma": "Octavia",
            "upos": "PROPN", "xpos": "NNIP1", "feats": {"Case": "Acc"},
            "depHead": 2, "depRelation": "obj",
        }
    ],
    "entities": [
        {"text": "Shell", "label": "ORG", "charStart": 10, "charEnd": 15,
         "normalizedValue": "", "sourceEngine": "stanza"}
    ],
    "sentences": [{"charStart": 0, "charEnd": 20}],
    "modelVersion": "stanza-1.10.0",
}


class TestJsonBackendParse:
    def test_parses_tokens_entities_sentences(self):
        eng = _engine()
        result = parse_uniform_json(_BODY, eng._client)
        t = result.tokens[0]
        assert (t.text, t.lemma, t.upos, t.dep_head, t.dep_relation) == ("Octavie", "Octavia", "PROPN", 2, "obj")
        assert t.feats == {"Case": "Acc"}
        assert result.entities[0].label == "ORG"
        assert result.sentences == [(0, 20)]

    def test_learns_reported_model_version_for_s1(self):
        eng = _engine()
        parse_uniform_json(_BODY, eng._client)
        # Still readable off the engine — the property delegates to the client.
        assert eng.reported_model_version == "stanza-1.10.0"

    def test_supports_reflects_capability_map(self):
        eng = _engine()
        assert eng.supports("cs", NlpOp.DEP_PARSE)
        assert eng.supports("en", NlpOp.NER)
        assert not eng.supports("cs", NlpOp.NER)
