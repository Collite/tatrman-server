// SPDX-License-Identifier: Apache-2.0
package shared.formatter.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import shared.formatter.types.LogicalType

class FormatTypesSpec :
    StringSpec({

        "OutputFormat carries media types" {
            OutputFormat.JSON.mediaType shouldBe "application/json; charset=utf-8"
            OutputFormat.CSV.mediaType shouldBe "text/csv; charset=utf-8"
            OutputFormat.TSV.mediaType shouldBe "text/tab-separated-values; charset=utf-8"
            OutputFormat.MARKDOWN.mediaType shouldBe "text/markdown; charset=utf-8"
        }

        "InputFormat enum has expected values" {
            InputFormat.values().toList() shouldContainExactly listOf(InputFormat.ARROW_IPC, InputFormat.JSON_ROWS)
        }

        "MdAlign enum has expected values" {
            MdAlign.values().toList() shouldContainExactly listOf(MdAlign.LEFT, MdAlign.RIGHT, MdAlign.CENTER)
        }

        "RowNumbering enum has expected values" {
            RowNumbering.values().toList() shouldContainExactly listOf(RowNumbering.NONE, RowNumbering.ONE_BASED)
        }

        "FormatOptions defaults preserve legacy behaviour" {
            val opts = FormatOptions()
            opts.rowLimit shouldBe null
            opts.truncateSilently shouldBe true
            opts.mdAlignmentOverrides shouldBe emptyMap()
            opts.timestampZone shouldBe "Z"
            opts.hideColumnsMatching shouldBe emptyList()
            opts.rowNumbering shouldBe RowNumbering.NONE
        }

        "ColumnMeta is a value type" {
            val a = ColumnMeta("name", LogicalType.StringT, nullable = true)
            val b = ColumnMeta("name", LogicalType.StringT, nullable = true)
            a shouldBe b
        }

        "LogicalType sealed hierarchy includes all expected variants" {
            val all =
                listOf(
                    LogicalType.Int64,
                    LogicalType.Double,
                    LogicalType.Decimal(precision = 18, scale = 4),
                    LogicalType.StringT,
                    LogicalType.Bool,
                    LogicalType.Date,
                    LogicalType.Timestamp,
                    LogicalType.TimestampTz,
                    LogicalType.Bytes,
                    LogicalType.NullType,
                )
            // Each is distinct.
            all.toSet().size shouldBe all.size
        }

        "Decimal carries precision and scale" {
            val d = LogicalType.Decimal(precision = 38, scale = 10)
            d.precision shouldBe 38
            d.scale shouldBe 10
            d shouldBe LogicalType.Decimal(precision = 38, scale = 10)
        }

        "FormattedResult exposes a defensive copy of bytes" {
            val original = byteArrayOf(1, 2, 3)
            val r =
                FormattedResult(
                    bytes = original,
                    mediaType = "text/plain",
                    rowCount = 0,
                    columnCount = 0,
                    truncated = false,
                    columns = emptyList(),
                )
            original[0] = 99
            r.bytes shouldBe byteArrayOf(1, 2, 3)
            // Mutating the returned copy doesn't affect the result.
            r.bytes.also { it[0] = 42 }
            r.bytes shouldBe byteArrayOf(1, 2, 3)
        }

        "FormattedResult equality is structural over bytes + metadata" {
            val a =
                FormattedResult(byteArrayOf(1, 2), "x", 1, 1, false, emptyList())
            val b =
                FormattedResult(byteArrayOf(1, 2), "x", 1, 1, false, emptyList())
            val c =
                FormattedResult(byteArrayOf(1, 2, 3), "x", 1, 1, false, emptyList())
            (a == b) shouldBe true
            (a == c) shouldBe false
            a.hashCode() shouldBe b.hashCode()
        }
    })
