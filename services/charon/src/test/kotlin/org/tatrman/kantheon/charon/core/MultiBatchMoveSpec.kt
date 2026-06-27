package org.tatrman.kantheon.charon.core

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.charon.v1.Location
import org.tatrman.charon.v1.MoveResult
import org.tatrman.kantheon.charon.endpoints.SeaweedEndpoint

/**
 * The multi-batch round-trip gate (review-006 B1, R1).
 *
 * Review-006 proved that the Stage 1.2 `ArrowPipe` silently produced
 * an **empty** target object on any source with more than one record
 * batch while reporting `MoveResult.rowCount = N` and a valid
 * fingerprint — the worst possible failure mode for a data mover
 * (silent data loss with a plausible success). The unit suite was
 * single-batch-only, so the bug slipped through.
 *
 * This spec is the gate. A 3-batch source driven through the real
 * `ArrowPipe.pipe` and a target that uses the production
 * `SeaweedEndpoint.serializeBatchesToIpcStream` must round-trip:
 *
 * - 9 rows in (batch sizes 2, 3, 4 — values `0..8` in column
 *   `i: Int64`; the per-batch slices are `[0,1]`, `[0,1,2]`,
 *   `[0,1,2,3]` — see the fixture KDoc).
 * - The produced bytes, read back with a fresh `ArrowStreamReader`,
 *   yield exactly the expected pattern.
 *
 * If this test is red, the fix (R1.2) is to make each `writeBatch`
 * call materialise the batch into owned data (a freshly-allocated
 * `VectorSchemaRoot` populated via `TransferPair` from the reader's
 * reused root), not a reference to the reader's reused root. The
 * pipe hot path also streams batch-by-batch to the target
 * (review-006 R2 — multipart upload for the S3 path), not
 * accumulate-then-flush.
 */
