package org.tatrman.kantheon.charon.endpoints

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.Schema

/**
 * Arrow IPC ↔ worker-chunk helpers shared by the worker gateways.
 *
 * **Chunk convention (mocked-suite contract, Stage 3.1):** each worker chunk's
 * `ipc_payload` (Metis `ArrowChunk.ipc_payload` / worker `ResultBatch.arrow_ipc`)
 * is a **self-contained** Arrow IPC stream — schema + one record batch + EOS —
 * matching the Steropes `_serialize_record_batch` convention the explore
 * confirmed. Charon reads each payload standalone and re-assembles. The exact
 * runtime convention against the live Metis/Steropes pods is reconciled in the
 * integration suite (testing policy §4); the Charon-side wiring is what these
 * helpers verify.
 */
internal object WorkerArrowCodec {
    /** Serialize one batch (schema + the batch) to a standalone IPC stream by
     *  writing the batch's own root directly — no `TransferPair`, so the batch
     *  may live under any allocator (no shared-root requirement). */
    fun batchToChunk(
        schema: Schema,
        batch: VectorSchemaRoot,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ArrowStreamWriter(batch, null, out).use { w ->
            w.start()
            if (batch.rowCount > 0) w.writeBatch()
            w.end()
        }
        return out.toByteArray()
    }

    /** Serialize a schema with no rows (schema-only stream) — used to stage an
     *  empty DataFrame. */
    fun schemaOnlyChunk(
        schema: Schema,
        alloc: BufferAllocator,
    ): ByteArray = SeaweedEndpoint.serializeBatchesToIpcStream(schema, emptyList(), alloc)

    /**
     * Re-assemble a list of standalone IPC-stream [payloads] into a single
     * coherent IPC stream and return an [ArrowReader] over it. Empty / schema-
     * only payloads contribute their schema (from the first non-null payload)
     * and no rows. Returns `null` if there are no payloads at all.
     */
    fun payloadsToReader(
        payloads: List<ByteArray>,
        alloc: BufferAllocator,
    ): ArrowReader? {
        if (payloads.isEmpty()) return null
        val ownedBatches = mutableListOf<VectorSchemaRoot>()
        var schema: Schema? = null
        try {
            for (payload in payloads) {
                ArrowStreamReader(ByteArrayInputStream(payload), alloc).use { rd ->
                    if (schema == null) schema = rd.vectorSchemaRoot.schema
                    while (rd.loadNextBatch()) {
                        val src = rd.vectorSchemaRoot
                        if (src.rowCount == 0) continue
                        val owned = VectorSchemaRoot.create(src.schema, alloc)
                        for (i in 0 until src.schema.fields.size) {
                            val dst = owned.getVector(i)
                            dst.reAlloc()
                            src.getVector(i).makeTransferPair(dst).transfer()
                        }
                        owned.rowCount = src.rowCount
                        ownedBatches.add(owned)
                    }
                }
            }
            val finalSchema = schema ?: return null
            val bytes = SeaweedEndpoint.serializeBatchesToIpcStream(finalSchema, ownedBatches, alloc)
            return ArrowStreamReader(ByteArrayInputStream(bytes), alloc)
        } finally {
            ownedBatches.forEach { it.close() }
        }
    }

    /** The schema of the first payload (for a describe probe). */
    fun schemaOf(
        payload: ByteArray,
        alloc: BufferAllocator,
    ): Schema = ArrowStreamReader(ByteArrayInputStream(payload), alloc).use { it.vectorSchemaRoot.schema }
}
