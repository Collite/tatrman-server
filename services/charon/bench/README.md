# Charon throughput benchmark (Stage 3.2 T4)

Measures the move-pipe throughput (rows/s + MB/s) that feeds the `move.*`
capability `cost_hints` (`tools/charon-mcp/src/main/resources/manifests/tools/*.yaml`)
and Pythia's `BudgetTracker` move-cost projection.

## Two tiers (testing policy §4)

- **Move-core micro-bench (runnable now, no infra).**
  `services/charon/src/test/kotlin/org/tatrman/kantheon/charon/bench/MovePipeBench.kt`
  is a plain `main()` that pumps 1e5- and 1e6-row Arrow reference sets through the
  real `ArrowPipe` from an in-memory IPC source into a buffering target (the same
  `serializeBatchesToIpcStream` the Seaweed target uses), and prints rows/s + MB/s
  + fingerprint-compute cost. It isolates the Kotlin move-core throughput from
  network/IO — the floor every legal pair is bounded by.

  Run: `./gradlew :services:charon:bench` (a `JavaExec` task wired to the test
  runtime classpath — see `services/charon/build.gradle.kts`). It is a `main`,
  not a spec, so it is intentionally not a CI unit test (it's a measurement, not
  an assertion) and does not run under `test-all`.

- **Per-pair live bench (integration carry-over).** rows/s + MB/s for each legal
  `(source, target)` pair against the real local-K3s Seaweed / Redis / PG / MSSQL /
  Steropes pods. This is the authoritative source for `cost_hints` and lives in
  the integration-test suite (it needs the live estate). The manifest
  `typical_latency_ms` values are **seeded** until that run lands; see each
  `move.*.yaml`.

## cost_hints columns

| field | meaning |
|---|---|
| `typical_latency_ms` | median wall-clock for a representative (≈1e5-row) move on that pair |
| `is_idempotent` | `true` for evict/describe; `false` for the moves |
| `max_concurrent` | the per-pod concurrency the move pipe sustains without memory pressure |

When the live per-pair bench runs, replace the seeded `typical_latency_ms` in
each manifest with the measured median and note the run in the PR.
