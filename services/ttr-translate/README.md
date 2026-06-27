# proteus

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`services/translator/`), tag `kantheon-fork-point`, forked 2026-06-14.
> Maintained independently since the fork; do not assume parity with the ai-platform original.

The **translator** for kantheon — a thin gRPC service wrapping the
`:shared:libs:kotlin:query-translator` Calcite/RelNode engine. Converts between
the supported `Language`s (SQL, Transformation DSL, DataFrame DSL, RelNode) and
SQL dialects, the two-halves way the pipeline uses it:

- **`ParseToRelNode`** — front of pipeline: source language → canonical RelNode in the target schema.
- **`UnparseFromRelNode`** — back of pipeline: validated RelNode → dialect SQL (default MSSQL).
- **`Translate`** — one-shot any-to-any (frontends, inspection).
- **`Explain`** — debug variant returning per-stage artefacts.
- **`DetectSourceSchema`** — infer the effective source schema.

The gRPC service proto is forked as `org.tatrman.proteus.v1` / `ProteusService`
(RPC names kept; `cz.dfpartner.translator.v1` is gone). The `Language` /
`SqlDialect` enums live in the library-only `translator.proto` (same package,
Stage 1.3) and are imported by the service proto. Model data comes from
**Ariadne** (formerly the metadata service): Proteus polls `GetSnapshot` and
calls `GetQuery`, via `org.tatrman.ariadne.v1.AriadneServiceGrpcKt`.

Kotlin source root: `org.tatrman.kantheon.proteus`. Ports: **7275** (HTTP) /
**7276** (gRPC). No MCP wrapper — Proteus is an internal pipeline service called
by Theseus, not an agent-facing tool.

## Local / test

```bash
just test-kt proteus          # Calcite golden round-trip suite
./gradlew :services:proteus:ktlintCheck
just deploy-kt proteus        # Jib image + k8s/overlays/local
```

`proteus.use-fixture-model = true` (env `PROTEUS_USE_FIXTURE_MODEL`) boots
against the in-process boot fixture when no Ariadne is reachable (local/test).
