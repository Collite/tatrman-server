package shared.formatter.output

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.avro.generic.GenericRecord
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.avro.AvroParquetReader
import org.apache.parquet.io.DelegatingSeekableInputStream
import org.apache.parquet.io.InputFile
import org.apache.parquet.io.SeekableInputStream
import shared.formatter.DataFormatter
import shared.formatter.core.ColumnMeta
import shared.formatter.core.FormatOptions
import shared.formatter.core.InMemoryRowIterable
import shared.formatter.core.OutputFormat
import shared.formatter.types.LogicalType
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class ParquetWriterSpec :
    StringSpec({

        fun rowsOf(
            cols: List<ColumnMeta>,
            rows: List<Array<Any?>>,
        ) = InMemoryRowIterable(columns = cols, rows = rows)

        /** Reads a parquet byte array back into a list of `GenericRecord`s. */
        fun readParquet(bytes: ByteArray): List<GenericRecord> {
            val input = InMemoryInputFile(bytes)
            val out = mutableListOf<GenericRecord>()
            AvroParquetReader.builder<GenericRecord>(input).withConf(Configuration()).build().use { reader ->
                while (true) {
                    val rec = reader.read() ?: break
                    out.add(rec)
                }
            }
            return out
        }

        "PARQUET — header + two typed rows decode back into records with the right field types" {
            val cols =
                listOf(
                    ColumnMeta("id", LogicalType.Int64, nullable = false),
                    ColumnMeta("name", LogicalType.StringT, nullable = true),
                    ColumnMeta("active", LogicalType.Bool, nullable = true),
                    ColumnMeta("created_on", LogicalType.Date, nullable = true),
                )
            val rows =
                rowsOf(
                    cols,
                    listOf(
                        arrayOf<Any?>(1L, "Alice", true, LocalDate.of(2026, 5, 13)),
                        arrayOf<Any?>(2L, null, false, null),
                    ),
                )
            val out = ParquetWriter.write(rows, FormatOptions())
            out.rowsWritten shouldBe 2
            out.truncated shouldBe false
            out.bytes.size shouldNotBe 0

            val records = readParquet(out.bytes)
            records.size shouldBe 2

            records[0].get("id") shouldBe 1L
            records[0].get("name").toString() shouldBe "Alice"
            records[0].get("active") shouldBe true
            // Date encoded as days-since-epoch; verify the int value matches.
            records[0].get("created_on") shouldBe LocalDate.of(2026, 5, 13).toEpochDay().toInt()

            records[1].get("id") shouldBe 2L
            records[1].get("name") shouldBe null
            records[1].get("active") shouldBe false
            records[1].get("created_on") shouldBe null
        }

        "PARQUET — bytes start with PAR1 magic (parquet file signature)" {
            val cols = listOf(ColumnMeta("id", LogicalType.Int64, nullable = false))
            val rows = rowsOf(cols, listOf(arrayOf<Any?>(1L)))
            val out = ParquetWriter.write(rows, FormatOptions())
            // .parquet files start with the 4-byte magic "PAR1" (0x50 0x41 0x52 0x31).
            out.bytes[0] shouldBe 0x50.toByte()
            out.bytes[1] shouldBe 0x41.toByte()
            out.bytes[2] shouldBe 0x52.toByte()
            out.bytes[3] shouldBe 0x31.toByte()
        }

        "PARQUET — Double + Decimal + Timestamp round-trip through the writer" {
            val cols =
                listOf(
                    ColumnMeta("price", LogicalType.Double, nullable = false),
                    ColumnMeta("amount", LogicalType.Decimal(precision = 12, scale = 2), nullable = false),
                    ColumnMeta("created_at", LogicalType.Timestamp, nullable = false),
                )
            val rows =
                rowsOf(
                    cols,
                    listOf(
                        arrayOf<Any?>(19.95, BigDecimal("123.45"), LocalDateTime.of(2026, 5, 13, 9, 30, 0)),
                    ),
                )
            val out = ParquetWriter.write(rows, FormatOptions())
            val records = readParquet(out.bytes)
            records.size shouldBe 1
            records[0].get("price") shouldBe 19.95
            // Decimal lands as a ByteBuffer carrying the unscaled BigInteger bytes; the schema's
            // logical type carries scale=2. Recompose to verify round-trip.
            val raw = records[0].get("amount") as java.nio.ByteBuffer
            val bytes = ByteArray(raw.remaining())
            raw.get(bytes)
            BigDecimal(java.math.BigInteger(bytes), 2) shouldBe BigDecimal("123.45")
            // Timestamp encoded as epoch millis (UTC).
            val millis = records[0].get("created_at") as Long
            java.time.Instant
                .ofEpochMilli(millis)
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDateTime() shouldBe LocalDateTime.of(2026, 5, 13, 9, 30, 0)
        }

        "PARQUET — hideColumnsMatching is honoured (column dropped before writer sees rows)" {
            val cols =
                listOf(
                    ColumnMeta("id", LogicalType.Int64, nullable = false),
                    ColumnMeta("secret", LogicalType.StringT, nullable = true),
                    ColumnMeta("name", LogicalType.StringT, nullable = true),
                )
            val rows = rowsOf(cols, listOf(arrayOf<Any?>(1L, "shh", "Alice")))
            val pq =
                DataFormatter
                    .convert(
                        rows = rows,
                        output = OutputFormat.PARQUET,
                        options = FormatOptions(hideColumnsMatching = listOf(Regex("^secret\$"))),
                    ).bytes

            val records = readParquet(pq)
            val schemaFields = records[0].schema.fields.map { it.name() }
            schemaFields shouldBe listOf("id", "name")
        }

        "PARQUET — rowLimit clips rows and sets truncated=true (silent)" {
            val cols = listOf(ColumnMeta("id", LogicalType.Int64, nullable = false))
            val rows = rowsOf(cols, (1..10).map { arrayOf<Any?>(it.toLong()) })
            val out = ParquetWriter.write(rows, FormatOptions(rowLimit = 3))
            out.rowsWritten shouldBe 3
            out.truncated shouldBe true
            val records = readParquet(out.bytes)
            records.size shouldBe 3
        }

        "PARQUET — output passes the OutputFormat.binary contract" {
            OutputFormat.PARQUET.binary shouldBe true
            OutputFormat.PARQUET.mediaType shouldBe "application/vnd.apache.parquet"
        }
    })

/** In-memory [InputFile] mirror of the writer's `InMemoryOutputFile` — used by tests to round-trip. */
private class InMemoryInputFile(
    private val bytes: ByteArray,
) : InputFile {
    override fun getLength(): Long = bytes.size.toLong()

    override fun newStream(): SeekableInputStream {
        val bais = ByteArrayInputStream(bytes)
        return object : DelegatingSeekableInputStream(bais) {
            private var pos: Long = 0L

            override fun getPos(): Long = pos

            override fun seek(newPos: Long) {
                bais.reset()
                bais.skip(newPos)
                pos = newPos
            }

            override fun read(): Int {
                val v = bais.read()
                if (v >= 0) pos++
                return v
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                val n = bais.read(b, off, len)
                if (n > 0) pos += n.toLong()
                return n
            }
        }
    }
}
