# Bootstrap batch — world `core`

The p9-2 cascade, run over the uncovered target vocabulary. Nothing here is verified: `auto-validated` means the engine generated the word from the pattern it proposed (LM-14), which is evidence, not a decision. Everything below still needs a person.

## Counts

| | |
|---|---:|
| targets worked | 2 |
| already covered, skipped | 147 |
| **auto-validated** (spot-check these) | 1 |
| **proposed** (the review) | 1 |
| nothing proposed (author these) | 0 |

### By list

- `target-words.yaml` — 2

### By tier

The leg that reached the answer. `human` means the cascade declined.

- **guesser** — 1
- **human** — 1

### By layer (LM-10 routing)

- **core** — 2

### Patterns proposed

A pattern taking an implausible share of the batch is the thing to
notice here — it usually means the guesser found a shape, not a word.

- `hrad-u` — 1
- `píseň` — 1

## Samples

### Auto-validated (1)

| word | lemma | upos | vzor | conf | source | notes |
|---|---|---|---|---:|---|---|
| `kvartál` | kvartál | NOUN | `hrad-u` | 0.95 | guesser | — |

### Proposed (1)

| word | lemma | upos | vzor | conf | source | notes |
|---|---|---|---|---:|---|---|
| `největší` | největší | NOUN | `píseň` | 0.60 | guesser | — |

### Nothing proposed (0)

_none_

## What happens next

1. Read this. If the shape is wrong — one pattern swallowing the batch, a tier doing more than it should — say so before anything is ingested.
2. `POST /v1/ingest` the rows file into the world's studio. They arrive at the status this report gives them; the cascade is not run again.
3. Work the queue (FI-7 surface 3). `verified` is the human act.
4. `POST /v1/export` → the layer files → recompile → the next `morph/v*`.

