# ariadne

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`infra/metadata/`), tag `kantheon-fork-point`, forked 2026-06-13.
> Maintained independently since the fork; do not assume parity with the ai-platform original.

The **model-graph service** for kantheon. Loads the `ai-models` model (YAML/JSON via ClasspathStorage, Git source via `METADATA_GIT_*` for live reload) into an in-memory `ModelSnapshot`, then serves `ListObjects` / `GetObject` / `Search` / `ListQueries` / `GetModel` / `ResolveArea` / `GetStatus` / `Refresh` over gRPC (AriadneService) and HTTP. The gRPC service proto is forked as `org.tatrman.ariadne.v1` / `AriadneService` (Stage 1.2; the old `MetadataService` is gone).

**Areas (Golem P4 S4.2, 2026-06-25):** `ResolveArea(area) → packages + description + tags` resolves a subject area to its member packages, loaded from `model-ttr/areas/*.ttrm` (`AreaDef`, modeler ≥ 0.7.0). Golem assembles a Shem by resolving its `areas` → packages → `GetModel`.

**Prompt-serving REMOVED (Golem P4 S4.2, 2026-06-25; reverses the 2026-06-13 addition):** the `GetPrompts` RPC, the `prompts/` package, and the `prompts/` Git subtree are gone — **the model is just the model.** Prompts belong to the **Shem** now (mounted with the Golem pod; each Golem's `PromptStore` loads its own `prompts/{locale}/…`). The Git source is back to `model-ttr/` only. See `docs/architecture/fork/contracts.md` §1.1.

## Package root

`org.tatrman.kantheon.ariadne` (per Charon module-map idiom, `org/tatrman/<service>/`).

## Ports

- HTTP: **7260** (HOCON override `ariadne.http.port`)
- gRPC: **7261** (`ariadne.grpc.port`)
- ariadne-mcp wrapper: **7262** (separate module)

## Run

```bash
just build-kt ariadne         # compile
just test-kt ariadne          # Kotest unit + component suite
just deploy-kt ariadne        # Jib image + k8s/overlays/local (local K3s)
```

ClasspathStorage (the in-repo `model-ttr/` seed, incl. `model-ttr/areas/`) is the
default source; set `METADATA_GIT_*` to load the `ai-models` repo with live reload.
