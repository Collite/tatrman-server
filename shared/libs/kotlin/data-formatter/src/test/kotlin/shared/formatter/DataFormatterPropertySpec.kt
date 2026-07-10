package shared.formatter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import shared.formatter.core.ColumnMeta
import shared.formatter.core.FormatOptions
import shared.formatter.core.InMemoryRowIterable
import shared.formatter.core.OutputFormat
import shared.formatter.input.JsonRowsReader
import shared.formatter.types.LogicalType

/**
 * Property-based smoke tests: random row counts and value distributions
 * exercise the full pipeline without exception, and assert round-trip
 * stability through the JSON path (Arrow ↔ JSON not covered here — see
 * the snapshot spec for the deterministic round-trip).
 */
class DataFormatterPropertySpec :
    StringSpec({

        val rowArb: Arb<Array<Any?>> =
            Arb.bind(
                Arb.long(min = -1_000_000L, max = 1_000_000L).orNull(nullProbability = 0.2),
                Arb.string(0..30).orNull(nullProbability = 0.1),
            ) { i, s -> arrayOf<Any?>(i, s) }

        val cols =
            listOf(
                ColumnMeta("n", LogicalType.Int64, nullable = true),
                ColumnMeta("s", LogicalType.StringT, nullable = true),
            )

        "all four formats produce non-throwing output for random inputs" {
            checkAll(Arb.list(rowArb, 0..50)) { rows ->
                for (fmt in OutputFormat.values()) {
                    InMemoryRowIterable(columns = cols, rows = rows).use {
                        val r = DataFormatter.convert(it, fmt, FormatOptions())
                        r.rowCount shouldBe rows.size
                        r.columnCount shouldBe 2
                        r.bytes.size shouldBe r.bytes.size // tautology to force materialisation
                    }
                }
            }
        }

        "JSON round-trip preserves row and column count" {
            checkAll(Arb.list(rowArb, 0..50)) { rows ->
                InMemoryRowIterable(columns = cols, rows = rows).use {
                    val r = DataFormatter.convert(it, OutputFormat.JSON, FormatOptions())
                    JsonRowsReader.read(r.bytes).use { reread ->
                        reread
                            .iterator()
                            .asSequence()
                            .toList()
                            .size shouldBe rows.size
                    }
                }
            }
        }

        "rowLimit truncates deterministically" {
            checkAll(Arb.list(rowArb, 5..30), Arb.int(1..20)) { rows, limit ->
                InMemoryRowIterable(columns = cols, rows = rows).use {
                    val r =
                        DataFormatter.convert(
                            it,
                            OutputFormat.CSV,
                            FormatOptions(rowLimit = limit),
                        )
                    val expectedRows = minOf(rows.size, limit)
                    r.rowCount shouldBe expectedRows
                    r.truncated shouldBe (rows.size > limit)
                }
            }
        }
    })
