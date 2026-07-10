# kyklop

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`services/dispatcher/`), tag `kantheon-fork-point`, forked 2026-06-15 (Stage 3.3).
> Maintained independently since the fork; do not assume parity with the ai-platform original.

**Kyklop** — the worker dispatcher for kantheon. It routes a validated, physical
`PlanNode` (post-Argos) to a capable worker (a Kyklops) and pipes the worker's
Arrow IPC result stream straight back upstream. Kyklop is the genus; the workers
it dispatches to are the individually-named Kyklops — **Brontes** (the MSSQL
worker), and **Steropes** (the Polars worker, lands Stage 3.4).

- **ROUTING** — walks the plan's `TableScan` qnames against the World config to
  derive a `connection_id`; a caller may also supply one explicitly. Plans with
  no scans route to the configured `default-connection`.
- **CAPABILITY MATCH** — selects among workers that advertise the resolved
  connection (and, for stateful plans, `supports_stateful_sessions = true`).
- **STICKY ROUTING** — pins a `session_id` to a worker when the worker is
  stateful; consistent-hash fallback keeps the choice stable across cold starts.
- **FAIL CLOSED** — cross-database plans (`cross_database_not_supported`) and
  unroutable plans (`no_worker_for_connection`) terminate the stream with a typed
  Rule-6 message rather than hanging.

Kyklop does **not** transform worker batches — it pipes them through unchanged,
adding only routing-decision warnings on the first batch.

## Proto

The service proto is `org.tatrman.kyklop.v1` / `KyklopService` (RPCs `Dispatch`
streaming `org.tatrman.worker.v1.ResultBatch`, `ListWorkers`, `GetStatus`;
`cz.dfpartner.dispatcher.v1` is gone). `OverallStatus` comes from
`org.tatrman.ariadne.v1`; Rule-6 `messages = 99` is the kantheon
`org.tatrman.kantheon.common.v1.ResponseMessage` stand-in.

## Workers (the Kyklops)

Worker slots are declared in `kyklop.workers` (HOCON); each needs an env-backed
endpoint (empty endpoint → skipped at boot). At Stage 3.3 the active slot is
**Brontes** (`KYKLOP_WORKER_BRONTES_ENDPOINT`, default `brontes:7296`); the
Steropes (Polars) slot is commented until Stage 3.4.

## Run

```bash
just build-kt kyklop          # compile
just test-kt kyklop           # Kotest unit + component suite
just deploy-kt kyklop         # Jib image + k8s/overlays/local (local K3s)
```

## Status (Stage 3.3 — Kyklop dispatches)

Routes a validated fixture plan to Brontes and streams Arrow IPC back, verified
by mocked unit + component tests (`KyklopBrontesDispatchComponentSpec`); real
on-K3s dispatch is on the integration-test track. Ports: HTTP 7290
(health/ready/status) · gRPC 7291 (`Dispatch`/`ListWorkers`/`GetStatus`).
Tag: `kyklop/v0.1.0`.
