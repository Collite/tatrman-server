package org.tatrman.kantheon.charon.core

import io.grpc.Status
import io.grpc.Status.Code
import org.tatrman.kantheon.charon.core.LocationKind
import org.tatrman.kantheon.charon.core.MoveRpc
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity

/**
 * The typed failure surface for Charon's planner + executor.
 *
 * Sealed hierarchy per `AGENTS.md` §9 ("Sealed types for finite outcome unions").
 * Each variant maps to exactly one gRPC `Status.Code` per `charon/contracts.md` §1
 * "Error model" — see [toStatus] below.
 *
 * Every error carries the Rule-6 [message] (kantheon `AGENTS.md` §6.1) that the
 * caller sees on the wire: a machine `code`, a `human_message` safe to show in
 * UIs, and a `severity` (always `ERROR` at v1; `WARNING`/`INFO` are reserved
 * for non-fatal hints — same-location no-op, fingerprint recomputed, etc.).
 */
sealed class CharonError(
    val code: String,
    val humanMessage: String,
    val grpcStatusCode: Code,
) {
    /** `Materialize` target was a worker session (use `Stage`); or `Stage`
     *  target was anything other than a worker session; or some other
     *  shape the contracts doc explicitly forbids. */
    data class IllegalTargetForRpc(
        val rpc: MoveRpc,
        val target: LocationKind,
    ) : CharonError(
            code = "illegal_target_for_rpc",
            humanMessage = "RPC ${rpc.name} does not accept target kind ${target.name}; see contracts §2",
            grpcStatusCode = Code.INVALID_ARGUMENT,
        )

    /** A `Location` arrived with no `oneof kind` set (an empty/default
     *  proto). The planner rejects it as `INVALID_ARGUMENT` rather than
     *  letting `Location.kind()` throw (review-006 R8.7 / L5). */
    data class EmptyLocation(
        val rpc: MoveRpc,
        val role: String,
    ) : CharonError(
            code = "empty_location",
            humanMessage = "RPC ${rpc.name} $role location has no kind set (empty Location)",
            grpcStatusCode = Code.INVALID_ARGUMENT,
        )

    /** `(rpc, source, target)` is not in the legality matrix — either the
     *  cell is absent (programmer error in the matrix) or the cell is
     *  explicitly `DISALLOWED`. Both surface identically. */
    data class IllegalPair(
        val rpc: MoveRpc,
        val source: LocationKind,
        val target: LocationKind,
    ) : CharonError(
            code = "illegal_pair",
            humanMessage = "Move ${rpc.name}(${source.name} -> ${target.name}) is not permitted by the v1 matrix",
            grpcStatusCode = Code.INVALID_ARGUMENT,
        )

    /** `Evict` on a `DbTable` — DB cleanup is the owner's job, not Charon's. */
    data class CannotEvictDbTable(
        val connectionId: String,
        val schema: String,
        val table: String,
    ) : CharonError(
            code = "cannot_evict_db_table",
            humanMessage = "DB table '$schema.$table' (conn '$connectionId') is the owner's job; not Charon's",
            grpcStatusCode = Code.INVALID_ARGUMENT,
        )

    /** `Materialize`/`Copy`/`Stage` with target = `DbTable` and no `db_write_mode` in
     *  `MoveOptions` — or `db_write_mode` set for a non-DB target. */
    data class MissingOrInvalidDbWriteMode(
        val target: LocationKind,
    ) : CharonError(
            code = "missing_or_invalid_db_write_mode",
            humanMessage = "Target kind ${target.name} requires explicit db_write_mode in MoveOptions",
            grpcStatusCode = Code.INVALID_ARGUMENT,
        )

    /** A `DbTable` location referenced a `connection_id` that the connection
     *  registry doesn't know about. Populated in Phase 2 Stage 2.1. */
    data class UnknownConnectionId(
        val connectionId: String,
    ) : CharonError(
            code = "unknown_connection_id",
            humanMessage = "Connection '$connectionId' is not registered in Charon's connection registry",
            grpcStatusCode = Code.INVALID_ARGUMENT,
        )

    /** The connection's allow-list forbids this op (read-only connection
     *  asked to write, or a schema outside the allow-list). Populated in
     *  Phase 2 Stage 2.1. */
    data class AllowListViolation(
        val connectionId: String,
        val op: String,
    ) : CharonError(
            code = "allow_list_violation",
            humanMessage = "Connection '$connectionId' does not permit $op; see the connection registry's allow-list",
            grpcStatusCode = Code.INVALID_ARGUMENT,
        )

    /** A `DbTable` location carried a structurally invalid SQL identifier
     *  (blank schema/table, control characters). Rejected before any SQL is
     *  issued. `INVALID_ARGUMENT` per contracts §1. */
    data class InvalidIdentifier(
        val detail: String,
    ) : CharonError(
            code = "invalid_identifier",
            humanMessage = "DB location has an invalid identifier: $detail",
            grpcStatusCode = Code.INVALID_ARGUMENT,
        )

    /** A column's Arrow type (extract) or DDL mapping (ingest) is not in the
     *  contracts §5 type matrix — e.g. a List/Struct/Map column over a DB edge,
     *  or a driver-specific JDBC type with no Arrow mapping. Names the column.
     *  `FAILED_PRECONDITION` per contracts §1 — no silent coercion. */
    data class UnmappableType(
        val column: String,
        val detail: String,
    ) : CharonError(
            code = "unmappable_type",
            humanMessage = "Column '$column' has a type with no v1 DB↔Arrow mapping: $detail (contracts §5)",
            grpcStatusCode = Code.FAILED_PRECONDITION,
        )

    /** A DB write mode precondition failed — CREATE on an existing table, or
     *  APPEND onto a table whose schema is incompatible with the source. Names
     *  the table. `FAILED_PRECONDITION` per contracts §1. */
    data class DbWritePrecondition(
        val schema: String,
        val table: String,
        val detail: String,
    ) : CharonError(
            code = "db_write_precondition",
            humanMessage = "Write to '$schema.$table' failed a precondition: $detail",
            grpcStatusCode = Code.FAILED_PRECONDITION,
        )

    /** The move's `expected_schema_fingerprint` doesn't match the source's
     *  actual fingerprint. `FAILED_PRECONDITION` per contracts §1 error model
     *  — the move never started, the source is unchanged. */
    data class FingerprintMismatch(
        val expected: String,
        val actual: String,
    ) : CharonError(
            code = "fingerprint_mismatch",
            humanMessage = "Source schema fingerprint does not match the expected fingerprint supplied in MoveOptions",
            grpcStatusCode = Code.FAILED_PRECONDITION,
        )

    /** The source location is absent (`S3 GetObject → NoSuchKey`, `Redis GET` miss,
     *  worker DF dropped). The move never started. */
    data class SourceNotFound(
        val source: LocationKind,
        val ref: String,
    ) : CharonError(
            code = "source_not_found",
            humanMessage = "Source ${source.name} '$ref' was not found",
            grpcStatusCode = Code.NOT_FOUND,
        )

    /** The move's bytes exceeded the per-move cap (`MoveOptions.max_bytes`
     *  or the server default). The partial state, if any, is unwound —
     *  no visible partial object (S3 temp-key + rename, etc.). */
    data class ByteCapExceeded(
        val bytes: Long,
        val cap: Long,
    ) : CharonError(
            code = "byte_cap_exceeded",
            humanMessage = "Move bytes $bytes exceeded per-move cap $cap",
            grpcStatusCode = Code.RESOURCE_EXHAUSTED,
        )

    /** A worker rejected the op for resource exhaustion (a workspace cap —
     *  `workspace_cap_exceeded` — or similar). Distinct from the move's own
     *  byte cap: the numbers aren't Charon's, so the worker's own message is
     *  carried. `RESOURCE_EXHAUSTED` per contracts §1. */
    data class WorkerResourceExhausted(
        val detail: String,
    ) : CharonError(
            code = "worker_resource_exhausted",
            humanMessage = "Worker rejected the move for resource exhaustion: $detail",
            grpcStatusCode = Code.RESOURCE_EXHAUSTED,
        )

    /** Underlying endpoint unreachable (S3 5xx, Redis dead, worker pod down,
     *  DB JDBC connect timeout). The move never started. */
    data class EndpointUnavailable(
        val endpoint: String,
    ) : CharonError(
            code = "endpoint_unavailable",
            humanMessage = "Endpoint '$endpoint' is unavailable; move not started",
            grpcStatusCode = Code.UNAVAILABLE,
        )

    /** The RPC hit the gRPC deadline. The move's partial state, if any, is
     *  unwound. */
    data class DeadlineExceeded(
        val deadlineMs: Long,
    ) : CharonError(
            code = "deadline_exceeded",
            humanMessage = "Move did not complete within the gRPC deadline (${deadlineMs}ms)",
            grpcStatusCode = Code.DEADLINE_EXCEEDED,
        )

    /** A worker engine has no RPC for the requested op (POLARS/Polars
     *  stage-in or evict at v1 — `worker.v1` has no Arrow-ingest / drop RPC).
     *  `UNIMPLEMENTED` per contracts §1 — names the engine + op + the gap. */
    data class WorkerOpUnsupported(
        val engine: String,
        val op: String,
        val detail: String,
    ) : CharonError(
            code = "worker_op_unsupported",
            humanMessage = "$engine worker has no $op path ($detail); see charon Stage 3.1 carry-over",
            grpcStatusCode = Code.UNIMPLEMENTED,
        )

    /** No worker gateway is wired for the requested [engine] on this pod
     *  (config/factory gap). `UNAVAILABLE`. */
    data class WorkerEngineUnavailable(
        val engine: String,
    ) : CharonError(
            code = "worker_engine_unavailable",
            humanMessage = "No worker gateway is configured for engine '$engine'",
            grpcStatusCode = Code.UNAVAILABLE,
        )

    /** Marker for endpoints not yet wired (the P1 skeleton — Stage 1.1).
     *  Resolves at Stage 1.2 (Seaweed), Stage 1.3 (Redis), Stage 2.1
     *  (DB), Stage 3.1 (Worker). */
    data class NotYetImplemented(
        val rpc: MoveRpc,
    ) : CharonError(
            code = "not_yet_implemented",
            humanMessage = "RPC ${rpc.name} not yet wired (Stage 1.1 skeleton); see services/charon/plan.md",
            grpcStatusCode = Code.UNIMPLEMENTED,
        )
}

/** Map a [CharonError] to the gRPC `Status` per contracts §1 error model. */
fun CharonError.toStatus(): Status = Status.fromCode(grpcStatusCode)

/** Convert a [CharonError] to a Rule-6 [ResponseMessage] suitable for
 *  embedding in `MoveResult.messages`, `EvictResult.messages`, or
 *  `DescribeResult.messages` (kantheon `AGENTS.md` §6.1). The error variant's
 *  `code` is the machine token, the `humanMessage` is safe to show in UIs. */
fun CharonError.toResponseMessage(): ResponseMessage =
    ResponseMessage
        .newBuilder()
        .setSeverity(Severity.ERROR)
        .setCode(code)
        .setHumanMessage(humanMessage)
        .build()

/** Convert a list of errors to a list of Rule-6 messages. */
fun List<CharonError>.toResponseMessages(): List<ResponseMessage> = map { it.toResponseMessage() }
