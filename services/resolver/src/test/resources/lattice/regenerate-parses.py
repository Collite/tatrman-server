#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Rebuild `*.parse.json` from the RV-P0.2 spike's cached Stanza parses.

    python3 regenerate-parses.py <path-to>/implementation/spikes/frame-roles

The parse half is copied field-for-field (the spike's derived `depHeadIdx` is dropped —
it is a harness convenience, not a proto field). The `entities` half is authored here and
justified in PROVENANCE.md; keep the two in step if you touch either.
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).parent

H1 = "Zobraz náklady účtu 501001 v roce 2025 podle období"
H1P = "Zobraz náklady účtu 5010O1 v roce 2025 podle období"
H2 = "Zobraz prvních 10 čerpacích stanic v Praze podle tržby za 12 měsíců."
H5 = "Ukaž vývoj nákladů střediska 220 za posledních 12 měsíců a porovnej s plánem."


def span(text: str, needle: str) -> tuple[int, int]:
    start = text.index(needle)
    return start, start + len(needle)


def ner(text: str, needle: str, label: str, normalized: str) -> dict:
    start, end = span(text, needle)
    return {
        "text": needle,
        "label": label,
        "charStart": start,
        "charEnd": end,
        "normalizedValue": normalized,
        "sourceEngine": "nametag3",
    }


# Authored NER — see PROVENANCE.md §"The parses are real; the NER layer is authored".
CASES = {
    "h1-cs": (H1, [ner(H1, "2025", "DATE", "2025")]),
    "h1prime-cs": (H1P, [ner(H1P, "2025", "DATE", "2025")]),
    "h2-cs": (H2, [ner(H2, "Praze", "LOC", "cnec:gu"), ner(H2, "12 měsíců", "DATE", "P12M")]),
    # RV-P2.5.T4 — the H5 core slice. Same discipline: the parse is the spike's real Stanza
    # output, only the NER layer is authored, and only where a cs NER front genuinely fires.
    # `posledních 12 měsíců` is the chrono WINDOW the design's H5 asks for — the whole phrase,
    # not the bare `12 měsíců`, because "posledních" is what makes it relative.
    # `220` is deliberately NOT tagged: it must reach the member index as střediska's value,
    # exactly as `501001` must in H1.
    "h5-cs": (H5, [ner(H5, "posledních 12 měsíců", "DATE", "P12M")]),
}


def main(spike: pathlib.Path) -> None:
    for fixture_id, (text, entities) in CASES.items():
        key = hashlib.sha256(f"cs\x00{text}".encode()).hexdigest()[:16]
        doc = json.loads((spike / "parses" / f"{key}.json").read_text(encoding="utf-8"))
        tokens = []
        for t in doc["tokens"]:
            token = {
                "text": t["text"],
                "charStart": t["charStart"],
                "charEnd": t["charEnd"],
                "lemma": t["lemma"],
                "upos": t["upos"],
                "xpos": t.get("xpos", ""),
                "depHead": t["depHead"],
                "depRelation": t["depRelation"],
            }
            if t.get("feats"):
                token["feats"] = t["feats"]
            tokens.append(token)
        parse = {
            "language": "cs",
            "detectedLanguage": "cs",
            "traceId": f"trace-{fixture_id}",
            "tokens": tokens,
            "entities": entities,
            "used": [
                {"op": "DEP_PARSE", "engine": "stanza", "model": "cs", "modelVersion": doc.get("modelVersion") or "1.13.0"},
                {"op": "NER", "engine": "nametag3", "model": "cnec2.0", "modelVersion": "240830"},
            ],
        }
        out = HERE / f"{fixture_id}.parse.json"
        out.write_text(json.dumps(parse, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{fixture_id}: {len(tokens)} tokens, {len(entities)} entities  <- parses/{key}.json")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(pathlib.Path(sys.argv[1]))
