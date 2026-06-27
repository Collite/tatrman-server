package shared.formatter.snapshot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import shared.formatter.DataFormatter
import shared.formatter.core.FormatOptions
import shared.formatter.core.OutputFormat
import shared.formatter.input.ArrowFixtures
import shared.formatter.input.JsonRowsReader

class OrdersSnapshotSpec :
    StringSpec({

        "Arrow fixture serialises to a stable byte snapshot" {
            // Capture the Arrow IPC bytes themselves so downstream consumers can replay.
            val arrow = ArrowFixtures.ordersFixture()
            SnapshotIo.assertEqualsOrRegenerate("fixtures/orders-5x4.arrow", arrow)
        }

        "Arrow → JSON snapshot" {
            val arrow = ArrowFixtures.ordersFixture()
            val r = DataFormatter.fromArrow(arrow, OutputFormat.JSON, FormatOptions(timestampZone = "Z"))
            SnapshotIo.assertEqualsOrRegenerate("fixtures/orders-5x4.json", r.bytes)
        }

        "Arrow → CSV snapshot" {
            val arrow = ArrowFixtures.ordersFixture()
            val r = DataFormatter.fromArrow(arrow, OutputFormat.CSV, FormatOptions(timestampZone = "Z"))
            SnapshotIo.assertEqualsOrRegenerate("fixtures/orders-5x4.csv", r.bytes)
        }

        "Arrow → TSV snapshot" {
            val arrow = ArrowFixtures.ordersFixture()
            val r = DataFormatter.fromArrow(arrow, OutputFormat.TSV, FormatOptions(timestampZone = "Z"))
            SnapshotIo.assertEqualsOrRegenerate("fixtures/orders-5x4.tsv", r.bytes)
        }

        "Arrow → MARKDOWN snapshot" {
            val arrow = ArrowFixtures.ordersFixture()
            val r = DataFormatter.fromArrow(arrow, OutputFormat.MARKDOWN, FormatOptions(timestampZone = "Z"))
            SnapshotIo.assertEqualsOrRegenerate("fixtures/orders-5x4.md", r.bytes)
        }

        "Arrow → JSON → JsonRowsReader round-trip preserves shape and values" {
            val arrow = ArrowFixtures.ordersFixture()
            val asJson = DataFormatter.fromArrow(arrow, OutputFormat.JSON, FormatOptions(timestampZone = "Z"))

            JsonRowsReader.read(asJson.bytes).use { reread ->
                reread.columns.size shouldBe 4
                reread.columns.map { it.name } shouldBe listOf("id", "customer", "amount", "ordered_at")

                val rows = reread.iterator().asSequence().toList()
                rows.size shouldBe 5

                // Pick a few cells we know.
                rows[0][0] shouldBe 1L
                rows[0][1] shouldBe "Alice"
                rows[2][1] shouldBe null // null customer
                rows[3][0] shouldBe null // null id
                rows[4][2] shouldBe null // null amount
            }
        }
    })