class MultiBatchMoveSpec :
    StringSpec({

        val schema = schemaOf(int64("i"))

        // --- Gate: 3-batch move through the real pipe + the real serializer ---

        "3-batch source moves through the real pipe and round-trips all 9 rows" {
            // Build a 3-batch source stream. Row counts: 2, 3, 4 (= 9 rows).
            val alloc = RootAllocator()
            val sourceStreamBytes = makeMultiBatchIpcStream(schema, alloc, listOf(2, 3, 4))

            // The target is in-memory but uses the production
            // `SeaweedEndpoint.serializeBatchesToIpcStream` (the
            // load-bearing serialization path the S3 target would
            // use at commit time). The captured `producedBytes` is
            // what a real S3 target would buffer for the PUT.
            val producedBytes = java.io.ByteArrayOutputStream()
            val target =
                BufferingTarget(
                    out = producedBytes,
                    schema = schema,
                    sharedAlloc = alloc,
                )
            val source =
                InMemorySource(
                    streamBytes = sourceStreamBytes,
                    sharedAlloc = alloc,
                )
            val result =
                ArrowPipe.pipe(
                    MoveRpc.COPY,
                    source,
                    target,
                    Location.getDefaultInstance(),
                    Location.getDefaultInstance(),
                    PipeOptions(),
                )
            result.shouldBeInstanceOf<Either.Right<MoveResult>>()
            val moveResult = (result as Either.Right).value
            withClue("MoveResult.rowCount must equal the source's total row count (9), not 0") {
                moveResult.rowCount shouldBe 9L
            }
            val readback = readIpcValues(producedBytes.toByteArray(), alloc)
            // Per the fixture (see makeMultiBatchIpcStream's KDoc):
            // each batch writes values [0..n-1] of the source
            // vector. For batches of 2, 3, 4 rows, the readback is
            // the concatenation [0, 1, 0, 1, 2, 0, 1, 2, 3] = 9
            // values. The test's contract is the row count and the
            // per-batch row-count preservation; the exact values
            // are deterministic.
            withClue("the produced object must contain all 9 rows in the expected pattern") {
                readback shouldBe listOf(0L, 1L, 0L, 1L, 2L, 0L, 1L, 2L, 3L)
            }
        }

        // --- Auxiliary: 1-batch source still works (regression net for R1) ---

        "1-batch source still round-trips (regression net)" {
            val alloc = RootAllocator()
            val sourceStreamBytes = makeMultiBatchIpcStream(schema, alloc, listOf(5))
            val producedBytes = java.io.ByteArrayOutputStream()
            val target =
                BufferingTarget(
                    out = producedBytes,
                    schema = schema,
                    sharedAlloc = alloc,
                )
            val source =
                InMemorySource(
                    streamBytes = sourceStreamBytes,
                    sharedAlloc = alloc,
                )
            val result =
                ArrowPipe.pipe(
                    MoveRpc.COPY,
                    source,
                    target,
                    Location.getDefaultInstance(),
                    Location.getDefaultInstance(),
                    PipeOptions(),
                )
            result.shouldBeInstanceOf<Either.Right<MoveResult>>()
            (result as Either.Right).value.rowCount shouldBe 5L
            val readback = readIpcValues(producedBytes.toByteArray(), alloc)
            readback shouldBe listOf(0L, 1L, 2L, 3L, 4L)
        }

        // --- R2 gate: the REAL SeaweedEndpoint writes ONE coherent object ---
        //
        // review-006 re-review: this is the test that was missing — it
        // reassembles what the SeaweedEndpoint actually PUTs and reads it
        // back. The earlier multipart implementation passed its test
        // (which only checked the pipe's row count + that uploadPart
        // fired) while writing a malformed object that read back EMPTY.
        // Here we capture the PUT body and round-trip it.

        "3-batch source through the real SeaweedEndpoint writes one readable object (all 9 rows)" {
            val alloc = RootAllocator()
            val sourceStreamBytes = makeMultiBatchIpcStream(schema, alloc, listOf(2, 3, 4))
            val s3 = io.mockk.mockk<software.amazon.awssdk.services.s3.S3Client>(relaxed = false)

            // Capture the bytes the endpoint PUTs to the temp key.
            val putBody = java.io.ByteArrayOutputStream()
            io.mockk.every {
                s3.putObject(
                    any<software.amazon.awssdk.services.s3.model.PutObjectRequest>(),
                    any<software.amazon.awssdk.core.sync.RequestBody>(),
                )
            } answers {
                val body = secondArg<software.amazon.awssdk.core.sync.RequestBody>()
                body.contentStreamProvider().newStream().use { it.copyTo(putBody) }
                software.amazon.awssdk.services.s3.model.PutObjectResponse
                    .builder()
                    .build()
            }
            io.mockk.every {
                s3.copyObject(any<software.amazon.awssdk.services.s3.model.CopyObjectRequest>())
            } returns
                software.amazon.awssdk.services.s3.model.CopyObjectResponse
                    .builder()
                    .build()
            io.mockk.every {
                s3.deleteObject(any<software.amazon.awssdk.services.s3.model.DeleteObjectRequest>())
            } returns
                software.amazon.awssdk.services.s3.model.DeleteObjectResponse
                    .builder()
                    .build()

            val target =
                SeaweedEndpoint(s3, alloc).apply {
                    setLocation(
                        org.tatrman.charon.v1.SeaweedBlob
                            .newBuilder()
                            .setBucket("b")
                            .setKey("tgt")
                            .build(),
                    )
                }
            val source = InMemorySource(streamBytes = sourceStreamBytes, sharedAlloc = alloc)
            val result =
                ArrowPipe.pipe(
                    MoveRpc.COPY,
                    source,
                    target,
                    Location.getDefaultInstance(),
                    Location.getDefaultInstance(),
                    PipeOptions(),
                )
            result.shouldBeInstanceOf<Either.Right<MoveResult>>()
            (result as Either.Right).value.rowCount shouldBe 9L

            // The load-bearing assertion the old multipart test lacked:
            // read the ACTUAL stored object back. A malformed stream
            // would read back as [] (review-006 B1/R2).
            withClue("the stored object must read back as all 9 rows in order") {
                readIpcValues(putBody.toByteArray(), alloc) shouldBe
                    listOf(0L, 1L, 0L, 1L, 2L, 0L, 1L, 2L, 3L)
            }
            withClue("temp-key + rename atomicity: copy to the real key + delete the temp") {
                io.mockk.verify(
                    exactly = 1,
                ) { s3.copyObject(any<software.amazon.awssdk.services.s3.model.CopyObjectRequest>()) }
                io.mockk.verify(
                    exactly = 1,
                ) { s3.deleteObject(any<software.amazon.awssdk.services.s3.model.DeleteObjectRequest>()) }
            }
        }
    })

// --- In-memory Source/Target for the round-trip gate ---

/** A source that wraps an in-memory Arrow IPC byte stream. */
private class InMemorySource(
    private val streamBytes: ByteArray,
    private val sharedAlloc: RootAllocator,
) : Source {
    override fun open(): ArrowStreamReader = ArrowStreamReader(ByteArrayInputStream(streamBytes), sharedAlloc)

    override fun kind(): LocationKind = LocationKind.SEAWEED

    override fun ref(): String = "in-memory"
}

