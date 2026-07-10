package shared.formatter.input

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.BitVector
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.Float4Vector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.SmallIntVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.TimeStampMicroVector
import org.apache.arrow.vector.TimeStampMilliTZVector
import org.apache.arrow.vector.TimeStampMilliVector
import org.apache.arrow.vector.TimeStampNanoTZVector
import org.apache.arrow.vector.TimeStampNanoVector
import org.apache.arrow.vector.TimeStampSecTZVector
import org.apache.arrow.vector.TimeStampSecVector
import org.apache.arrow.vector.TinyIntVector
import org.apache.arrow.vector.VarBinaryVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.ipc.ReadChannel
import org.apache.arrow.vector.ipc.message.MessageSerializer
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.Types
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import shared.formatter.core.ColumnMeta
import shared.formatter.core.InMemoryRowIterable
import shared.formatter.core.RowIterable
import shared.formatter.types.LogicalType
import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigDecimal
import java.nio.channels.Channels
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Thrown when an Arrow column type is not in the supported v1 mapping. */
class UnsupportedArrowType(
    message: String,
) : RuntimeException(message)

/**
 * Reads an Arrow IPC stream into a fully-materialised [RowIterable].
 *
 * v1 reads everything into memory. Streaming row iteration without
 * materialisation would require coupling allocator lifetime to iterator
 * lifetime, which complicates the writer-side API. Worker batch sizes
 * are bounded by `ExecutionOptions.row_limit`, so memory footprint is
 * predictable in practice.
 */
internal object ArrowReader {
    fun read(bytes: ByteArray): RowIterable {
        if (bytes.isEmpty()) {
            return InMemoryRowIterable(columns = emptyList(), rows = emptyList())
        }
        // Probe the leading message: if it's not a Schema message the input is malformed.
        val allocator: BufferAllocator = RootAllocator(Long.MAX_VALUE)
        try {
            val input = ByteArrayInputStream(bytes)
            ArrowStreamReader(input, allocator).use { reader ->
                val root: VectorSchemaRoot = reader.vectorSchemaRoot
                val columns = root.schema.fields.map { it.toColumnMeta() }
                val rows = mutableListOf<Array<Any?>>()
                while (reader.loadNextBatch()) {
                    val rowCount = root.rowCount
                    val vectors = root.fieldVectors
                    for (r in 0 until rowCount) {
                        val row = arrayOfNulls<Any?>(vectors.size)
                        for (c in vectors.indices) {
                            row[c] = readCell(vectors[c], r)
                        }
                        rows.add(row)
                    }
                }
                return InMemoryRowIterable(columns = columns, rows = rows)
            }
        } catch (e: UnsupportedArrowType) {
            throw e
        } catch (e: IOException) {
            throw IOException("Malformed Arrow IPC stream: ${e.message}", e)
        } finally {
            allocator.close()
        }
    }

    /** Convenience: peek at the schema-message-only bytes without reading any batches. */
    fun schemaOnly(bytes: ByteArray): List<ColumnMeta> {
        if (bytes.isEmpty()) return emptyList()
        val ch = ReadChannel(Channels.newChannel(ByteArrayInputStream(bytes)))
        val schema = MessageSerializer.deserializeSchema(ch)
        return schema.fields.map { it.toColumnMeta() }
    }

    private fun Field.toColumnMeta(): ColumnMeta = ColumnMeta(name, this.toLogicalType(), nullable = isNullable)

    private fun Field.toLogicalType(): LogicalType =
        when (val t = type) {
            is ArrowType.Int ->
                when (t.bitWidth) {
                    8, 16, 32, 64 -> LogicalType.Int64
                    else -> throw UnsupportedArrowType("Int(${t.bitWidth}) not supported")
                }

            is ArrowType.FloatingPoint ->
                when (t.precision) {
                    FloatingPointPrecision.SINGLE, FloatingPointPrecision.DOUBLE -> LogicalType.Double
                    else -> throw UnsupportedArrowType("FloatingPoint(${t.precision}) not supported")
                }

            is ArrowType.Decimal -> LogicalType.Decimal(t.precision, t.scale)

            is ArrowType.Utf8 -> LogicalType.StringT
            is ArrowType.LargeUtf8 -> LogicalType.StringT
            is ArrowType.Bool -> LogicalType.Bool

            is ArrowType.Date ->
                when (t.unit) {
                    org.apache.arrow.vector.types.DateUnit.DAY -> LogicalType.Date
                    else -> throw UnsupportedArrowType("Date(${t.unit}) not supported")
                }

            is ArrowType.Timestamp ->
                if (t.timezone.isNullOrEmpty()) LogicalType.Timestamp else LogicalType.TimestampTz

            is ArrowType.Binary -> LogicalType.Bytes
            is ArrowType.LargeBinary -> LogicalType.Bytes
            is ArrowType.FixedSizeBinary -> LogicalType.Bytes

            is ArrowType.Null -> LogicalType.NullType

            else -> throw UnsupportedArrowType("Arrow type $t not supported by data-formatter")
        }

    private fun readCell(
        vector: FieldVector,
        row: Int,
    ): Any? {
        if (vector.isNull(row)) return null
        return when (vector) {
            is BigIntVector -> vector.get(row)
            is IntVector -> vector.get(row).toLong()
            is SmallIntVector -> vector.get(row).toLong()
            is TinyIntVector -> vector.get(row).toLong()

            is Float8Vector -> vector.get(row)
            is Float4Vector -> vector.get(row).toDouble()

            is DecimalVector -> vector.getObject(row) as BigDecimal

            is VarCharVector -> String(vector.get(row), Charsets.UTF_8)

            is BitVector -> vector.get(row) != 0

            is DateDayVector -> LocalDate.ofEpochDay(vector.get(row).toLong())

            is TimeStampSecVector -> LocalDateTime.ofEpochSecond(vector.get(row), 0, ZoneOffset.UTC)
            is TimeStampMilliVector -> Instant.ofEpochMilli(vector.get(row)).atZone(ZoneOffset.UTC).toLocalDateTime()
            is TimeStampMicroVector -> instantFromMicros(vector.get(row)).atZone(ZoneOffset.UTC).toLocalDateTime()
            is TimeStampNanoVector -> instantFromNanos(vector.get(row)).atZone(ZoneOffset.UTC).toLocalDateTime()

            is TimeStampSecTZVector -> Instant.ofEpochSecond(vector.get(row))
            is TimeStampMilliTZVector -> Instant.ofEpochMilli(vector.get(row))
            is TimeStampMicroTZVector -> instantFromMicros(vector.get(row))
            is TimeStampNanoTZVector -> instantFromNanos(vector.get(row))

            is VarBinaryVector -> vector.get(row)

            else -> {
                // Fall back through getObject() for any vector whose Java type
                // we can hand directly to the renderer (the renderer understands
                // a small number of primitive shapes; anything truly exotic
                // would have failed the type-mapping step above).
                val mt =
                    Types.getMinorTypeForArrowType(vector.field.type)
                throw UnsupportedArrowType(
                    "No row-reader for vector kind $mt — add one to ArrowReader.readCell",
                )
            }
        }
    }

    private fun instantFromMicros(micros: Long): Instant {
        val seconds = micros / 1_000_000L
        val nanoAdjustment = (micros % 1_000_000L) * 1_000L
        return Instant.ofEpochSecond(seconds, nanoAdjustment)
    }

    private fun instantFromNanos(nanos: Long): Instant {
        val seconds = nanos / 1_000_000_000L
        val nanoAdjustment = nanos % 1_000_000_000L
        return Instant.ofEpochSecond(seconds, nanoAdjustment)
    }
}
