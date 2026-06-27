package org.tatrman.kantheon.charon.endpoints

import java.math.BigDecimal
import java.sql.ResultSet
import org.apache.arrow.memory.BufferAllocator
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
import org.apache.arrow.vector.TinyIntVector
import org.apache.arrow.vector.VarBinaryVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.Schema

/**
 * A streaming [ArrowReader] over a JDBC `ResultSet` (charon/contracts.md §5
 * extract direction). Each [loadNextBatch] pulls up to [chunkRows] rows from
 * the cursor into the reader's reused [VectorSchemaRoot] — **bounded memory**,
 * no full materialisation (Stage 2.1 T5). The same root is reloaded in place
 * per batch (exactly as `ArrowStreamReader` does); `ArrowPipe` deep-copies
 * each batch on the target side, so reuse is safe.
 *
 * The Arrow [schema] is precomputed from the `ResultSetMetaData`
 * ([org.tatrman.kantheon.charon.core.DbTypeMapping.schemaFromMetadata]); this
 * reader only fills cells. [closeReadSource] closes the `ResultSet` and the
 * caller-supplied [onClose] (statement + connection return-to-pool).
 */
class JdbcArrowReader(
    allocator: BufferAllocator,
    private val rs: ResultSet,
    private val arrowSchema: Schema,
    private val chunkRows: Int,
    private val onClose: () -> Unit,
) : ArrowReader(allocator) {
    private var bytes: Long = 0

    override fun readSchema(): Schema = arrowSchema

    override fun bytesRead(): Long = bytes

    override fun loadNextBatch(): Boolean {
        val root = vectorSchemaRoot
        root.allocateNew()
        var rowIdx = 0
        while (rowIdx < chunkRows && rs.next()) {
            for (colIdx in 0 until root.fieldVectors.size) {
                setCell(root.fieldVectors[colIdx], colIdx + 1, rowIdx)
            }
            rowIdx++
        }
        root.rowCount = rowIdx
        if (rowIdx == 0) return false
        bytes += root.fieldVectors.sumOf { it.bufferSize.toLong() }
        return true
    }

    private fun setCell(
        vec: FieldVector,
        jdbcCol: Int,
        row: Int,
    ) {
        when (vec) {
            is TinyIntVector -> {
                val v = rs.getByte(jdbcCol)
                if (rs.wasNull()) vec.setNull(row) else vec.setSafe(row, v.toInt())
            }
            is SmallIntVector -> {
                val v = rs.getShort(jdbcCol)
                if (rs.wasNull()) vec.setNull(row) else vec.setSafe(row, v.toInt())
            }
            is IntVector -> {
                val v = rs.getInt(jdbcCol)
                if (rs.wasNull()) vec.setNull(row) else vec.setSafe(row, v)
            }
            is BigIntVector -> {
                val v = rs.getLong(jdbcCol)
                if (rs.wasNull()) vec.setNull(row) else vec.setSafe(row, v)
            }
            is Float4Vector -> {
                val v = rs.getFloat(jdbcCol)
                if (rs.wasNull()) vec.setNull(row) else vec.setSafe(row, v)
            }
            is Float8Vector -> {
                val v = rs.getDouble(jdbcCol)
                if (rs.wasNull()) vec.setNull(row) else vec.setSafe(row, v)
            }
            is DecimalVector -> {
                val v: BigDecimal? = rs.getBigDecimal(jdbcCol)
                // Align to the vector's fixed scale. Drivers may report a row
                // with more fractional digits than the column metadata declared
                // (e.g. unscaled NUMERIC); HALF_UP rounds rather than aborting
                // the whole move with an ArithmeticException.
                if (v ==
                    null
                ) {
                    vec.setNull(row)
                } else {
                    vec.setSafe(row, v.setScale(vec.scale, java.math.RoundingMode.HALF_UP))
                }
            }
            is BitVector -> {
                val v = rs.getBoolean(jdbcCol)
                if (rs.wasNull()) vec.setNull(row) else vec.setSafe(row, if (v) 1 else 0)
            }
            is VarCharVector -> {
                val v = rs.getString(jdbcCol)
                if (v == null) vec.setNull(row) else vec.setSafe(row, v.toByteArray(Charsets.UTF_8))
            }
            is DateDayVector -> {
                val v = rs.getDate(jdbcCol)
                if (v == null) vec.setNull(row) else vec.setSafe(row, (v.toLocalDate().toEpochDay()).toInt())
            }
            is TimeStampMicroVector -> {
                val v = rs.getTimestamp(jdbcCol)
                if (v == null) vec.setNull(row) else vec.setSafe(row, micros(v))
            }
            is TimeStampMicroTZVector -> {
                val v = rs.getTimestamp(jdbcCol)
                if (v == null) vec.setNull(row) else vec.setSafe(row, micros(v))
            }
            is VarBinaryVector -> {
                val v = rs.getBytes(jdbcCol)
                if (v == null) vec.setNull(row) else vec.setSafe(row, v)
            }
            else -> error("JdbcArrowReader: no cell setter for ${vec.javaClass.simpleName} (col $jdbcCol)")
        }
    }

    private fun micros(ts: java.sql.Timestamp): Long {
        // Microseconds since the epoch via Instant — correct for pre-1970
        // timestamps too (the old millis*1000 + nanos hand-math was
        // inconsistent for negative epochs). Instant.nano is always 0..1e9.
        val instant = ts.toInstant()
        return instant.epochSecond * 1_000_000 + instant.nano / 1_000
    }

    override fun closeReadSource() {
        try {
            rs.close()
        } finally {
            onClose()
        }
    }
}
