# Changelog

All notable changes to what this repo **publishes** are recorded here: the
`ttr-nlp` PyPI wheel, the `org.tatrman:*` Kotlin artifacts cut from
`shared/libs/kotlin`, and the wire contracts in `shared/proto` that deployed
consumers speak. Internal refactoring is out of scope; a change that a consumer
outside this repo could notice is in.

> **This file starts at NLS-P4** (2026-08-11). Everything before it is in the git
> history and in `project/`'s per-effort trackers, which stay the place where the
> *reasoning* lives — a changelog answers "what changed for me", not "why". Format
> follows `tatrman`'s, deliberately: two repos in one ecosystem with two changelog
> conventions is a small tax paid on every release.
>
> While versions are `< 1.0.0`, minor bumps may contain breaking changes.

## Unreleased

### `ttr-nlp` 0.1.0 — the first wheel (NLS-P0…P4)

The NLP suite as a library: an annotation model, a JAPE-class rule engine,
gazetteers, the pack loader and validator, and the clients. Apache-2.0, imported
as `ttrnlp`.

**In this release**

- **Annotation model** — engine JSON → a pinned gatenlp `Document`
  (`gatenlp==1.0.8`, an exact pin: the compiler targets PAMPAC internals). One
  annotation per (type, span); duplicates are dropped and counted rather than
  stacked.
- **The rule DSL** — YAML with JAPE's vocabulary (phases, `input:` visibility,
  the five control styles, priorities, bindings), a JSON Schema, cross-checks,
  and a compiler onto PAMPAC. The right-hand side is `add` / `update` over a
  closed set of getters: **no code in a pack, ever**.
- **The JAPE-exact executor** — `appelt` tie-broken longest → priority → file
  order, plus `brill` / `all` / `first` / `once`. 35 conformance cases.
- **Gazetteers** — one YAML file per list, four matching modes (`lemma`, `ci`,
  `fold-diacritics`, `exact`), multi-token terms, longest-match, provenance on
  every `Lookup`. **No scoring** (NL-17) — approximate matching stays world-side.
- **Pack loading** — fail-all-or-nothing over directories, files and `http(s)`
  URLs; an immutable snapshot with a content-keyed `state_id`.
- **`ttr-nlp validate`** — the CLI, running the same code path as the service's
  boot load and `ReloadPacks`. `--model` adds the query/parameter cross-check.
- **`Document ⇄ proto`** — the `org.tatrman.nlp.v1` annotation surface, with the
  output filters.
- **`NlpClient`** — an async gRPC client whose `run_pipeline` returns a
  `Document`, not a wire message.
- **`ttrnlp.client.backends`** — the HTTP engine-adapter clients (moved here from
  `services/nlp` at NLS-P3.3).

**Not in this release**

- Czech morphology (`ttrnlp.morph`) — NLS-P7…P9.
- Anything trained on the CAC corpus — post-v1 by ruling (⚑GXP-D5).
- Rule-pack and list **content**: never part of the suite (NL-17). Each world
  maintains its own.

**Extras.** `[grpc]` for the client and the serializer, `[http]` for the backend
adapters. The core install is deliberately model-free — `gatenlp`, `pydantic`,
`pyyaml`, `jsonschema` and nothing else — which is what lets the rule engine run
in-process inside the engine-free `nlp` front.

### `org.tatrman.nlp.v1` — the pipeline surface (NLS-P3.1)

**Additive only**: 155 insertions, no existing field number, name or type
touched. `services/nlp/tests/test_contract_shapes.py` freezes every message's
fields as literals, so a renumbering has to be written down to pass.

- **New rpcs** — `RunPipeline` (run a named pipeline, get an annotated document),
  `ReloadPacks` (re-read the configured pack sources; sources never come from the
  request), `ReportToken` (the LM queue sink's front door — answers
  `accepted=false` until NLS-P9 wires it).
- **New messages** — `FeatureValue` / `FeatureValueList` / `Annotation` /
  `AnnotationSet` / `AnnotatedDocument`; `RunPipelineRequest` / `Response` /
  `PhaseTrace`; `ReloadPacksRequest` / `Response` / `PackDiagnostic`;
  `PipelineInfo` / `PackState`; `ReportTokenRequest` / `Response`.
- **`StatusResponse` gains fields 3–5** — `lane`, `pipelines`, `pack_state`.
  Fields 1–2 are untouched.

`Analyze`, `BatchLemmatize` and `GetStatus` request/response shapes are
byte-untouched. Themis, Echo and kantheon need no change.

### `services/nlp` — lanes and the pipeline surface (NLS-P3.2)

Deployment-visible, so it is here even though the image is not a published
artifact.

- **`lane: default | option` (NL-4).** `default` is Stanza + spaCy only — anyone
  may run it. `option` adds the UFAL stack, whose licence is a per-deployment
  decision (NL-5). Set it with `NLP_LANE` or the chart's `lane` value.
- **⚠ `config.yaml`'s `op_routing` is now the DEFAULT-lane table.** The Czech
  MorphoDiTa/NameTag routing moved verbatim into `lane_overrides.option`. **A
  deployment that wants today's behaviour must set `lane: option`.** Leaving it
  at `default` is a working service with no Czech NER: cs `NER` gets no
  `GetStatus` capability row, responses carry `NLS-NLP-011`, and every other
  phase still runs (NL-14).
- **New config sections** — `packs.sources`, `lists.sources`, `pipelines`. All
  optional; a front with no packs serves `Analyze` exactly as before.
- **New chart values** — `lane`, `packs.configMapName` / `mountPath`,
  `lists.configMapName` / `mountPath`. The mounts are off unless a configmap is
  named, so a chart that has never heard of a rule pack renders byte-identically.
- **New diagnostics** — `NLS-NLP-011` (op unrouted in the active lane),
  `NLS-PACK-010` (reload refused), `LM-MORPH-007` (token report not sunk).
