# SPDX-License-Identifier: Apache-2.0
"""spaCy engine — front-side HTTP adapter (RG-P1.S1).

The front is engine-free: spaCy (`en_core_web_md`) runs in its own backend
image (RG-P1.S3); the front talks to it over the uniform JSON contract. No
`import spacy` in the front. spaCy is the English NER fallback + tokenizer.
"""

from __future__ import annotations

from nlp_service.config import BackendConfig
from nlp_service.engines.base import NlpOp
from nlp_service.engines.json_backend import JsonBackendEngine

_EN_OPS = {NlpOp.TOKENIZE, NlpOp.NER}


class SpacyEngine(JsonBackendEngine):
    def __init__(self, backend: BackendConfig):
        super().__init__("spacy", backend, {"en": _EN_OPS})
