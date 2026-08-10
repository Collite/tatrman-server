<!-- SPDX-License-Identifier: Apache-2.0 -->
# ttr-nlp

The Tatrman NLP suite as a library: an annotation model, a JAPE-class rule
engine, gazetteers, and the clients that talk to the `nlp` service. Apache-2.0,
published to PyPI as **`ttr-nlp`**, imported as **`ttrnlp`**.

Built once in `tatrman-server` and consumed everywhere (NL-9): the `services/nlp`
front hosts it in-process, `nlp-mcp` imports its gRPC client, and the DFP
model-validator wraps its CLI. Rule-pack and list **content** is never part of
the suite — each world maintains its own packs.

## Why it exists

An LLM can be asked what a sentence means. A deterministic pipeline has to be
*told*, and told in something a domain analyst can read, diff and review. GATE's
JAPE is the proven shape for that, but JAPE is a Java DSL inside a Java platform.
This library keeps the semantics and drops the platform: a YAML rule DSL with
JAPE's vocabulary (phases, `input:` visibility, control styles, priorities,
bindings), compiled onto [python-gatenlp]'s PAMPAC matcher.

## Module map

Full detail in the effort's `architecture.md` §2.

| Module | What it holds |
|---|---|
| `ttrnlp.doc` | Annotation model — engine JSON → gatenlp `Document`; `Document ⇄ proto` (P3) |
| `ttrnlp.rules` | The rule engine — YAML DSL → PAMPAC; the JAPE-exact executor |
| `ttrnlp.gazetteer` | List interchange + `Lookup` annotation (lemma / ci / fold-diacritics / exact) |
| `ttrnlp.packs` | Pack + list loading (fail-all) and **the** validation code path |
| `ttrnlp.client` | gRPC client for the `nlp` front; HTTP engine-adapter clients |
| `ttrnlp.cli` | `ttr-nlp validate` |

## Install

```bash
pip install ttr-nlp              # core: annotation model, rules, gazetteers, packs
pip install 'ttr-nlp[grpc]'      # + the org.tatrman.nlp.v1 client
pip install 'ttr-nlp[http]'      # + the HTTP engine-adapter clients
```

The mandatory dependency set is deliberately tiny and **model-free** —
`gatenlp`, `pydantic`, `pyyaml`, `jsonschema`. No torch, no Stanza, no spaCy, no
models. That is what lets the rule engine run in-process inside the engine-free
`nlp` front without breaking its engine-free invariant (⚑NLS-D3), and it is
enforced by a test, not just by intent.

### The gatenlp pin

`gatenlp==1.0.8` is an **exact pin** (NL-2), asserted at import time in
`ttrnlp.doc.model`. The rule compiler and the executor are written against
PAMPAC's parser and selection internals, so the pin is a contract rather than a
floor. A vendored-subset exit is pre-approved once local patches exceed two —
record any patch here before reaching for it.

**Local patches to gatenlp: none.**

## Dev

From the repo root:

```bash
just test-py shared/libs/python/ttr-nlp     # pytest
just lint-py shared/libs/python/ttr-nlp     # ruff
just build-py shared/libs/python/ttr-nlp    # uv sync --frozen
```

`just test-py ttr-nlp` (bare module name) resolves too.

## Publishing

Tag lane **`python-nlp/v<x.y.z>-RELEASE`** → `.github/workflows/publish-python.yml`
→ PyPI via Trusted Publishing. The repo tree keeps `version = "0.0.0"`; the
workflow injects the version from the tag. Bare tags (no `-RELEASE`) build but do
not publish. See the repo's `PUBLISHING.md`.

[python-gatenlp]: https://github.com/GateNLP/python-gatenlp
