// SPDX-License-Identifier: Apache-2.0
package shared.formatter.output

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.OutputFile
import org.apache.parquet.io.PositionOutputStream
import shared.formatter.core.ColumnMeta
import shared.formatter.core.FormatOptions
import shared.formatter.core.RowIterable
import shared.formatter.types.LogicalType
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Phase 08 D3 / DF-F03 — Parquet writer.
 *
 * Uses `parquet-avro`'s `AvroParquetWriter` with a per-call schema derived from the column
 * metadata. The writer streams rows to an in-memory [InMemoryOutputFile] so the bytes are
 * available without touching the filesystem. SNAPPY compression by default (well-supported on
 * the read side and ~1.5–2× smaller than uncompressed).
 *
 * Type mapping (LogicalType → Avro → Parquet):
 *   - `Int64` → `long`
 *   - `Double` → `double`
 *   - `Decimal(p,s)` → Avro `bytes` with the `decimal(p,s)` logical type; stored as
 *     `BigDecimal.unscaledValue.toByteArray()`. Precision and scale flow through to the Parquet
 *     `DECIMAL` annotation via Avro's logical type carrier.
 *   - `StringT` → `string`
 *   - `Bool` → `boolean`
 *   - `Date` → Avro `int` with logical type `date` (days since epoch).
 *   - `Timestamp` → Avro `long` with logical type `timestamp-millis` (local time, no zone).
 *   - `TimestampTz` → Avro `long` with logical type `timestamp-millis` (UTC instant; if the
 *     column carried a non-UTC zone via [FormatOptions.timestampZone] the value is converted to
 *     that zone's wall-clock millis first to preserve display semantics).
 *   - `Bytes` → `bytes`
 *   - `NullType` → Avro `null` only (column is always nullable in this case).
 *
 * Nullability is honoured: a nullable column emits a `union[null, T]` Avro type so the writer
 * can encode null values without throwing. The header / sheet-name concepts from `XlsxWriter`
 * don't apply — Parquet is columnar by construction.
 *
 * Header localisation and value-label substitution are NOT applied here: Parquet is a
 * machine-consumption format. Downstream tools read column names from the schema; substituting
 * codes would silently destroy the underlying data. The format options that DO apply are
 * [FormatOptions.rowLimit] (clipped + `truncated=true`), [FormatOptions.hideColumnsMatching]
 * (applied at the `Projection.hideColumns` stage before this writer sees the rows), and the
 * timestamp zone for TimestampTz.
 */
internal object ParquetWriter {
    fun write(
        rows: RowIterable,
        opts: FormatOptions,
    ): WriteOutcome {
        val cols = rows.columns
        if (cols.isEmpty()) {
            // No columns → empty result; emit a header-only schema with no records.
            return WriteOutcome(bytes = ByteArray(0), rowsWritten = 0, truncated = false, columns = cols)
        }
        val schema = buildSchema(cols)
        val outputFile = InMemoryOutputFile()

        val conf = Configuration()
        val writer =
            AvroParquetWriter
                .builder<GenericRecord>(outputFile)
                .withSchema(schema)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .withConf(conf)
                .build()

        var written = 0
        var truncated = false
        val limit = opts.rowLimit
        try {
            val it = rows.iterator()
            while (it.hasNext()) {
                if (limit != null && written >= limit) {
                    if (!opts.truncateSilently) error("rowLimit ($limit) exceeded")
                    truncated = true
                    break
                }
                val row = it.next()
                val record = GenericData.Record(schema)
                for (i in cols.indices) {
                    record.put(cols[i].name, encodeCell(row[i], cols[i], opts))
                }
                writer.write(record)
                written++
            }
        } finally {
            writer.close()
        }

        return WriteOutcome(
            bytes = outputFile.toByteArray(),
            rowsWritten = written,
            truncated = truncated,
            columns = cols,
        )
    }

    private fun buildSchema(cols: List<ColumnMeta>): Schema {
        val record = Schema.createRecord("Result", null, "shared.formatter", false)
        val fields =
            cols.map { col ->
                val raw = avroTypeFor(col.logicalType)
                val typed = if (col.nullable) Schema.createUnion(Schema.create(Schema.Type.NULL), raw) else raw
                // Nullable fields get a default of null so the writer accepts missing values;
                // required fields have no default (Avro rejects `null` as a default for non-union
                // primitive types).
                if (col.nullable) {
                    Schema.Field(col.name, typed, null, org.apache.avro.JsonProperties.NULL_VALUE)
                } else {
                    Schema.Field(col.name, typed, null, null as Any?)
                }
            }
        record.setFields(fields)
        return record
    }

    private fun avroTypeFor(t: LogicalType): Schema =
        when (t) {
            LogicalType.Int64 -> Schema.create(Schema.Type.LONG)
            LogicalType.Double -> Schema.create(Schema.Type.DOUBLE)
            is LogicalType.Decimal -> {
                val s = Schema.create(Schema.Type.BYTES)
                org.apache.avro.LogicalTypes
                    .decimal(t.precision, t.scale)
                    .addToSchema(s)
                s
            }
            LogicalType.StringT -> Schema.create(Schema.Type.STRING)
            LogicalType.Bool -> Schema.create(Schema.Type.BOOLEAN)
            LogicalType.Date -> {
                val s = Schema.create(Schema.Type.INT)
                org.apache.avro.LogicalTypes
                    .date()
                    .addToSchema(s)
                s
            }
            LogicalType.Timestamp, LogicalType.TimestampTz -> {
                val s = Schema.create(Schema.Type.LONG)
                org.apache.avro.LogicalTypes
                    .timestampMillis()
                    .addToSchema(s)
                s
            }
            LogicalType.Bytes -> Schema.create(Schema.Type.BYTES)
            LogicalType.NullType -> Schema.create(Schema.Type.NULL)
        }

    private fun encodeCell(
        value: Any?,
        col: ColumnMeta,
        opts: FormatOptions,
    ): Any? {
        if (value == null || col.logicalType == LogicalType.NullType) return null
        return when (col.logicalType) {
            LogicalType.Int64 -> coerceLong(value)
            LogicalType.Double -> coerceDouble(value)
            is LogicalType.Decimal -> {
                val bd = coerceBigDecimal(value).setScale((col.logicalType).scale)
                ByteBuffer.wrap(bd.unscaledValue().toByteArray())
            }
            LogicalType.StringT -> value.toString()
            LogicalType.Bool -> coerceBoolean(value)
            LogicalType.Date -> toLocalDate(value).toEpochDay().toInt()
            LogicalType.Timestamp -> toLocalDateTime(value).toInstant(ZoneOffset.UTC).toEpochMilli()
            LogicalType.TimestampTz -> toZonedDateTime(value, opts.timestampZone).toInstant().toEpochMilli()
            LogicalType.Bytes -> ByteBuffer.wrap(coerceBytes(value))
            LogicalType.NullType -> null
        }
    }

    private fun coerceLong(v: Any): Long =
        when (v) {
            is Long -> v
            is Number -> v.toLong()
            is String -> v.toLong()
            else -> error("Cannot coerce ${v::class.qualifiedName} to Long")
        }

    private fun coerceDouble(v: Any): Double =
        when (v) {
            is Number -> v.toDouble()
            is String -> v.toDouble()
            else -> error("Cannot coerce ${v::class.qualifiedName} to Double")
        }

    private fun coerceBigDecimal(v: Any): BigDecimal =
        when (v) {
            is BigDecimal -> v
            is BigInteger -> BigDecimal(v)
            is Number -> BigDecimal.valueOf(v.toDouble())
            is String -> BigDecimal(v)
            else -> error("Cannot coerce ${v::class.qualifiedName} to BigDecimal")
        }

    private fun coerceBoolean(v: Any): Boolean =
        when (v) {
            is Boolean -> v
            is Number -> v.toLong() != 0L
            is String -> v.toBoolean()
            else -> error("Cannot coerce ${v::class.qualifiedName} to Boolean")
        }

    private fun coerceBytes(v: Any): ByteArray =
        when (v) {
            is ByteArray -> v
            is String -> v.toByteArray(Charsets.UTF_8)
            else -> error("Cannot coerce ${v::class.qualifiedName} to ByteArray")
        }

    private fun toLocalDate(v: Any): LocalDate =
        when (v) {
            is LocalDate -> v
            is java.sql.Date -> v.toLocalDate()
            is Number -> LocalDate.ofEpochDay(v.toLong())
            is String -> LocalDate.parse(v)
            else -> error("Cannot render ${v::class.qualifiedName} as DATE")
        }

    private fun toLocalDateTime(v: Any): LocalDateTime =
        when (v) {
            is LocalDateTime -> v
            is java.sql.Timestamp -> v.toLocalDateTime()
            is Instant -> v.atZone(ZoneOffset.UTC).toLocalDateTime()
            is Number -> Instant.ofEpochMilli(v.toLong()).atZone(ZoneOffset.UTC).toLocalDateTime()
            is String -> LocalDateTime.parse(v)
            else -> error("Cannot render ${v::class.qualifiedName} as TIMESTAMP")
        }

    private fun toZonedDateTime(
        v: Any,
        zoneId: String,
    ): ZonedDateTime {
        val zone = ZoneId.of(zoneId)
        return when (v) {
            is OffsetDateTime -> v.atZoneSameInstant(zone)
            is ZonedDateTime -> v.withZoneSameInstant(zone)
            is Instant -> v.atZone(zone)
            is Number -> Instant.ofEpochMilli(v.toLong()).atZone(zone)
            is String -> OffsetDateTime.parse(v).atZoneSameInstant(zone)
            else -> error("Cannot render ${v::class.qualifiedName} as TIMESTAMP_TZ")
        }
    }
}

/**
 * Phase 08 D3 — in-memory [OutputFile] for Parquet. `parquet-hadoop`'s `LocalOutputFile` only
 * targets a real filesystem path; for the v1 use case (return bytes from a request handler)
 * a memory-backed buffer keeps the writer off-disk. Implementation is small enough to live
 * alongside the writer.
 */
private class InMemoryOutputFile : OutputFile {
    private val baos = ByteArrayOutputStream()

    override fun create(blockSizeHint: Long): PositionOutputStream = wrap(baos)

    override fun createOrOverwrite(blockSizeHint: Long): PositionOutputStream {
        baos.reset()
        return wrap(baos)
    }

    override fun supportsBlockSize(): Boolean = false

    override fun defaultBlockSize(): Long = 0L

    fun toByteArray(): ByteArray = baos.toByteArray()

    private fun wrap(out: ByteArrayOutputStream): PositionOutputStream =
        object : PositionOutputStream() {
            private var pos: Long = 0L

            override fun getPos(): Long = pos

            override fun write(b: Int) {
                out.write(b)
                pos++
            }

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) {
                out.write(b, off, len)
                pos += len
            }

            override fun flush() = out.flush()

            override fun close() {
                // Don't close the underlying ByteArrayOutputStream — the writer calls close() and
                // we still need to read the bytes afterwards.
                flush()
            }
        }
}
