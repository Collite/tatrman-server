<!-- SPDX-License-Identifier: Apache-2.0 -->
# Hero fixture corpus (NLS-P0.T5)

Canned **backend results** for the two hero scenarios plus one en sample, and the
annotations the later phases must produce from them. These are the offline stand-in
for a running engine stack: P0 uses them for the importers, P1 runs rule packs
against them, P2 runs gazetteers against them, and P4's eval replays them E2E.

## Files

| File | What it is |
|---|---|
| `*.engines.json` | Input — canned engine results, the uniform shape the front's adapters yield |
| `*.expected.yaml` | Output — the `Lookup` and `QueryPattern` annotations P1/P2 must produce |

## The shape of `*.engines.json`

`engines[]` entries are the **uniform result shape** that all four adapters
converge on — the same shape `JsonBackendEngine._parse` builds and the Stanza and
spaCy backends return natively:

```json
{"engine": "stanza",
 "tokens":    [{"text","charStart","charEnd","lemma","upos","xpos","feats","depHead","depRelation"}],
 "entities":  [{"text","label","charStart","charEnd","normalizedValue","sourceEngine"}],
 "sentences": [{"charStart","charEnd"}],
 "modelVersion": "..."}
```

For Stanza and spaCy this is byte-faithful to the backend's own HTTP response
(`services/nlp/backends/*/server.py`). MorphoDiTa and NameTag 3 backends speak
vertical/CoNLL **text** on the wire (`{"result": "word\tlemma\ttag\n…"}`) and
their front-side adapters parse that into this same shape — so what is canned
here is the adapter's output, which is exactly what `build_document` consumes.
The wire-text parsers move into `ttrnlp.client.backends` at NLS-P3 (⚑NLS-D7);
until then the vertical text itself is not part of the wheel's input surface.

## Two NER label vocabularies — deliberate, not an oversight

The cs and en lanes do **not** speak the same NER labels today, and the fixtures
record that honestly rather than harmonising it:

- **cs (NameTag 3)** — CNEC 2.0 tags mapped to the universal coarse set by
  leading class letter: `ORGANIZATION`, `PERSON`, `LOCATION`, `DATE`, else
  `MISC`. The raw tag survives as `normalizedValue: "cnec:<tag>"`.
- **en (spaCy)** — spaCy's own OntoNotes labels pass straight through: `ORG`,
  `PERSON`, `GPE`, …

The annotation **type** is the label, so a rule that wants Czech organisations
matches `{ann: ORGANIZATION}` and its English sibling matches `{ann: ORG}`.
Pack authors need to know which lane they are writing for; harmonising the two
would mean silently rewriting engine output, which the suite does not do.

## Offsets

Character offsets are Python string indices into `text` (not bytes, not UTF-16),
which matters for the Czech heroes — `zákazníka` is 9 characters and 11 bytes.