/** A target that mirrors the production `SeaweedEndpoint.writeBatch`
 *  shape (review-006 R1.2): each batch is materialised into an
 *  owned `VectorSchemaRoot` via `TransferPair` (the reader's
 *  reused root is the source — but we don't keep a reference to
 *  it; the owned root has the data we need). At `commit` time
 *  the production `SeaweedEndpoint.serializeBatchesToIpcStream`
 *  serialises the buffered owned roots to the captured
 *  `ByteArrayOutputStream`.
 *
 *  The owned roots use the same parent [sharedAlloc] as the
 *  reader (Arrow's `TransferPair` requires source and
 *  destination to share a root allocator). */
private class BufferingTarget(
    private val out: java.io.ByteArrayOutputStream,
    private val schema: Schema,
    private val sharedAlloc: RootAllocator,
) : Target {
    private val pendingBatches = mutableListOf<VectorSchemaRoot>()

    override fun begin(schema: Schema): Any = Unit

    override fun writeBatch(
        receipt: Any,
        root: VectorSchemaRoot,
    ) {
        if (root.rowCount == 0) return
        // R1.2: materialise into an owned root. The reader's
        // reused root stays untouched (the pipe may call
        // loadNextBatch() again on the next iteration).
        val owned = VectorSchemaRoot.create(schema, sharedAlloc)
        try {
            for (i in 0 until schema.fields.size) {
                val srcVec = root.getVector(i)
                val dstVec = owned.getVector(i)
                dstVec.reAlloc()
                srcVec.makeTransferPair(dstVec).transfer()
            }
            owned.rowCount = root.rowCount
            pendingBatches.add(owned)
        } catch (e: Exception) {
            owned.close()
            throw e
        }
    }

    override fun commit(receipt: Any): Any {
        val bytes = SeaweedEndpoint.serializeBatchesToIpcStream(schema, pendingBatches, sharedAlloc)
        out.write(bytes)
        return Unit
    }

    override fun discard(receipt: Any) {
        pendingBatches.forEach { it.close() }
        pendingBatches.clear()
    }

    override fun kind(): LocationKind = LocationKind.SEAWEED

    override fun ref(): String = "in-memory-buffer"
}

// --- Fixtures ---

/** Build a real Arrow IPC stream with the given per-batch row counts.
 *  The schema is a single int64 column `i`. Each batch writes the
 *  prefix `[0, 1, ..., n-1]` of the source vector — the resulting
 *  stream's row count is `sum(perBatchRowCounts)` and the readback
 *  is the concatenation of those per-batch prefixes.
 *
 *  The 3-batch `2,3,4` case therefore produces
 *  `[0,1, 0,1,2, 0,1,2,3]` (batch 1 of 2 rows, batch 2 of 3 rows,
 *  batch 3 of 4 rows). The readback assertion is on this
 *  pattern. The point of the test is the move-pipe contract:
 *  every batch's row count + value slice must be preserved
 *  through the move (the review-006 B1 bug would have lost all
 *  but the first batch — the readback would have been
 *  `[0, 1]` only). */
private fun makeMultiBatchIpcStream(
    schema: Schema,
    alloc: RootAllocator,
    perBatchRowCounts: List<Int>,
): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val maxRows = perBatchRowCounts.max()
    val root = VectorSchemaRoot.create(schema, alloc)
    val vec = root.getVector(0) as BigIntVector
    vec.allocateNew(maxRows)
    for (i in 0 until maxRows) vec.set(i, i.toLong())
    vec.valueCount = maxRows
    ArrowStreamWriter(root, null, out).use { writer ->
        writer.start()
        for (n in perBatchRowCounts) {
            root.rowCount = n
            writer.writeBatch()
        }
        writer.end()
    }
    root.close()
    return out.toByteArray()
}

/** Read an Arrow IPC stream's int64 column `i` back as a list. */
private fun readIpcValues(
    bytes: ByteArray,
    alloc: RootAllocator,
): List<Long> {
    val out = mutableListOf<Long>()
    ArrowStreamReader(ByteArrayInputStream(bytes), alloc).use { reader ->
        while (reader.loadNextBatch()) {
            val r = reader.vectorSchemaRoot
            val vec = r.getVector(0) as BigIntVector
            for (i in 0 until vec.valueCount) out.add(vec.get(i))
        }
    }
    return out
}

private fun int64(name: String): Field = Field(name, FieldType.notNullable(ArrowType.Int(64, true)), null)

private fun schemaOf(vararg fields: Field): Schema = Schema(listOf(*fields))
