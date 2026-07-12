# ttr-nlp

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`infra/nlp/`), tag `kantheon-fork-point`, forked 2026-06-14 (service formerly named Kadmos).
> Maintained independently since the fork; do not assume parity with the ai-platform original.

The **NLP foundation service** for kantheon — multi-engine analysis (Stanza, spaCy, NameTag/MorphoDiTa via UFAL, langid) over Python/FastAPI.

## Overview

`services/ttr-nlp` provides NLP operations (tokenize, lemmatize, POS, dependency
parse, NER, language detection) with per-op-per-language routing (Czech through
the UFAL stack). It is the NLP foundation consumed by Themis (via
`tools/ttr-nlp-mcp`) and Echo.

**RG-P1.S1 (Resolution & Grounding, workstream C):**

- **gRPC is the service contract** — `org.tatrman.nlp.v1.NlpService`
  (`Analyze` / `BatchLemmatize` / `GetStatus`) on port **7271**. The FastAPI
  REST endpoint (port **7270**) is a **dev/health mirror only**.
- **The front is engine-free** — no in-process torch/models. Every model-bearing
  engine (MorphoDiTa, NameTag 3, Stanza, spaCy) is an **HTTP-adapter client** to
  its own backend image; only `langid` (lingua) runs in-front. Backends land in
  S2 (MorphoDiTa + NameTag 3) and S3 (Stanza + spaCy).
- **S-1 (model identity on the wire)** — every backend is launched with an
  **explicit model id**; every response echoes `used[]` (engine + model +
  version), never blank. `GetStatus` returns the capability matrix with each
  routed (language, op)'s pinning `tier` (`SELF_HOSTED_PINNED` /
  `REMOTE_UNPINNED`). Diagnostics: `RG-NLP-002` (Lindat/unpinned), `RG-NLP-003`
  (empty model), `RG-NLP-010` (degrade floor).

> **Proto stubs.** ttr-nlp owns its generated `org.tatrman.{nlp,common}.v1`
> Python stubs under `generated/` (gitignored), produced from the shared
> `.proto` source by `scripts/gen_proto.py` (a pytest conftest regenerates them
> on demand). The `.proto` file remains the single canonical source; the Kotlin
> consumer's gRPC stubs come from `:shared:proto`.

## Supported Operations

| Operation | Description | Engines |
|-----------|-------------|---------|
| `TOKENIZE` | Tokenization | Stanza, spaCy |
| `SENTENCE_SPLIT` | Sentence boundary detection | Stanza |
| `LEMMATIZE` | Lemmatization | Stanza |
| `POS_TAG` | Part-of-speech tagging (UD + language-specific) | Stanza |
| `DEP_PARSE` | Dependency parsing | Stanza |
| `NER` | Named Entity Recognition | Stanza, spaCy, NameTag |
| `DETECT_LANGUAGE` | Language detection | langid (lingua) |

## Engines

### Stanza
- **Languages**: Czech (cs), English (en)
- **Operations**: All except NER (cs/en), with full POS and dependency parsing
- **Models**: Bundled in Docker image (pre-downloaded at build time)

### spaCy
- **Languages**: English (en)
- **Operations**: Tokenization, NER
- **Models**: `en_core_web_md` bundled in Docker image

### NameTag (UFAL)
- **Languages**: Czech (cs), English (en)
- **Operations**: NER only
- **Endpoint**: `https://lindat.mff.cuni.cz/services/nametag`
- **Rate Limit**: 5 req/min (configurable)

### langid (lingua-language-detector)
- **Languages**: Multiple (cs, en, de, sk, pl, hu, sl, hr, sr, mk, bg)
- **Operations**: DETECT_LANGUAGE only

## Configuration

Configuration is managed via `config.yaml`:

