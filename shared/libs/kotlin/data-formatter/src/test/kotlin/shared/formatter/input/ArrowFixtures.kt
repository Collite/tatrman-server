// SPDX-License-Identifier: Apache-2.0
package shared.formatter.input

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.BitVector
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.TimeStampMicroVector
import org.apache.arrow.vector.VarBinaryVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.channels.Channels

/** Helpers to build Arrow IPC byte arrays for tests. */
internal object ArrowFixtures {
    private fun nullableField(
        name: String,
        type: ArrowType,
    ): Field = Field(name, FieldType.nullable(type), null)

    private fun notNullField(
        name: String,
        type: ArrowType,
    ): Field = Field(name, FieldType.notNullable(type), null)

    /** Empty stream — schema header only. */
    fun emptyArrow(): ByteArray = withRoot(Schema(emptyList())) { root, _ -> root.setRowCount(0) }

    /** Single-batch order fixture: 5 rows × 4 cols (INT64, STRING, DECIMAL, TIMESTAMP). One NULL per column. */
    fun ordersFixture(): ByteArray {
        val schema =
            Schema(
                listOf(
                    nullableField("id", ArrowType.Int(64, true)),
                    nullableField("customer", ArrowType.Utf8()),
                    nullableField("amount", ArrowType.Decimal(18, 2, 128)),
                    nullableField(
                        "ordered_at",
                        ArrowType.Timestamp(TimeUnit.MICROSECOND, null),
                    ),
                ),
            )
        return withRoot(schema) { root, _ ->
            val ids = root.getVector("id") as BigIntVector
            val cust = root.getVector("customer") as VarCharVector
            val amt = root.getVector("amount") as DecimalVector
            val ts = root.getVector("ordered_at") as TimeStampMicroVector

            val custValues = listOf("Alice", "Bob", null, "Diana", "Eve")
            val idValues = listOf(1L, 2L, 3L, null, 5L)
            val amtValues =
                listOf(
                    BigDecimal("12.50"),
                    BigDecimal("99.99"),
                    BigDecimal("0.10"),
                    BigDecimal("100.00"),
                    null,
                )
            // 2026-05-03T12:00:00 in micros since epoch
            val baseMicros = 1_777_852_800_000_000L
            val tsValues =
                listOf(
                    baseMicros,
                    baseMicros + 1_000_000L,
                    baseMicros + 2_000_000L,
                    baseMicros + 3_000_000L,
                    null,
                )

            for (r in 0 until 5) {
                val id = idValues[r]
                if (id == null) ids.setNull(r) else ids.setSafe(r, id)
                val c = custValues[r]
                if (c == null) cust.setNull(r) else cust.setSafe(r, c.toByteArray(Charsets.UTF_8))
                val a = amtValues[r]
                if (a == null) amt.setNull(r) else amt.setSafe(r, a)
                val tv = tsValues[r]
                if (tv == null) ts.setNull(r) else ts.setSafe(r, tv)
            }
            root.setRowCount(5)
        }
    }

    /** Multi-batch: two batches of 2 rows each, single INT64 column. */
    fun multiBatchInts(): ByteArray {
        val schema = Schema(listOf(nullableField("n", ArrowType.Int(64, true))))
        val out = ByteArrayOutputStream()
        val allocator: BufferAllocator = RootAllocator(Long.MAX_VALUE)
        try {
            VectorSchemaRoot.create(schema, allocator).use { root ->
                root.allocateNew()
                ArrowStreamWriter(root, null, Channels.newChannel(out)).use { writer ->
                    writer.start()
                    val v = root.getVector("n") as BigIntVector
                    // batch 1 — [10, 20]
                    v.setSafe(0, 10L)
                    v.setSafe(1, 20L)
                    root.setRowCount(2)
                    writer.writeBatch()
                    // batch 2 — [null, 40]
                    v.reset()
                    v.setNull(0)
                    v.setSafe(1, 40L)
                    root.setRowCount(2)
                    writer.writeBatch()
                    writer.end()
                }
            }
        } finally {
            allocator.close()
        }
        return out.toByteArray()
    }

    /** Stream covering all LogicalTypes. */
    fun allLogicalTypes(): ByteArray {
        val schema =
            Schema(
                listOf(
                    nullableField("i", ArrowType.Int(64, true)),
                    nullableField("d", ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                    nullableField("dec", ArrowType.Decimal(10, 2, 128)),
                    nullableField("s", ArrowType.Utf8()),
                    nullableField("b", ArrowType.Bool()),
                    nullableField("dt", ArrowType.Date(DateUnit.DAY)),
                    nullableField("ts", ArrowType.Timestamp(TimeUnit.MICROSECOND, null)),
                    nullableField("tsz", ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC")),
                    nullableField("bin", ArrowType.Binary()),
                ),
            )
        return withRoot(schema) { root, _ ->
            (root.getVector("i") as BigIntVector).setSafe(0, 7L)
            (root.getVector("d") as Float8Vector).setSafe(0, 2.5)
            (root.getVector("dec") as DecimalVector).setSafe(0, BigDecimal("3.14"))
            (root.getVector("s") as VarCharVector).setSafe(0, "hi".toByteArray(Charsets.UTF_8))
            (root.getVector("b") as BitVector).setSafe(0, 1)
            // 2026-05-03 → epoch day
            val epochDay =
                java.time.LocalDate
                    .of(2026, 5, 3)
                    .toEpochDay()
                    .toInt()
            (root.getVector("dt") as DateDayVector).setSafe(0, epochDay)
            val micros = 1_777_852_800_000_000L
            (root.getVector("ts") as TimeStampMicroVector).setSafe(0, micros)
            (root.getVector("tsz") as TimeStampMicroTZVector).setSafe(0, micros)
            (root.getVector("bin") as VarBinaryVector).setSafe(0, byteArrayOf(0x41, 0x42, 0x43))

            // null row
            for (i in 0 until root.fieldVectors.size) {
                root.fieldVectors[i].setNull(1)
            }
            root.setRowCount(2)
        }
    }

    private inline fun withRoot(
        schema: Schema,
        block: (VectorSchemaRoot, MutableList<FieldVector>) -> Unit,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val allocator: BufferAllocator = RootAllocator(Long.MAX_VALUE)
        try {
            VectorSchemaRoot.create(schema, allocator).use { root ->
                root.allocateNew()
                ArrowStreamWriter(root, null, Channels.newChannel(out)).use { writer ->
                    writer.start()
                    block(root, root.fieldVectors)
                    writer.writeBatch()
                    writer.end()
                }
            }
        } finally {
            allocator.close()
        }
        return out.toByteArray()
    }
}
