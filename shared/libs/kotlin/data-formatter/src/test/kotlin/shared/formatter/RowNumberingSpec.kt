package shared.formatter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import shared.formatter.core.ColumnMeta
import shared.formatter.core.FormatOptions
import shared.formatter.core.InMemoryRowIterable
import shared.formatter.core.OutputFormat
import shared.formatter.core.RowNumbering
import shared.formatter.input.ArrowFixtures
import shared.formatter.snapshot.SnapshotIo
import shared.formatter.types.LogicalType

class RowNumberingSpec :
    StringSpec({

        "ONE_BASED — orders fixture snapshots per format" {
            val arrow = ArrowFixtures.ordersFixture()
            for (fmt in OutputFormat.values()) {
                // Skip binary formats before running the writer — their bytes are non-text and
                // pin-by-byte snapshots are too brittle (timestamps, compression vary by call).
                // Binary formats have their own coverage in XlsxWriterSpec / ParquetWriterSpec.
                if (fmt.binary) continue
                val r =
                    DataFormatter.fromArrow(
                        arrow,
                        fmt,
                        FormatOptions(rowNumbering = RowNumbering.ONE_BASED, timestampZone = "Z"),
                    )
                val ext =
                    when (fmt) {
                        OutputFormat.JSON -> "json"
                        OutputFormat.CSV -> "csv"
                        OutputFormat.TSV -> "tsv"
                        OutputFormat.MARKDOWN -> "md"
                        OutputFormat.XLSX, OutputFormat.PARQUET -> error("binary formats are pre-filtered above")
                    }
                SnapshotIo.assertEqualsOrRegenerate("snapshots/g3/orders-numbered.$ext", r.bytes)
            }
        }

        "Hide + number combined — orders fixture snapshots per format" {
            val arrow = ArrowFixtures.ordersFixture()
            val opts =
                FormatOptions(
                    hideColumnsMatching = listOf(Regex("^id$")),
                    rowNumbering = RowNumbering.ONE_BASED,
                    timestampZone = "Z",
                )
            for (fmt in OutputFormat.values()) {
                if (fmt.binary) continue
                val r = DataFormatter.fromArrow(arrow, fmt, opts)
                val ext =
                    when (fmt) {
                        OutputFormat.JSON -> "json"
                        OutputFormat.CSV -> "csv"
                        OutputFormat.TSV -> "tsv"
                        OutputFormat.MARKDOWN -> "md"
                        OutputFormat.XLSX, OutputFormat.PARQUET -> error("binary formats are pre-filtered above")
                    }
                SnapshotIo.assertEqualsOrRegenerate("snapshots/g3/orders-hide-number.$ext", r.bytes)
            }
        }

        "Truncated + numbered — rowLimit=3 against 10-row source clips to 1,2,3" {
            val cols = listOf(ColumnMeta("v", LogicalType.StringT, nullable = true))
            val rows = (1..10).map { arrayOf<Any?>("row_$it") }
            val opts =
                FormatOptions(
                    rowLimit = 3,
                    rowNumbering = RowNumbering.ONE_BASED,
                )

            InMemoryRowIterable(columns = cols, rows = rows).use {
                val csv = DataFormatter.convert(it, OutputFormat.CSV, opts)
                csv.rowCount shouldBe 3
                csv.truncated shouldBe true
                String(csv.bytes, Charsets.UTF_8) shouldBe
                    "#,v\r\n1,row_1\r\n2,row_2\r\n3,row_3\r\n"
            }
        }

        "Markdown — # column right-aligned because INT64" {
            val cols = listOf(ColumnMeta("v", LogicalType.StringT, nullable = true))
            val rows = listOf(arrayOf<Any?>("a"), arrayOf<Any?>("b"))
            val opts = FormatOptions(rowNumbering = RowNumbering.ONE_BASED)
            InMemoryRowIterable(columns = cols, rows = rows).use {
                val md = DataFormatter.convert(it, OutputFormat.MARKDOWN, opts)
                String(md.bytes, Charsets.UTF_8) shouldBe
                    "| # | v |\n| ---: | :--- |\n| 1 | a |\n| 2 | b |\n"
            }
        }

        "FormattedResult.columns includes the # column when numbering on" {
            val cols = listOf(ColumnMeta("v", LogicalType.StringT, nullable = true))
            InMemoryRowIterable(columns = cols, rows = listOf(arrayOf<Any?>("a"))).use {
                val r =
                    DataFormatter.convert(
                        it,
                        OutputFormat.JSON,
                        FormatOptions(rowNumbering = RowNumbering.ONE_BASED),
                    )
                r.columnCount shouldBe 2
                r.columns[0].name shouldBe "#"
                r.columns[0].logicalType shouldBe LogicalType.Int64
                r.columns[1].name shouldBe "v"
            }
        }

        "JSON — # field appears in each object" {
            val cols = listOf(ColumnMeta("v", LogicalType.StringT, nullable = true))
            InMemoryRowIterable(columns = cols, rows = listOf(arrayOf<Any?>("a"), arrayOf<Any?>("b"))).use {
                val r =
                    DataFormatter.convert(
                        it,
                        OutputFormat.JSON,
                        FormatOptions(rowNumbering = RowNumbering.ONE_BASED),
                    )
                String(r.bytes, Charsets.UTF_8) shouldBe """[{"#":1,"v":"a"},{"#":2,"v":"b"}]"""
            }
        }

        // DF-F04 / Phase 08 D4 — explicit key-order guarantee: when row numbering is on, `#` is
        // ALWAYS the first key in every row object regardless of other column names/order.
        // Locked in so a future refactor (e.g. switching JSON impls or column-list construction)
        // can't silently regress it.
        "JSON — # is the LEADING key in every row object regardless of other column names" {
            val cols =
                listOf(
                    ColumnMeta("zzz", LogicalType.StringT, nullable = true),
                    ColumnMeta("aaa", LogicalType.Int64, nullable = true),
                    ColumnMeta("mmm", LogicalType.StringT, nullable = true),
                )
            InMemoryRowIterable(
                columns = cols,
                rows = listOf(arrayOf<Any?>("z1", 1L, "m1"), arrayOf<Any?>("z2", 2L, "m2")),
            ).use {
                val r =
                    DataFormatter.convert(
                        it,
                        OutputFormat.JSON,
                        FormatOptions(rowNumbering = RowNumbering.ONE_BASED),
                    )
                val raw = String(r.bytes, Charsets.UTF_8)
                // Each row object's first key is `#`. Assert on the raw bytes (not a parsed model
                // that would re-order them) so the guarantee binds at the wire.
                val rowObjects =
                    raw
                        .trim()
                        .removePrefix("[")
                        .removeSuffix("]")
                        .split("},{")
                rowObjects.size shouldBe 2
                rowObjects.all { it.replace("{", "").startsWith("\"#\":") } shouldBe true
            }
        }

        "JSON — when row numbering is NONE, no # column is emitted" {
            val cols = listOf(ColumnMeta("v", LogicalType.StringT, nullable = true))
            InMemoryRowIterable(columns = cols, rows = listOf(arrayOf<Any?>("a"))).use {
                val r =
                    DataFormatter.convert(
                        it,
                        OutputFormat.JSON,
                        FormatOptions(rowNumbering = RowNumbering.NONE),
                    )
                String(r.bytes, Charsets.UTF_8) shouldBe """[{"v":"a"}]"""
            }
        }
    })
