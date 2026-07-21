# ttr-fuzzy

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`services/fuzzy-matcher/` + `tools/fuzzy-mcp/`), tag `kantheon-fork-point`, forked 2026-06-13 (Stage 2.2).
> Maintained independently since the fork; do not assume parity with the ai-platform original.

**ttr-fuzzy** — the Czech-aware fuzzy matcher for kantheon. Given a user term and an
entity type, it returns the best-matching catalog entries through an algorithm
cascade (TATRMAN / LEVENSHTEIN / JARO_WINKLER), tuned for Czech diacritics and
morphology. Themis and Golem call it (via `tools/ttr-fuzzy-mcp`) to resolve fuzzy
entity references before query construction.

## Surface

Proto `org.tatrman.fuzzy.v1` / `FuzzyService` (`com.tatrman.fuzzy.v1` /
`FuzzyMatcherService` are gone — renamed at fork, wire shapes unchanged):

- **Match** — fuzzy-match a term against the entity catalog; the algorithm is a
  request arg (`TATRMAN | LEVENSHTEIN | JARO_WINKLER`). Returns scored matches
  (`FuzzyMatchResponse`). The proto carries no Rule-6 add — the fork principle is
  "wire shapes fork unchanged"; the only additive exception is the Veles hop.

`tools/ttr-fuzzy-mcp` wraps this as the single `match` tool (capability id
`fuzzy.match:v1`).

## Loader sources

The entity catalog populates from one of two loader sources (opt-in via config):

- **Static** (default; local/CI) — reads an in-repo JSON catalog. No external
  dependency; the lean carve-out the fork landed on.
- **DB-backed** (re-added 2026-06-14) — reproduces the ai-platform `fuzzy-matcher`
  behaviour in full: asks **Veles** for fuzzy-tagged columns, composes
  `SELECT pk, col FROM table`, queries the warehouse, populates the catalog.

## Retrieval modes (TATRMAN path)

The TATRMAN matcher supports two retrieval strategies behind
`fuzzy.token-based.retrieval` (env `FUZZY_TOKEN_BASED_RETRIEVAL`). Both return the
**same scores** — scoring, the cascade min-score gates, and the `fuzzy.match:v1`
contract are untouched:

- **`index-first`** (default) — resolves each query token once against the interned
  token vocabulary (edit-distance ≤ 2 over length buckets), sweeps postings to pick
  the best candidates term-at-a-time, then **exact-rescores** the top few hundred with
  the unchanged scorer. Cost scales with postings length, not corpus size: ~7–40×
  faster than legacy at product-name scale, and parity-or-better (it reaches candidates
  with no exact-token overlap that legacy misses). This is also the `CandidateRetriever`
  seam an OpenSearch backend can plug into.
- **`legacy`** — the escape hatch: score every candidate that shares an exact token with
  the query. Byte-identical to the pre-FZ engine. Select with
  `FUZZY_TOKEN_BASED_RETRIEVAL=legacy`.

| Config key | Env | Values | Default |
|---|---|---|---|
| `fuzzy.token-based.retrieval` | `FUZZY_TOKEN_BASED_RETRIEVAL` | `index-first` \| `legacy` | `index-first` |

## Run

```bash
just build-kt fuzzy          # compile
just test-kt fuzzy           # Kotest unit + component suite (44 tests)
just deploy-kt fuzzy         # Jib image + k8s/overlays/local (local K3s)
```

## Ports

- HTTP **7265** (health / ready / `/match` REST) · gRPC **7266** (`FuzzyService.Match`)
- `tools/ttr-fuzzy-mcp` wrapper: **7267**

Tag: `ttr-fuzzy/v0.1.0`.
