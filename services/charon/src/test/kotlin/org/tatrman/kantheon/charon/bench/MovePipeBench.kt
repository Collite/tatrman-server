package org.tatrman.kantheon.charon.bench

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.transfer.v1.Location
import org.tatrman.kantheon.charon.core.ArrowPipe
import org.tatrman.kantheon.charon.core.Either
import org.tatrman.kantheon.charon.core.LocationKind
import org.tatrman.kantheon.charon.core.MoveRpc
import org.tatrman.kantheon.charon.core.PipeOptions
import org.tatrman.kantheon.charon.core.Source
import org.tatrman.kantheon.charon.core.Target
import org.tatrman.kantheon.charon.endpoints.SeaweedEndpoint

/**
 * Charon move-core micro-bench (Stage 3.2 T4). Pumps N-row Arrow reference sets
 * through the real [ArrowPipe] (in-memory source → buffering target using the
 * production `serializeBatchesToIpcStream`), printing rows/s + MB/s — the
 * network-free floor every legal pair is bounded by. NOT a CI unit test; run via
 * `./gradlew :services:charon:bench`. See `services/charon/bench/README.md`.
 */
fun main() {
    println("charon move-core micro-bench (rows/s + MB/s; in-memory, network-free)")
    println("rows,batches,bytesMiB,wallMs,rowsPerSec,mibPerSec")
    for (rows in listOf(100_000, 1_000_000)) {
        runOne(rows, batchRows = 65_536)
    }
}

private val schema =
    Schema(
        listOf(
            Field("id", FieldType(false, ArrowType.Int(64, true), null), null),
            Field("name", FieldType(true, ArrowType.Utf8(), null), null),
            Field("value", FieldType(true, ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE), null), null),
        ),
    )

private fun runOne(
    totalRows: Int,
    batchRows: Int,
) {
    RootAllocator().use { alloc ->
        val ipc = buildMultiBatchIpc(totalRows, batchRows, alloc)
        val source =
            object : Source {
                override fun open(): ArrowReader = ArrowStreamReader(ByteArrayInputStream(ipc), alloc)

                override fun kind(): LocationKind = LocationKind.SEAWEED

                override fun ref(): String = "bench"
            }
        val sink = ByteArrayOutputStream()
        val target = BenchTarget(sink, alloc)

        val started = System.nanoTime()
        val result =
            ArrowPipe.pipe(
                MoveRpc.COPY,
                source,
                target,
                Location.getDefaultInstance(),
                Location.getDefaultInstance(),
                PipeOptions(chunkRows = batchRows, maxBytes = Long.MAX_VALUE),
            )
        val wallMs = (System.nanoTime() - started) / 1_000_000.0
        val movedRows = (result as Either.Right).value.rowCount
        val bytes = sink.size().toLong()
        val mib = bytes / (1024.0 * 1024.0)
        val rowsPerSec = movedRows / (wallMs / 1000.0)
        val mibPerSec = mib / (wallMs / 1000.0)
        println(
            "%d,%d,%.2f,%.1f,%.0f,%.1f".format(
                movedRows,
                (totalRows + batchRows - 1) / batchRows,
                mib,
                wallMs,
                rowsPerSec,
                mibPerSec,
            ),
        )
    }
}

private fun buildMultiBatchIpc(
    totalRows: Int,
    batchRows: Int,
    alloc: RootAllocator,
): ByteArray {
    val out = ByteArrayOutputStream()
    VectorSchemaRoot.create(schema, alloc).use { root ->
        val id = root.getVector("id") as BigIntVector
        val name = root.getVector("name") as VarCharVector
        val value = root.getVector("value") as Float8Vector
        ArrowStreamWriter(root, null, out).use { w ->
            w.start()
            var written = 0
            while (written < totalRows) {
                val n = minOf(batchRows, totalRows - written)
                id.allocateNew(n)
                name.allocateNew()
                value.allocateNew(n)
                for (i in 0 until n) {
                    id.setSafe(i, (written + i).toLong())
                    name.setSafe(i, "row-${written + i}".toByteArray(Charsets.UTF_8))
                    value.setSafe(i, (written + i) * 1.5)
                }
                root.rowCount = n
                w.writeBatch()
                written += n
            }
            w.end()
        }
    }
    return out.toByteArray()
}

/** A target mirroring the Seaweed write shape: buffer owned batches, serialize
 *  via the production `serializeBatchesToIpcStream` at commit. */
private class BenchTarget(
    private val out: ByteArrayOutputStream,
    private val alloc: RootAllocator,
) : Target {
    private val batches = mutableListOf<VectorSchemaRoot>()

    override fun begin(schema: Schema): Any = Unit

    override fun writeBatch(
        receipt: Any,
        root: VectorSchemaRoot,
    ) {
        if (root.rowCount == 0) return
        val owned = VectorSchemaRoot.create(root.schema, alloc)
        for (i in 0 until root.schema.fields.size) {
            val dst = owned.getVector(i)
            dst.reAlloc()
            root.getVector(i).makeTransferPair(dst).transfer()
        }
        owned.rowCount = root.rowCount
        batches.add(owned)
    }

    override fun commit(receipt: Any): Any {
        out.write(SeaweedEndpoint.serializeBatchesToIpcStream(schema, batches, alloc))
        batches.forEach { it.close() }
        batches.clear()
        return Unit
    }

    override fun discard(receipt: Any) {
        batches.forEach { it.close() }
        batches.clear()
    }

    override fun kind(): LocationKind = LocationKind.SEAWEED

    override fun ref(): String = "bench-sink"
}
