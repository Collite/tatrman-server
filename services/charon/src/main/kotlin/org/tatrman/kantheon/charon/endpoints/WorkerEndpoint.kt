package org.tatrman.kantheon.charon.endpoints

import java.util.UUID
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.kantheon.charon.core.LocationKind
import org.tatrman.kantheon.charon.core.Source
import org.tatrman.kantheon.charon.core.Target
import org.tatrman.kantheon.charon.core.WorkerGateway

/**
 * The worker-DF endpoint (charon/plan.md §5 Stage 3.1). A thin Source/Target
 * adapter over a [WorkerGateway] — the engine-specific gRPC lives in the
 * gateway; this class does the Arrow plumbing the pipe needs.
 *
 * As a **Target** (the `Stage` RPC): buffers each batch into an owned
 * `VectorSchemaRoot` (review-006 R1.2 — the reader reuses its root, so a stored
 * reference would lose data), then on [commit] stages the lot into the session
 * DataFrame via [WorkerGateway.stageIn].
 *
 * As a **Source** (worker → X moves): [open] returns the gateway's streaming
 * scan-out reader.
 */
class WorkerEndpoint(
    private val gateway: WorkerGateway,
    private val sessionId: String,
    private val dfName: String,
    private val expectedFingerprint: String? = null,
    parentAllocator: RootAllocator? = null,
) : Source,
    Target {
    private val parentAllocator: RootAllocator =
        parentAllocator ?: org.apache.arrow.memory
            .RootAllocator()

    // --- Source (scan-out) ---

    override fun open(): ArrowReader? = gateway.scanOut(sessionId, dfName, parentAllocator)

    override fun kind(): LocationKind = LocationKind.WORKER_DF

    override fun ref(): String = "${gateway.kind()}:$sessionId/$dfName"

    // --- Target (stage-in, three-phase) ---

    override fun begin(schema: Schema): Any =
        WorkerWriteReceipt(
            schema = schema,
            allocator = parentAllocator.newChildAllocator("worker-stage-${UUID.randomUUID()}", 0, Long.MAX_VALUE),
        )

    override fun writeBatch(
        receipt: Any,
        root: VectorSchemaRoot,
    ) {
        if (root.rowCount == 0) return
        val r = receipt as WorkerWriteReceipt
        val owned = VectorSchemaRoot.create(r.schema, r.allocator)
        try {
            for (i in 0 until r.schema.fields.size) {
                val dst = owned.getVector(i)
                dst.reAlloc()
                root.getVector(i).makeTransferPair(dst).transfer()
            }
            owned.rowCount = root.rowCount
            r.pendingBatches.add(owned)
        } catch (e: Exception) {
            owned.close()
            throw e
        }
    }

    override fun commit(receipt: Any): Any {
        val r = receipt as WorkerWriteReceipt
        try {
            val rows = gateway.stageIn(sessionId, dfName, r.schema, r.pendingBatches, expectedFingerprint)
            return WorkerCommitReceipt(ref = "${gateway.kind()}:$sessionId/$dfName", rows = rows)
        } finally {
            r.closeAll()
            r.allocator.close()
        }
    }

    override fun discard(receipt: Any) {
        val r = receipt as? WorkerWriteReceipt ?: return
        r.closeAll()
        r.allocator.close()
    }
}

internal data class WorkerWriteReceipt(
    val schema: Schema,
    val allocator: org.apache.arrow.memory.BufferAllocator,
    val pendingBatches: MutableList<VectorSchemaRoot> = mutableListOf(),
) {
    fun closeAll() {
        pendingBatches.forEach { it.close() }
        pendingBatches.clear()
    }
}

internal data class WorkerCommitReceipt(
    val ref: String,
    val rows: Long,
)