```yaml
service:
  host: "0.0.0.0"
  port: 7270

engines:
  stanza:
    enabled: true
    model_dir: "/opt/nlp-models/stanza"
  spacy:
    enabled: true
    model_name: "en_core_web_md"
  nametag:
    enabled: true
    # `/services/nametag` 301-redirects; the REST API is at `/api/recognize`.
    endpoint: "https://lindat.mff.cuni.cz/services/nametag/api/recognize"
    timeout_seconds: 30
    max_retries: 3
    rate_limit_per_minute: 5
  morphodita:
    enabled: true
    endpoint: "https://lindat.mff.cuni.cz/services/morphodita/api/tag"
    timeout_seconds: 30
    max_retries: 3
    rate_limit_per_minute: 5
  langid:
    enabled: true

# Per-operation routing: {op}.{lang} -> engine_name
op_routing:
  # Czech (cs) — UFAL stack; Stanza's cs model has no NER head, so we route
  # tokenize/sentence_split/lemmatize/POS through morphodita and NER through nametag.
  TOKENIZE.cs: "morphodita"
  SENTENCE_SPLIT.cs: "morphodita"
  LEMMATIZE.cs: "morphodita"
  POS_TAG.cs: "morphodita"
  DEP_PARSE.cs: "stanza"
  NER.cs: "nametag"
  NER.cs.fallback: ""        # No fallback — stanza-cs has no NER model.

  TOKENIZE.en: "stanza"
  LEMMATIZE.en: "stanza"
  POS_TAG.en: "stanza"
  DEP_PARSE.en: "stanza"
  NER.en: "stanza"           # Stanza for English NER
  NER.en.fallback: "spacy"   # spaCy fallback

  DETECT_LANGUAGE: "langid"

default_language: "cs"
```

## API

### POST /v1/analyze

Run NLP analysis on input text.

**Request:**
```json
{
  "text": "Které faktury Shell ještě neuhradil?",
  "language": "cs",
  "ops": ["TOKENIZE", "LEMMATIZE", "POS_TAG", "DEP_PARSE", "NER"],
  "mode": "NORMAL",
  "engineHints": {}
}
```

**Response:**
```json
{
  "language": "cs",
  "languageConfidence": 1.0,
  "engineUsed": "stanza",
  "tokens": [
    {
      "text": "Které",
      "charStart": 0,
      "charEnd": 5,
      "lemma": "který",
      "upos": "DET",
      "xpos": "派4",
      "feats": {"Number": "Plur", "Case": "Nom"},
      "depHead": 4,
      "depRelation": "det"
    },
    ...
  ],
  "sentences": [{"charStart": 0, "charEnd": 33}],
  "paragraphs": [],
  "entities": [
    {
      "text": "Shell",
      "label": "PER",
      "charStart": 13,
      "charEnd": 18,
      "normalizedValue": "",
      "sourceEngine": "nametag"
    }
  ],
  "byEngine": {},
  "traceId": "abc123...",
  "elapsedMs": 150,
  "messages": []
}
```

### GET /healthz

Health check endpoint.

### GET /readyz

Readiness check - returns 503 if no engines are available.

### GET /version

Service version and engine information.

## Local Development

```bash
# Install dependencies
cd services/ttr-nlp
uv sync

# Run service (default port 7270)
uv run python src/main.py

# Or with uvicorn directly
uvicorn nlp_service.api.routes:app --reload --port 7270
```

## Testing

```bash
# Run tests (from repo root; regenerates proto-py first)
just test-py services/ttr-nlp

# Lint (ruff)
just lint-py services/ttr-nlp
```

## Evaluation

The NLP eval corpus (`eval/corpus/seed.jsonl`, 50 hand-curated Czech questions
with expected parses + entity bindings) and harness (`eval/run_eval.py`) are
carried over **verbatim** from the ai-platform original — same code, same corpus.
Run it against a deployed service:

```bash
just eval-nlp                    # port-forwards the nlp pod (7270) + runs the harness
# or against a local instance:
uv run python eval/run_eval.py --url http://localhost:7270
```

**Baseline:** because the engine code and corpus are byte-identical to the
ai-platform original at the fork point, ttr-nlp answers identically by
construction — the Stage 2.6 Themis gate builds on this. A numeric baseline is
recorded at the first live deployment (the ai-platform original shipped no
recorded `eval/reports` metrics, and the harness needs a running service +
remote UFAL endpoints to score; live infra was unavailable in the fork session,
deferred to the deployment pipeline — Veles/Echo precedent). Reports land in
`eval/reports/` (`metrics.json` + `report.md`).

## Container & deployment

The front and each engine backend are **separate images** (RG-P1.S2/S3):

