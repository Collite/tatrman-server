# SPDX-License-Identifier: Apache-2.0
"""spaCy backend server (RG-P1.S3.T2) — the uniform-JSON NLP contract.

Standalone FastAPI app the front's `JsonBackendEngine` adapter talks to; runs
spaCy `en_core_web_md` in-process (baked). Serves English tokenize + NER (the
English NER fallback). Same JSON shape as the Stanza backend.
"""

from __future__ import annotations

import os

import spacy
from fastapi import FastAPI
from pydantic import BaseModel

MODEL_NAME = os.getenv("SPACY_MODEL", "en_core_web_md")
_nlp = spacy.load(MODEL_NAME)
MODEL_VERSION = f"{MODEL_NAME}-{_nlp.meta.get('version', '')}"

app = FastAPI(title="ttr-nlp spaCy backend")


class AnalyzeRequest(BaseModel):
    text: str
    language: str = "en"
    ops: list[str] = []
    model: str = ""


@app.get("/healthz")
def healthz():
    return {"status": "ok"}


@app.get("/readyz")
def readyz():
    return {"status": "ready", "modelVersion": MODEL_VERSION}


@app.post("/analyze")
def analyze(req: AnalyzeRequest):
    doc = _nlp(req.text)
    tokens = []
    for token in doc:
        head_idx = token.head.i + 1 if token.head.i != token.i else 0
        tokens.append(
            {
                "text": token.text,
                "charStart": token.idx,
                "charEnd": token.idx + len(token.text),
                "lemma": token.lemma_,
                "upos": token.pos_,
                "xpos": token.tag_,
                "feats": {},
                "depHead": head_idx,
                "depRelation": token.dep_,
            }
        )
    entities = [
        {
            "text": ent.text,
            "label": ent.label_,
            "charStart": ent.start_char,
            "charEnd": ent.end_char,
            "normalizedValue": "",
            "sourceEngine": "spacy",
        }
        for ent in doc.ents
    ]
    sentences = [{"charStart": s.start_char, "charEnd": s.end_char} for s in doc.sents]
    return {"tokens": tokens, "entities": entities, "sentences": sentences, "modelVersion": MODEL_VERSION}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8091")))
