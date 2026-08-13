# Bootstrap batch — world `hartland`

The p9-2 cascade, run over the uncovered target vocabulary. Nothing here is verified: `auto-validated` means the engine generated the word from the pattern it proposed (LM-14), which is evidence, not a decision. Everything below still needs a person.

## Counts

| | |
|---|---:|
| targets worked | 11 |
| already covered, skipped | 4 |
| **auto-validated** (spot-check these) | 4 |
| **proposed** (the review) | 7 |
| nothing proposed (author these) | 3 |

### By list

- `hartland.lex.yaml` — 11

### By tier

The leg that reached the answer. `human` means the cascade declined.

- **human** — 7
- **guesser** — 4

### By layer (LM-10 routing)

- **core** — 7
- **world** — 4

### Patterns proposed

A pattern taking an implausible share of the batch is the thing to
notice here — it usually means the guesser found a shape, not a word.

- `píseň` — 3
- `hrad-u` — 2
- `hrad-proper` — 2
- `muzeum-um` — 1

## Samples

### Auto-validated (4)

| word | lemma | upos | vzor | conf | source | notes |
|---|---|---|---|---:|---|---|
| `půlrok` | půlrok | NOUN | `hrad-u` | 0.95 | guesser | — |
| `YoY` | YoY | PROPN | `hrad-proper` | 0.95 | guesser | — |
| `svátky` | svátk | NOUN | `hrad-u` | 0.85 | guesser | — |
| `PSČ` | PSČ | PROPN | `hrad-proper` | 0.95 | guesser | — |

### Proposed (7)

| word | lemma | upos | vzor | conf | source | notes |
|---|---|---|---|---:|---|---|
| `pololetích` | pololetum | NOUN | `muzeum-um` | 0.80 | guesser | 'pololetích' ends in an inflectional ending: the deterministic leg may propose but not auto-validate |
| `H1` | — | — | — | — | human | — |
| `H2` | — | — | — | — | human | — |
| `pol.` | — | — | — | — | human | — |
| `meziroční` | meziroční | NOUN | `píseň` | 0.60 | guesser | — |
| `sezóně` | sezóně | NOUN | `píseň` | 0.60 | guesser | — |
| `státě` | státě | NOUN | `píseň` | 0.60 | guesser | — |

### Nothing proposed (3)

| word | lemma | upos | vzor | conf | source | notes |
|---|---|---|---|---:|---|---|
| `H1` | — | — | — | — | human | — |
| `H2` | — | — | — | — | human | — |
| `pol.` | — | — | — | — | human | — |

## What happens next

1. Read this. If the shape is wrong — one pattern swallowing the batch, a tier doing more than it should — say so before anything is ingested.
2. `POST /v1/ingest` the rows file into the world's studio. They arrive at the status this report gives them; the cascade is not run again.
3. Work the queue (FI-7 surface 3). `verified` is the human act.
4. `POST /v1/export` → the layer files → recompile → the next `morph/v*`.

