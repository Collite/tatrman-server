package shared.formatter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import shared.formatter.core.ColumnMeta
import shared.formatter.core.FormatOptions
import shared.formatter.core.InMemoryRowIterable
import shared.formatter.core.OutputFormat
import shared.formatter.input.ArrowFixtures
import shared.formatter.types.LogicalType

class DataFormatterFacadeSpec :
    StringSpec({

        "fromArrow → JSON, CSV, TSV, MARKDOWN" {
            val arrow = ArrowFixtures.ordersFixture()
            for (fmt in OutputFormat.values()) {
                val r = DataFormatter.fromArrow(arrow, fmt)
                r.rowCount shouldBe 5
                r.columnCount shouldBe 4
                r.mediaType shouldBe fmt.mediaType
                r.truncated shouldBe false
                r.columns.map { it.name } shouldBe listOf("id", "customer", "amount", "ordered_at")
            }
        }

        "fromJsonRows → JSON, CSV, TSV, MARKDOWN" {
            val bytes = """[{"id":1,"name":"Alice"},{"id":2,"name":"Bob"}]""".toByteArray(Charsets.UTF_8)
            for (fmt in OutputFormat.values()) {
                val r = DataFormatter.fromJsonRows(bytes, fmt)
                r.rowCount shouldBe 2
                r.columnCount shouldBe 2
                r.mediaType shouldBe fmt.mediaType
            }
        }

        "convert with InMemoryRowIterable propagates columnCount and rowCount" {
            val cols =
                listOf(
                    ColumnMeta("a", LogicalType.Int64, nullable = true),
                    ColumnMeta("b", LogicalType.StringT, nullable = true),
                )
            val iter =
                InMemoryRowIterable(
                    columns = cols,
                    rows = listOf(arrayOf<Any?>(1L, "x"), arrayOf<Any?>(2L, "y")),
                )
            iter.use {
                val r = DataFormatter.convert(it, OutputFormat.MARKDOWN, FormatOptions())
                r.rowCount shouldBe 2
                r.columnCount shouldBe 2
            }
        }

        "non-silent truncation raises" {
            val cols = listOf(ColumnMeta("n", LogicalType.Int64, nullable = true))
            val iter =
                InMemoryRowIterable(
                    columns = cols,
                    rows = (1..5).map { arrayOf<Any?>(it.toLong()) },
                )
            iter.use { rows ->
                shouldThrow<IllegalStateException> {
                    DataFormatter.convert(
                        rows,
                        OutputFormat.CSV,
                        FormatOptions(rowLimit = 2, truncateSilently = false),
                    )
                }
            }
        }

        "writer exception still releases reader resources" {
            // Construct an InMemoryRowIterable that records close().
            var closed = false
            val cols = listOf(ColumnMeta("v", LogicalType.StringT, nullable = true))
            val iter =
                object : shared.formatter.core.RowIterable {
                    override val columns = cols

                    override fun iterator(): Iterator<Array<Any?>> = listOf(arrayOf<Any?>("only")).iterator()

                    override fun close() {
                        closed = true
                    }
                }
            iter.use {
                DataFormatter.convert(it, OutputFormat.JSON, FormatOptions())
            }
            closed shouldBe true
        }
    })
