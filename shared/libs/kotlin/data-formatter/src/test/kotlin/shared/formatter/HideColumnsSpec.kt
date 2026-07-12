// SPDX-License-Identifier: Apache-2.0
package shared.formatter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import shared.formatter.core.ColumnMeta
import shared.formatter.core.FormatOptions
import shared.formatter.core.InMemoryRowIterable
import shared.formatter.core.OutputFormat
import shared.formatter.types.LogicalType

class HideColumnsSpec :
    StringSpec({

        val cols =
            listOf(
                ColumnMeta("ID", LogicalType.Int64, nullable = true),
                ColumnMeta("ID_FOO", LogicalType.Int64, nullable = true),
                ColumnMeta("NAZEV", LogicalType.StringT, nullable = true),
                ColumnMeta("AMOUNT", LogicalType.Decimal(10, 2), nullable = true),
            )
        val rows =
            listOf(
                arrayOf<Any?>(1L, 100L, "Aaa", java.math.BigDecimal("12.50")),
                arrayOf<Any?>(2L, 200L, "Bbb", java.math.BigDecimal("99.99")),
            )

        fun source() = InMemoryRowIterable(columns = cols, rows = rows)

        "single regex hides matching columns across all formats" {
            val opts = FormatOptions(hideColumnsMatching = listOf(Regex("^ID")))

            for (fmt in OutputFormat.values()) {
                source().use {
                    val r = DataFormatter.convert(it, fmt, opts)
                    r.columnCount shouldBe 2
                    r.columns.map { c -> c.name } shouldBe listOf("NAZEV", "AMOUNT")
                }
            }
        }

        "multiple regexes — all matches hidden" {
            val opts =
                FormatOptions(
                    hideColumnsMatching = listOf(Regex("^ID"), Regex("^AMOUNT$")),
                )
            source().use {
                val r = DataFormatter.convert(it, OutputFormat.MARKDOWN, opts)
                r.columnCount shouldBe 1
                r.columns[0].name shouldBe "NAZEV"
            }
        }

        "no matching regex — output unchanged" {
            val opts = FormatOptions(hideColumnsMatching = listOf(Regex("^NOPE")))
            source().use {
                val r = DataFormatter.convert(it, OutputFormat.CSV, opts)
                r.columnCount shouldBe 4
                r.columns.map { c -> c.name } shouldBe listOf("ID", "ID_FOO", "NAZEV", "AMOUNT")
            }
        }

        "all hidden — header-only output for CSV/TSV/MD; [] for JSON" {
            val opts = FormatOptions(hideColumnsMatching = listOf(Regex(".*")))

            source().use {
                val csv = DataFormatter.convert(it, OutputFormat.CSV, opts)
                csv.columnCount shouldBe 0
                String(csv.bytes, Charsets.UTF_8) shouldBe "\r\n"
            }
            source().use {
                val tsv = DataFormatter.convert(it, OutputFormat.TSV, opts)
                tsv.columnCount shouldBe 0
                String(tsv.bytes, Charsets.UTF_8) shouldBe "\n"
            }
            source().use {
                val md = DataFormatter.convert(it, OutputFormat.MARKDOWN, opts)
                md.columnCount shouldBe 0
                String(md.bytes, Charsets.UTF_8) shouldBe "|  |\n|  |\n"
            }
            source().use {
                val json = DataFormatter.convert(it, OutputFormat.JSON, opts)
                json.columnCount shouldBe 0
                String(json.bytes, Charsets.UTF_8) shouldBe "[]"
            }
        }

        "row values are projected, not just headers" {
            val opts = FormatOptions(hideColumnsMatching = listOf(Regex("^ID")))
            source().use {
                val csv = DataFormatter.convert(it, OutputFormat.CSV, opts)
                String(csv.bytes, Charsets.UTF_8) shouldBe
                    "NAZEV,AMOUNT\r\nAaa,12.50\r\nBbb,99.99\r\n"
            }
        }
    })
