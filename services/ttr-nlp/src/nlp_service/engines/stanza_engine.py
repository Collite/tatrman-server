# SPDX-License-Identifier: Apache-2.0
"""Stanza engine — front-side HTTP adapter (RG-P1.S1).

The front is engine-free: Stanza runs in its own backend image (RG-P1.S3) and
the front talks to it over the uniform JSON contract. No `import stanza`, no
torch in the front. Stanza serves cs DEP_PARSE (hot path for the resolver's
span proposal) + en tokenize/lemma/pos/dep/ner. Its bundled cs model has no NER
head, so cs NER is routed to NameTag 3 — reflected in the capability map below.
"""

from __future__ import annotations

from nlp_service.config import BackendConfig
from nlp_service.engines.base import NlpOp
from nlp_service.engines.json_backend import JsonBackendEngine

_CS_OPS = {NlpOp.TOKENIZE, NlpOp.SENTENCE_SPLIT, NlpOp.LEMMATIZE, NlpOp.POS_TAG, NlpOp.DEP_PARSE}
_EN_OPS = _CS_OPS | {NlpOp.NER}


class StanzaEngine(JsonBackendEngine):
    def __init__(self, backend: BackendConfig):
        super().__init__("stanza", backend, {"cs": _CS_OPS, "en": _EN_OPS})
