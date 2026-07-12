// SPDX-License-Identifier: Apache-2.0
package shared.formatter.output

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import shared.formatter.core.ColumnMeta
import shared.formatter.core.FormatOptions
import shared.formatter.core.InMemoryRowIterable
import shared.formatter.core.MdAlign
import shared.formatter.types.LogicalType

class WritersSpec :
    StringSpec({

        fun rowsOf(
            cols: List<ColumnMeta>,
            rows: List<Array<Any?>>,
        ) = InMemoryRowIterable(columns = cols, rows = rows)

        val twoCol =
            listOf(
                ColumnMeta("id", LogicalType.Int64, nullable = true),
                ColumnMeta("name", LogicalType.StringT, nullable = true),
            )

        "JSON — header-only / zero rows produces []" {
            val out = JsonWriter.write(rowsOf(twoCol, emptyList()), FormatOptions())
            String(out.bytes, Charsets.UTF_8) shouldBe "[]"
            out.rowsWritten shouldBe 0
            out.truncated shouldBe false
        }

        "CSV — header-only / zero rows" {
            val out = CsvWriter.write(rowsOf(twoCol, emptyList()), FormatOptions())
            String(out.bytes, Charsets.UTF_8) shouldBe "id,name\r\n"
            out.rowsWritten shouldBe 0
        }

        "TSV — header-only / zero rows" {
            val out = TsvWriter.write(rowsOf(twoCol, emptyList()), FormatOptions())
            String(out.bytes, Charsets.UTF_8) shouldBe "id\tname\n"
        }

        "MARKDOWN — header + alignment rows even with zero data rows" {
            val out = MarkdownWriter.write(rowsOf(twoCol, emptyList()), FormatOptions())
            String(out.bytes, Charsets.UTF_8) shouldBe "| id | name |\n| ---: | :--- |\n"
        }

        "JSON — null cell becomes JSON null" {
            val rows = rowsOf(twoCol, listOf(arrayOf<Any?>(1L, null), arrayOf<Any?>(null, "x")))
            val out = JsonWriter.write(rows, FormatOptions())
            String(out.bytes, Charsets.UTF_8) shouldBe """[{"id":1,"name":null},{"id":null,"name":"x"}]"""
        }

        "CSV — embedded comma / quote / CR / LF triggers quoting" {
            val cols = listOf(ColumnMeta("v", LogicalType.StringT, nullable = true))
            val rows =
                rowsOf(
                    cols,
                    listOf(
                        arrayOf<Any?>("plain"),
                        arrayOf<Any?>("with,comma"),
                        arrayOf<Any?>("with\"quote"),
                        arrayOf<Any?>("with\nlf"),
                    ),
                )
            val out = CsvWriter.write(rows, FormatOptions())
            String(out.bytes, Charsets.UTF_8) shouldBe
                "v\r\nplain\r\n\"with,comma\"\r\n\"with\"\"quote\"\r\n\"with\nlf\"\r\n"
        }

        "TSV — embedded tab/newline/backslash escapes to literal sequences" {
            val cols = listOf(ColumnMeta("v", LogicalType.StringT, nullable = true))
            val rows =
                rowsOf(
                    cols,
                    listOf(
                        arrayOf<Any?>("plain"),
                        arrayOf<Any?>("a\tb"),
                        arrayOf<Any?>("a\nb"),
                        arrayOf<Any?>("a\\b"),
                    ),
                )
            val out = TsvWriter.write(rows, FormatOptions())
            String(out.bytes, Charsets.UTF_8) shouldBe
                "v\nplain\na\\tb\na\\nb\na\\\\b\n"
        }

        "MARKDOWN — pipe escaping, numeric vs text alignment, override wins" {
            val cols =
                listOf(
                    ColumnMeta("n", LogicalType.Int64, nullable = true),
                    ColumnMeta("note", LogicalType.StringT, nullable = true),
                )
            val rows = rowsOf(cols, listOf(arrayOf<Any?>(1L, "has | pipe")))
            val opts = FormatOptions(mdAlignmentOverrides = mapOf("note" to MdAlign.CENTER))
            val out = MarkdownWriter.write(rows, opts)
            String(out.bytes, Charsets.UTF_8) shouldBe
                "| n | note |\n| ---: | :---: |\n| 1 | has \\| pipe |\n"
        }

        "MARKDOWN — null becomes literal 'null'" {
            val cols = listOf(ColumnMeta("v", LogicalType.StringT, nullable = true))
            val rows = rowsOf(cols, listOf(arrayOf<Any?>(null)))
            val out = MarkdownWriter.write(rows, FormatOptions())
            String(out.bytes, Charsets.UTF_8) shouldBe "| v |\n| :--- |\n| null |\n"
        }

        "ISO-8601 timestamp output with timezone applied" {
            val cols = listOf(ColumnMeta("ts", LogicalType.TimestampTz, nullable = true))
            val instant =
                java.time.OffsetDateTime
                    .of(2026, 5, 3, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
                    .toInstant()
            val rows = rowsOf(cols, listOf(arrayOf<Any?>(instant)))
            val out = CsvWriter.write(rows, FormatOptions(timestampZone = "Europe/Prague"))
            String(out.bytes, Charsets.UTF_8) shouldBe "ts\r\n2026-05-03T14:00:00+02:00\r\n"
        }

        "Truncation — rowLimit clips, truncated flag set" {
            val cols = listOf(ColumnMeta("n", LogicalType.Int64, nullable = true))
            val rows = rowsOf(cols, (1..25).map { arrayOf<Any?>(it.toLong()) })
            val opts = FormatOptions(rowLimit = 10)
            val csv = CsvWriter.write(rows, opts)
            csv.rowsWritten shouldBe 10
            csv.truncated shouldBe true
            val tsv = TsvWriter.write(rows, opts)
            tsv.rowsWritten shouldBe 10
            tsv.truncated shouldBe true
            val md = MarkdownWriter.write(rows, opts)
            md.rowsWritten shouldBe 10
            md.truncated shouldBe true
            val json = JsonWriter.write(rows, opts)
            json.rowsWritten shouldBe 10
            json.truncated shouldBe true
        }
    })
