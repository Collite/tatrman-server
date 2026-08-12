# morph-studio

The editorial service for the Czech morphology lexicon (LM) — ⚑LMP-D5,
[contracts §7](../../../project/server/features/cz-lemma/contracts.md).

> **DB works, files publish.** A Postgres working store in the B-F5 shape, the
> enrichment cascade over it, and an export that emits canonical `*.morph.yaml`
> layer files with a gate at `verified`. Nothing here is read at query time: the
> runtime serves a compiled snapshot, and this is where the next one comes from.

**One instance per world** (LM-5/S-4). `MORPH_WORLD` is required and has no
default. A world's queue, its overlay and its export are its own, and queues
never cross worlds.

## What it does

```
front (services/nlp)                      morph-studio
─────────────────────                     ────────────
a query misses a word                     POST /v1/ingest      ← the spool row
  → morph.queue sink spools it              queue_item, deduped on (world, token)
                                            cascade: guesser → [inflector: Wave C]
                                                     → LLM tie-break → human
                                            LM-10 routes it: core, or the world
                                          auto-validated + world + proper noun
                                            → Q-7: the overlay is re-emitted
                                              with `provisional` rows...
  ReloadPacks  ←───────────────────────────  ...and the front is asked to reload
the SAME query now resolves
                                          a reviewer verifies  → permanent
                                                     rejects   → retracted
                                          POST /v1/export      → layer files
                                            (the gate: `verified` and above)
```

## The endpoints

| | |
|---|---|
| `GET /v1/lookup/{form}` | FI-7 surface 1 — a word and everything known about it, `matched_via` exact/folded |
| `GET/POST /v1/entries`, `GET /v1/entries/{id}` | the store's lexemes |
| `POST /v1/entries/{id}/try-pattern` | generate a pattern's table for correction (`apply: true` saves it) |
| `POST /v1/entries/{id}/ask-llm` | the classifier's proposal — shown as a diff, never saved by itself |
| `POST /v1/entries/{id}/forms` | the corrected table (⇒ a full-form entry at export) |
| `POST /v1/entries/{id}/status` | the LM-14 machine; an illegal edge is a **409** |
| `POST /v1/ingest` | the front's spool rows |
| `GET /v1/queue` | FI-7 surfaces 3+4, ordered by how often the front saw the word |
| `POST /v1/queue/{id}/verdict` | `verify` · `reject` · `route` (LM-10's human override) |
| `POST /v1/export` | the layer files, gated |
| `POST /v1/export/proposals` | the DFP lane (LM-12) — proposed-entry fragments for `ai-models` |
| `GET /v1/vzory` | the closed pattern inventory the UI's picker is built from |
| `GET /v1/status`, `/healthz`, `/readyz` | |

`GET /openapi.json` is the frontend's codegen input (NLS-P9.3 T1).

## The cascade

The thinking is in **`ttr-morph`**, not here: `ttrmorph.enrich.guesser`,
`ttrmorph.enrich.llm` and `ttrmorph.enrich.cascade` are importable and testable
with no database, no app and no network. This service is the store, the status
machine, the endpoints and the overlay lane around them.

* **guesser** — deterministic, from the B-O5 sub-vzor inventory's hints. Every
  proposal is *generated and checked*: the pattern must actually produce the
  observed form. Confidence is a rank assembled from five stated signals, and
  the auto-validate line is set so nothing clears it without a matched
  `lemma_pattern`.
* **inflector** — Wave C (LM-6). Absent in v1; the cascade runs guesser → LLM.
* **LLM classifier** — asked which *pattern*, never for forms. Off unless
  `MORPH_LLM_URL` is set: **guesser → human is a supported deployment**, which
  is what makes an air-gapped world possible.
* **auto-validate** = the observed form is in the engine-generated paradigm
  (LM-14) **and** either the deterministic leg was confident or two independent
  legs named the same entry. Re-checked at every verdict, whoever proposed it.

## Q-7, narrowly

An auto-validated **proper noun for a world's entity layer** may go live before
a human sees it: the overlay is re-emitted with `provenance: provisional`, and
the front is asked to reload. Verifying makes those rows permanent; rejecting
retracts them and names the retraction in the file's header.

It never touches a core snapshot. `MORPH_STUDIO_PROVISIONAL=false` turns it off
entirely; `MORPH_STUDIO_OVERLAY_DIR` unset means no overlay is emitted at all
(the deployment publishes through the layer-file lane instead).

## Configuration

| Variable | |
|---|---|
| `MORPH_WORLD` | **required** — this instance's world (LM-5) |
| `MORPH_STUDIO_DB_URL` | `postgresql+psycopg://…`; SQLite is fine for a laptop |
| `MORPH_STUDIO_HOST` / `_PORT` | default `0.0.0.0:7290` |
| `MORPH_STUDIO_MODE` | `studio` (default) or `dfp` (LM-12) |
| `MORPH_STUDIO_EXPORT_DIR` | where `POST /v1/export?write` puts layer files |
| `MORPH_STUDIO_OVERLAY_DIR` | the Q-7 overlay directory the front also mounts |
| `MORPH_STUDIO_FRONT_TARGET` | the front's gRPC `host:port`, for `ReloadPacks` |
| `MORPH_STUDIO_PROVISIONAL` | Q-7 on/off, default on |
| `MORPH_STUDIO_VOCABULARY` | the world's model vocabulary, comma-separated (LM-10 routing) |
| `MORPH_LLM_URL` / `_MODEL` / `_API_KEY` | the classifier leg; no URL ⇒ no leg |

The schema is **alembic's**: `alembic upgrade head` runs as a job, and the
service never creates tables. A service that quietly created them on boot would
let a missing migration go unnoticed until the first environment that had an
older database.

## Development

```bash
just test-py services/morph-studio            # unit tier, SQLite, no daemon
MORPH_STUDIO_PG_URL=postgresql+psycopg://... \
  just test-py services/morph-studio -m component   # migrations against real PG
just run-morph-studio                          # uvicorn on :7290, SQLite

docker compose -f services/morph-studio/docker-compose.dev.yml up
```

## Pointers

* [LM contracts](../../../project/server/features/cz-lemma/contracts.md) — §1 records,
  §3 layer files, §7 this service, §8 the status machine, §9 diagnostics
* [LM detailed design](../../../project/server/features/cz-lemma/detailed-design.md) —
  §9 the life of *Kauflandu*, §10 editorial tooling
* `shared/libs/python/ttr-morph` — the engine, the compiler, and `enrich/`
* `services/nlp` — the front: the morph pipeline, `ReportToken`, and the
  `morph.queue` sink that fills this queue
