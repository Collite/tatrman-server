# Charon — Arrow data mover

> **Arrival note — CH-P1 (2026-07-26).** This is the **one open Charon** (arc CH,
> Apache-2.0). History-preserving `git filter-repo` from `tatrman-platform` master
> `395ec99` (`kotlin/services/charon` → `services/charon`), carrying the full
> kantheon→platform→server chain. Re-homed onto the server: Kotlin root
> `org.tatrman.charon.*`; `transfer.v1` + `metis.v1` → `:shared:proto` (published in
> `ttr-server-proto`), `common.v1` reuses the server's `org.tatrman.common.v1`;
> secrets via the open `ConnectionSecretResolver` port (the `cz.tatrman:secrets-spi`
> wiring is the platform's CH-P2 adapter, not here); `installKtorServerBase` bootstrap;
> image via Jib (`charon`, CH-D7). Platform + kantheon become consumers by published
> version (CH-P2/CH-P3). Suite: 21 specs / 248 tests. **Consumed core (`ttr-transfer-core`,
> CH-D5) publishes when radegast switches to it at CH-P2.**

> **Status:** **Arc complete (`charon/v0.3.0`).** Seaweed + Redis object-store movers (P1) + DB edges (P2: connection registry, JDBC extract/ingest for PG + MSSQL, allow-lists, `Describe(db_table)`) + **worker edges (P3)**: a `WorkerEndpoint` over a `WorkerGateway` SPI — **both METIS and POLARS** have the full stage-in / scan-out / describe / evict surface; the `tools/charon-mcp` wrapper + `move.*` capability registration + the throughput bench. Pythia Phase 4 unblocked.
>
> **Worker stage-in (both engines, 2026-06-26).** METIS uses `metis.v1` `ImportDataFrame`/`ExportDataFrame`/`DropWorkspaceEntry`. **POLARS uses the `worker.v1` `ImportDataFrame`/`DropWorkspaceEntry` RPCs added to Steropes at the Stage 3.1 closeout** (mirroring Metis §1.3) — `Stage(X → worker_df{POLARS})` and `Evict(worker_df{POLARS})` are live, closing the earlier upstream gap. POLARS read-out is `Execute` over a `WorkspaceRef`.
>
> ## ADBC spike verdict (Stage 2.1 T3, decided 2026-06-13)
>
> **v1 uses plain JDBC behind the `AdbcReader`/`AdbcWriter` interfaces** (`endpoints/JdbcAdbcReader.kt`, `endpoints/JdbcAdbcWriter.kt`), with a **hand-rolled JDBC↔Arrow mapping over the explicit `contracts §5` type matrix** (`core/DbTypeMapping.kt`). The candidates considered:
> - **ADBC driver-manager per dialect** — rejected for v1: the ADBC JVM driver-manager is immature for MSSQL, and bundling the native ADBC drivers is a packaging burden disproportionate to the table-level extract/ingest v1 needs.
> - **`arrow-jdbc` auto-mapping per dialect** — rejected: `JdbcToArrow`'s default type coercions don't hit the `§5` matrix without per-column config overrides, so we'd be hand-specifying the mapping anyway — with less control over named-column errors and cross-engine fingerprint determinism.
>
> Both PG and MSSQL go through the **same** impl; the dialect only changes DDL/quoting. The interface seam means swapping in an ADBC-per-dialect impl later is a single class — the executor and pipe are unchanged. Unit tests drive the impls with **H2 (PostgreSQL mode)** as the in-JVM stand-in driver; real PG/MSSQL dialect fidelity is the separate integration suite (testing policy §4). The connection registry (`core/ConnectionRegistry.kt`) loads `/etc/charon/connections.yaml`, env-substitutes sealed-secret credentials, enforces read/write/schema allow-lists, and is lazily-validated (a broken DB connection degrades that connection, never the pod) with a `POST /refresh` reload.
>
> **forked-from:** none — written in kantheon from the start. The first `services/` platform-grade module; its conventions are the template Midas's `report-renderer` follows.
>
> **First-instance conventions (the `services/` template):**
> - Kotlin source root: `org.tatrman.kantheon.charon.*` (decision 2026-06-13, fork Stage 2.1 T2; matches the Ariadne/Echo convention; migrated-service protos stay `org.tatrman.charon.v1`).
> - Proto root: `org.tatrman.charon.v1` (migrated-service convention; `AGENTS.md` §4 / `architecture.md` §2).
> - Transport: gRPC (`:7251`) for service-to-service + Ktor HTTP (`:7250`) for `/health` + `/ready` + (P1 Stage 1.3) `/refresh`.
> - Jib image: `charon:dev`; K3s manifests under `k8s/{base,overlays/local}/`; `imagePullPolicy: Never` in the local overlay.
> - Lint gate: `./gradlew :services:charon:ktlintCheck` (the umbrella `just lint-all` covers it).
> - Test gate: `./gradlew :services:charon:test` (the umbrella `just test-all` covers it).
>
> Up: [`../../plan.md`](../../plan.md) (3 phases × 8 stages) and [`../../../architecture/charon/architecture.md`](../../../architecture/charon/architecture.md) / [`contracts.md`](../../../architecture/charon/contracts.md). Source-of-truth for tags: `charon/v0.1.0` lands at Stage 1.3 close; this stage is the skeleton only.

## Module layout (skeleton at Stage 1.1)

```
services/charon/
├── build.gradle.kts
├── README.md
├── k8s/
│   ├── base/
│   │   ├── deployment.yaml
│   │   └── kustomization.yaml
│   └── overlays/local/
│       └── kustomization.yaml
└── src/
    ├── main/
    │   ├── kotlin/org/tatrman/kantheon/charon/
    │   │   ├── Application.kt          # main(); ≤45 lines per EXAMPLES.md §1b
    │   │   ├── core/
    │   │   │   ├── Legality.kt         # the matrix — contracts §2 source-of-truth
    │   │   │   ├── MovePlanner.kt      # validation + plan assembly
    │   │   │   ├── Errors.kt           # sealed CharonError + gRPC status mapping
    │   │   │   └── MoveExecutor.kt     # I/O seam — `UNIMPLEMENTED` at Stage 1.1
    │   │   └── grpc/
    │   │       └── CharonServiceImpl.kt
    │   └── resources/
    │       ├── application.conf
    │       └── logback.xml
    └── test/
        └── kotlin/org/tatrman/kantheon/charon/
            ├── core/
            │   ├── MovePlannerSpec.kt
            │   └── ErrorsSpec.kt
            └── grpc/
                └── RequestValidationSpec.kt
```

Endpoints (`endpoints/SeaweedEndpoint.kt`, `RedisEndpoint.kt`, `WorkerEndpoint.kt`, `db/ConnectionRegistry.kt`, `db/AdbcReader.kt`, `db/AdbcWriter.kt`) arrive in Stages 1.2/1.3/2.1/3.1 per the plan.

## Build & run

```bash
just proto                                # regenerate org.tatrman.charon.v1
just build-kt charon                      # Jib image `charon:dev`
just test-kt charon                       # Kotest suite
just deploy-kt charon                     # K3s apply (local overlay)
```
