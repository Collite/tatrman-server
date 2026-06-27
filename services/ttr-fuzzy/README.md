# echo

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`services/fuzzy-matcher/` + `tools/fuzzy-mcp/`), tag `kantheon-fork-point`, forked 2026-06-13 (Stage 2.2).
> Maintained independently since the fork; do not assume parity with the ai-platform original.

**Echo** — the Czech-aware fuzzy matcher for kantheon. Given a user term and an
entity type, it returns the best-matching catalog entries through an algorithm
cascade (TATRMAN / LEVENSHTEIN / JARO_WINKLER), tuned for Czech diacritics and
morphology. Themis and Golem call it (via `tools/echo-mcp`) to resolve fuzzy
entity references before query construction.

## Surface

Proto `org.tatrman.echo.v1` / `EchoService` (`cz.dfpartner.fuzzy.v1` /
`FuzzyMatcherService` are gone — renamed at fork, wire shapes unchanged):

- **Match** — fuzzy-match a term against the entity catalog; the algorithm is a
  request arg (`TATRMAN | LEVENSHTEIN | JARO_WINKLER`). Returns scored matches
  (`FuzzyMatchResponse`). The proto carries no Rule-6 add — the fork principle is
  "wire shapes fork unchanged"; the only additive exception is the Ariadne hop.

`tools/echo-mcp` wraps this as the single `match` tool (capability id
`echo.match:v1`).

## Loader sources

The entity catalog populates from one of two loader sources (opt-in via config):

- **Static** (default; local/CI) — reads an in-repo JSON catalog. No external
  dependency; the lean carve-out the fork landed on.
- **DB-backed** (re-added 2026-06-14) — reproduces the ai-platform `fuzzy-matcher`
  behaviour in full: asks **Ariadne** for fuzzy-tagged columns, composes
  `SELECT pk, col FROM table`, queries the warehouse, populates the catalog.

## Run

```bash
just build-kt echo            # compile
just test-kt echo            # Kotest unit + component suite (44 tests)
just deploy-kt echo          # Jib image + k8s/overlays/local (local K3s)
```

## Ports

- HTTP **7265** (health / ready / `/match` REST) · gRPC **7266** (`EchoService.Match`)
- `tools/echo-mcp` wrapper: **7267**

Tag: `echo/v0.1.0`.