| Image | Dockerfile | Contents |
|---|---|---|
| `ttr-nlp` (front) | `Dockerfile` | engine-free front (gRPC + REST mirror); no models needed at run time |
| `ttr-nlp-morphodita` | `backends/morphodita/Dockerfile` | `morphodita_server` + baked `czech-morfflex2.0-pdtc1.0-220710` (S2) |
| `ttr-nlp-nametag3` | `backends/nametag3/Dockerfile` | `nametag3_server.py` + baked `nametag3-czech-cnec2.0-240830` + RobeCzech PLM (S2) |
| `ttr-nlp-stanza` | `backends/stanza/Dockerfile` | uniform-JSON `server.py` + baked Stanza cs+en (cs DEP_PARSE hot path) (S3) |
| `ttr-nlp-spacy` | `backends/spacy/Dockerfile` | uniform-JSON `server.py` + baked spaCy `en_core_web_md` (en NER fallback) (S3) |

Backend images target the **x86 cluster** (`docker buildx build --platform
linux/amd64 …`); UFAL models are **CC BY-NC-SA** (FI-4 — building/running is
fine, **publishing** the images is the gated legal item). The pinned model
download URLs (LINDAT) resolve; set the `MODEL_SHA256` build-arg to the verified
digest on the first build (digest-pin). Local **offline** bring-up (front + both
backends, no Lindat egress):

```bash
docker compose -f services/ttr-nlp/docker-compose.offline.yml up   # see the file header for build + hero-parse recipes
```

### Image-size strategy (cached model layer)

Each backend isolates its baked model in a dedicated stage so app/config edits
never invalidate the heavy model layer (the Metis/prophet "cached base layer"
pattern). Sizing (Q-10 §3/§5): MorphoDiTa ~250 MB / sub-10 ms / scales with
concurrency; NameTag 3 ~1.1 GB / ~72 ms p50 / ~12 rps per replica → **scale by
replicas**, ~5 s cold start. The front is **engine-free** — no torch, no models,
no per-engine native deps (a test asserts the import- and install-level
guarantee); only langid runs in it.

The **front** image (engine-free) has no model stage:

| Stage | Holds | Cache-busts on |
|-------|-------|----------------|
| `base` | python:3.13-slim + build-essential/curl | base image bump |
| `deps` | uv venv (contract + routing + langid; NO torch) from `pyproject.toml`+`uv.lock` | dependency change |
| `protogen` | generated `org.tatrman.{nlp,common}.v1` stubs (grpcio-tools) | `.proto` change |
| `runtime` | venv + generated stubs + **app `src/`** (thin) | app-source change |

The front image is small (no torch/models); the model weight now lives in the
per-engine backend images (each with its own cached model stage).

## Architecture

```
                    ┌─────────────────────────────────────────────┐
                    │             services/ttr-nlp                 │
                    │                                              │
Request ──────────► │ ┌─────────┐    ┌────────────────────────┐  │
                    │ │ FastAPI │───►│     Orchestrator        │  │
                    │ └─────────┘    │  (per-op engine routing) │  │
                    │                └───────────┬────────────┘  │
                    │                            │                 │
                    │       ┌────────────────────┼────────────────┐ │
                    │       │                    │                │ │
                    │       ▼                    ▼                ▼ │
                    │  ┌─────────┐         ┌─────────┐     ┌─────────┐ │
                    │  │  Stanza │         │  spaCy  │     │ NameTag │ │
                    │  └─────────┘         └─────────┘     └─────────┘ │
                    │                                              │
                    │       ┌─────────┐                           │
                    │       │ langid  │ (for DETECT_LANGUAGE)       │
                    │       └─────────┘                           │
                    └─────────────────────────────────────────────┘
```

## Engine Plugin Contract

To add a new engine, implement `NlpEngine` protocol:

```python
from nlp_service.engines.base import NlpEngine, NlpOp, EngineResult

class MyEngine(NlpEngine):
    @property
    def name(self) -> str:
        return "my_engine"

    def supported_languages(self) -> Set[str]:
        return {"cs", "en"}

    def supports(self, lang: str, op: NlpOp) -> bool:
        return lang in self.supported_languages() and op in {NlpOp.TOKENIZE, NlpOp.NER}

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        # Your implementation
        return EngineResult(...)
```

Then register it in `EngineRegistry.__init__`.