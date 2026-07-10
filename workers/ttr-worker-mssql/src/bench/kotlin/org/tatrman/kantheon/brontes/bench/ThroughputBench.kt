package org.tatrman.kantheon.brontes.bench

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.kantheon.brontes.arrow.ArrowIpcSerializer

/**
 * Arrow IPC read-out throughput baseline for Brontes cost hints (Fork Stage 4.1 T4).
 *
 * Builds a 1e5-row VectorSchemaRoot (id / region / amount) and times the worker's
 * own [ArrowIpcSerializer.serializeBatch] — the CPU-bound read-out that turns a
 * fetched batch into the Arrow IPC bytes Kyklop streams back. Reports rows/s +
 * p50/p95. The **DB fetch is excluded** (no MSSQL in unit scope — that is the
 * integration suite's territory); this isolates the serialize step, which is the
 * part shared by every query regardless of source.
 *
 * Run on demand (not in the CI gate):
 *
 *     ./gradlew :workers:brontes:benchThroughput
 *
 * Indicative, single-host; the README records a conservatively-rounded baseline.
 */
private const val ROWS = 100_000
private const val WARMUP = 3
private const val REPEATS = 20

private fun buildRoot(allocator: RootAllocator): VectorSchemaRoot {
    val schema =
        Schema(
            listOf(
                Field("id", FieldType.notNullable(ArrowType.Int(64, true)), null),
                Field("region", FieldType.notNullable(ArrowType.Utf8()), null),
                Field(
                    "amount",
                    FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                    null,
                ),
            ),
        )
    val root = VectorSchemaRoot.create(schema, allocator)
    val id = root.getVector("id") as BigIntVector
    val region = root.getVector("region") as VarCharVector
    val amount = root.getVector("amount") as Float8Vector
    id.allocateNew(ROWS)
    region.allocateNew()
    amount.allocateNew(ROWS)
    for (i in 0 until ROWS) {
        id.set(i, i.toLong())
        region.setSafe(i, "r${i % 8}".toByteArray())
        amount.set(i, i * 1.5)
    }
    root.setRowCount(ROWS)
    return root
}

fun main() {
    RootAllocator(Long.MAX_VALUE).use { allocator ->
        buildRoot(allocator).use { root ->
            repeat(WARMUP) { ArrowIpcSerializer.serializeBatch(root) }
            val samplesMs = DoubleArray(REPEATS)
            var bytes = 0
            for (i in 0 until REPEATS) {
                val t0 = System.nanoTime()
                bytes = ArrowIpcSerializer.serializeBatch(root).size
                samplesMs[i] = (System.nanoTime() - t0) / 1_000_000.0
            }
            samplesMs.sort()
            val p50 = samplesMs[samplesMs.size / 2]
            val p95 = samplesMs[minOf(samplesMs.size - 1, (0.95 * samplesMs.size).toInt())]
            val rowsPerSec = ROWS / (p50 / 1000.0)
            println("rows           : $ROWS")
            println("ipc_bytes      : $bytes")
            println("repeats        : $REPEATS")
            println("p50_ms         : ${"%.2f".format(p50)}")
            println("p95_ms         : ${"%.2f".format(p95)}")
            println("rows_per_sec   : ${"%,.0f".format(rowsPerSec)}  (at p50)")
        }
    }
}
