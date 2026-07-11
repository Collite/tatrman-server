package org.tatrman.kantheon.charon.core

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.transfer.v1.DescribeResult
import org.tatrman.transfer.v1.EvictResult
import org.tatrman.transfer.v1.WorkerKind

/**
 * The worker-edge SPI (charon/plan.md §5 Stage 3.1). A [WorkerGateway] talks to
 * one worker engine and exposes the four worker-DF operations Charon needs:
 * stage data **in** to a session DataFrame, scan it **out**, [describe] it
 * (PD-5 liveness), and [evict] it.
 *
 * Both engines now expose the **full** workspace surface (the worker-arc
 * `ImportDataFrame`/`DropWorkspaceEntry` RPCs landed on `worker.v1`/Polars at
 * the Charon Stage 3.1 closeout, 2026-06-26):
 *
 *   - **METIS** (`org.tatrman.metis.v1.MetisService`) — `ImportDataFrame`
 *     (client-stream), `ExportDataFrame` (server-stream), `DropWorkspaceEntry`.
 *     [MetisWorkerGateway]. Pythia 4.2's "Charon stages a handle → Metis session DF".
 *   - **POLARS / Polars** (`org.tatrman.worker.v1.WorkerService`) —
 *     `ImportDataFrame` (stage-in), `Execute` over a `WorkspaceRef` (scan-out),
 *     `DropWorkspaceEntry` (evict). [PolarsWorkerGateway]. Pythia 4.1's
 *     "Charon stages data into a Polars-Worker session DataFrame via Stage".
 *
 * [WorkerOpUnsupportedException] remains as a defensive seam for any *future*
 * engine that lacks an op; no current gateway raises it.
 */
interface WorkerGateway {
    fun kind(): WorkerKind

    /** Stage the buffered Arrow [batches] (already deep-copied, owned) into the
     *  session DataFrame `(sessionId, dfName)`. Returns the staged row count.
     *  @throws WorkerOpUnsupportedException for engines without an ingest RPC. */
    fun stageIn(
        sessionId: String,
        dfName: String,
        schema: Schema,
        batches: List<VectorSchemaRoot>,
        expectedFingerprint: String?,
    ): Long

    /** Open a streaming read of the session DataFrame `(sessionId, dfName)`.
     *  @throws WorkerDfNotFoundException when the DF is absent / TTL'd out. */
    fun scanOut(
        sessionId: String,
        dfName: String,
        alloc: BufferAllocator,
    ): ArrowReader

    /** PD-5 liveness probe: `exists` + `schema_fingerprint` (+ row count /
     *  expiry when the engine reports them). `exists = false` for an
     *  absent / expired DF. */
    fun describe(
        sessionId: String,
        dfName: String,
        alloc: BufferAllocator,
    ): DescribeResult

    /** Drop the session DataFrame. @throws WorkerOpUnsupportedException for
     *  engines without a drop RPC (POLARS/Polars at v1). */
    fun evict(
        sessionId: String,
        dfName: String,
    ): EvictResult
}

/** Resolve a [WorkerGateway] for a [WorkerKind] (`null` ⇒ that engine isn't
 *  wired on this pod). Production builds gRPC-channel-backed gateways from
 *  config; tests inject fixture-backed ones. */
interface WorkerGatewayFactory {
    fun forKind(kind: WorkerKind): WorkerGateway?
}

/** The engine has no RPC for this operation (e.g. POLARS/Polars stage-in or
 *  evict at v1). The executor maps it to `UNIMPLEMENTED` with a message naming
 *  the gap, so a caller gets a clear "this engine can't do that yet" rather
 *  than a silent failure. */
class WorkerOpUnsupportedException(
    val engine: WorkerKind,
    val op: String,
    val detail: String,
) : RuntimeException("$engine worker has no $op path: $detail")

/** The referenced worker DataFrame is absent (dropped / session TTL'd out).
 *  Maps to `NOT_FOUND` (a move source) or `exists = false` (a Describe). */
class WorkerDfNotFoundException(
    val ref: String,
) : RuntimeException("worker DF '$ref' not found")
