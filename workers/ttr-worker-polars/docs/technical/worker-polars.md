# workers/polars

Phase 2.4 — second Worker engine for the v1 platform. Python + Polars
+ gRPC. Stateful: holds an in-memory session-scoped workspace store so
agents can build derivations step by step.

## Layout

* `src/workers_polars/main.py` — boot orchestration (gRPC + HTTP probes).
* `src/workers_polars/grpc_service.py` — `WorkerService` class. Handlers
  registered via `grpc.method_handlers_generic_handler` so the build
  doesn't depend on auto-generated `*_pb2_grpc.py` stubs.
* `src/workers_polars/converter.py` — `PlanToPolars` walks
  `org.tatrman.plan.v1.PlanNode` → `pl.LazyFrame`. `convert_expr` walks
  the v1 standardised expression operators.
* `src/workers_polars/workspace.py` — `WorkspaceStore` with TTL eviction,
  hard caps, asyncio locks, background sweeper.
* `src/workers_polars/probes.py` — FastAPI sidecar with /health /ready
  /status /metrics; Prometheus singletons.
* `src/workers_polars/config.py` — typed dataclasses; HOCON loader via
  `pyhocon`.
* `conf/application.conf` — HOCON config (matches platform-service
  convention from the Kotlin services).

## Surface

| Endpoint            | Type     | Notes                                         |
|---------------------|----------|-----------------------------------------------|
| `WorkerService.Execute` | gRPC server-stream | Returns Arrow IPC `ResultBatch` chunks. |
| `WorkerService.GetCapabilities` | gRPC unary | `engine_name=polars`, `supports_stateful_sessions=true`. |
| `WorkerService.GetStatus`       | gRPC unary | `active_sessions` from workspace store.      |
| `GET /health`       | HTTP     | Always 200 if process is up.                 |
| `GET /ready`        | HTTP     | 200 once gRPC server is bound.               |
| `GET /status`       | HTTP     | JSON: engine, capability, workspace stats.   |
| `GET /metrics`      | HTTP     | Prometheus exposition.                        |

Default ports: gRPC 7301, HTTP 7300.

## Plan-to-Polars conversion

| RelOp         | Polars                                                              |
|---------------|---------------------------------------------------------------------|
| `workspace_ref` | `entry.df.lazy()` for `(session_id, workspace_name)` lookup       |
| `table_scan`  | rejected — `unsupported_table_scan` (Dispatcher routing prevents)   |
| `project`     | `lf.select([convert_expr(e).alias(named.alias)] for ...)`           |
| `filter`      | `lf.filter(convert_expr(condition))`                                |
| `join`        | equi-join on column refs (`eq` or AND-of-eqs); cross when no cond   |
| `aggregate`   | `lf.group_by(group_keys).agg(aggregates)`                           |
| `sort`        | `lf.sort` with `descending` + `nulls_last` per key                  |
| `limit_offset`| `lf.slice(offset, limit)`                                           |
| `values`      | `pl.LazyFrame` from cell-per-column reshaping                       |
| `subquery`    | recurse                                                              |

## Expression operators

* Literals (string / int / float / bool / datetime / null), column refs,
  parameter refs (bound from `PipelineContext.parameters`), cast
  (target_type → Polars dtype).
* Arithmetic: add / subtract / multiply / divide / modulo / negate.
* Comparison: eq / ne / lt / le / gt / ge / between.
* `in` extracts literal values from operands (Polars `is_in` expects values).
* Logical: and / or / not.
* Null: is_null / is_not_null / coalesce.
* String: concat / lower / upper / like (LIKE → regex translation).

Unsupported operators raise `UnsupportedExpression` → `unsupported_expression`
error code on the response.

## Workspace lifecycle

The Polars Worker is the first Worker that **holds state across calls**.
A workspace is a Polars DataFrame keyed by
`(session_id, workspace_name)`. Lifecycle:

1. **Created** by an Execute call carrying `assign_to_workspace="<name>"`.
   The result of converting the plan is stored under that name.
2. **Read** by subsequent Execute calls in the same session whose plan
   contains a `WorkspaceRef` leaf with that name.
3. **Evicted** by:
   * Per-entry TTL (default 60 min idle).
   * Per-session / pod-wide cap breach (rejects the *new* `put`, leaves
     existing entries untouched).
   * Pod loss (no persistence — workspaces die with the pod).

The Dispatcher's sticky-routing path (Phase 1.7) keeps a session pinned
to one Polars pod, so subsequent calls reach the same workspace store.

## Error codes

All errors travel through the structured-message channel
(`ResultBatch.messages` with `severity = ERROR`).

| Code                           | Meaning                                                              |
|--------------------------------|----------------------------------------------------------------------|
| `workspace_requires_session`   | Plan has `workspace_ref` but request had empty `session_id`          |
| `workspace_not_found`          | `workspace_ref.workspace_name` not in the store for this session     |
| `workspace_cap_exceeded`       | `assign_to_workspace` would breach max-bytes / max-dfs / max-sessions |
| `workspace_put_failed`         | Generic put failure (other than cap)                                  |
| `unsupported_table_scan`       | TableScan reached the Polars Worker (defensive)                       |
| `unsupported_expression`       | Expression operator outside the v1 standardised set                   |
| `unsupported_node`             | PlanNode oneof case the converter doesn't implement                   |
| `polars_execution_failed`      | Polars-thrown exception during `lf.collect()`                         |

## Out of scope for v1

* Distributed Polars execution. Workspace is in-memory in one pod.
* Persistent workspace storage. Workspaces die with the pod (TTL handles
  the dominant case; pod loss is acceptable per the No-Op-stubs principle).
* Cross-engine workspace handoff (e.g. MS SQL → Polars). Plans rooted at
  `db.*` `TableScan` route to MS SQL; plans rooted at `WorkspaceRef`
  route to Polars. Phase 2.5+ may add a workspace-exchange protocol.
* Polars accessing the ERP database directly — no JDBC/ODBC bridge.
* Worker writing `display_label` / `value_labels` into Arrow schema field
  metadata. Phase 2.4 confirmation: workers stay dumb. `ttr-query-mcp`'s
  side-channel (Phase 2.2) decorates output for both engines uniformly.

## Operations notes

* **Dispatcher registration** is HOCON-only — add the Polars endpoint
  to `services/dispatcher/`'s `dispatcher.workers` list in deployment
  overlays. The dispatcher's existing capability poller picks up
  `engine_name=polars` + `supports_stateful_sessions=true` and adds it
  to its registry. No code change in `services/dispatcher/`.
* **Memory headroom**. `polars_workspace_bytes` gauge exposes the live
  total; alert when approaching the pod's memory limit. Hard caps in
  config (`max-bytes-per-df`, `max-total-bytes`) reject `put()` before
  OOM.
* **TTL alignment**. The workspace `idle-ttl-seconds` (default 60 min)
  matches the Dispatcher's sticky-session TTL, so a stale workspace
  and a stale sticky binding both expire together.
