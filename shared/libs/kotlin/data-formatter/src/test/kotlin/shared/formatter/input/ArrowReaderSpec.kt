// SPDX-License-Identifier: Apache-2.0
package shared.formatter.input

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import shared.formatter.types.LogicalType
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.channels.Channels
import java.time.LocalDate

class ArrowReaderSpec :
    StringSpec({

        "empty bytes → zero columns + zero rows" {
            val out = ArrowReader.read(byteArrayOf())
            out.use {
                it.columns shouldBe emptyList()
                it.iterator().hasNext() shouldBe false
            }
        }

        "single batch fixture parses to 5 rows" {
            ArrowReader.read(ArrowFixtures.ordersFixture()).use { it ->
                it.columns shouldHaveSize 4
                it.columns[0].name shouldBe "id"
                it.columns[0].logicalType shouldBe LogicalType.Int64
                it.columns[1].logicalType shouldBe LogicalType.StringT
                it.columns[2].logicalType shouldBe LogicalType.Decimal(18, 2)
                it.columns[3].logicalType shouldBe LogicalType.Timestamp

                val rows = it.iterator().asSequence().toList()
                rows shouldHaveSize 5
                rows[0][0] shouldBe 1L
                rows[0][1] shouldBe "Alice"
                (rows[0][2] as BigDecimal).toPlainString() shouldBe "12.50"
                rows[2][1] shouldBe null // Cara was null → mapped to Carol earlier; let's verify the actual null cell
                rows[3][0] shouldBe null
                rows[4][2] shouldBe null
            }
        }

        "multi batch produces 4 rows" {
            ArrowReader.read(ArrowFixtures.multiBatchInts()).use {
                val rows = it.iterator().asSequence().toList()
                rows shouldHaveSize 4
                rows[0][0] shouldBe 10L
                rows[1][0] shouldBe 20L
                rows[2][0] shouldBe null
                rows[3][0] shouldBe 40L
            }
        }

        "covers all LogicalTypes" {
            ArrowReader.read(ArrowFixtures.allLogicalTypes()).use { it ->
                it.columns.map { it.logicalType } shouldBe
                    listOf(
                        LogicalType.Int64,
                        LogicalType.Double,
                        LogicalType.Decimal(10, 2),
                        LogicalType.StringT,
                        LogicalType.Bool,
                        LogicalType.Date,
                        LogicalType.Timestamp,
                        LogicalType.TimestampTz,
                        LogicalType.Bytes,
                    )

                val rows = it.iterator().asSequence().toList()
                rows shouldHaveSize 2

                val first = rows[0]
                first[0] shouldBe 7L
                first[1] shouldBe 2.5
                (first[2] as BigDecimal).toPlainString() shouldBe "3.14"
                first[3] shouldBe "hi"
                first[4] shouldBe true
                first[5] shouldBe LocalDate.of(2026, 5, 3)

                val nullRow = rows[1]
                for (cell in nullRow) cell shouldBe null
            }
        }

        "unsupported Arrow type fails loudly" {
            // Build a stream with an unsupported type (Int(128) is reserved/unsupported in Arrow).
            // Use a simple unsupported type — Decimal256 with bitwidth 256 — handled, so use IntervalDay.
            val unsupported = ArrowType.Interval(org.apache.arrow.vector.types.IntervalUnit.DAY_TIME)
            val schema = Schema(listOf(Field("interval", FieldType.nullable(unsupported), null)))
            val out = ByteArrayOutputStream()
            val alloc = RootAllocator(Long.MAX_VALUE)
            try {
                VectorSchemaRoot.create(schema, alloc).use { root ->
                    root.allocateNew()
                    ArrowStreamWriter(root, null, Channels.newChannel(out)).use { w ->
                        w.start()
                        root.setRowCount(0)
                        w.writeBatch()
                        w.end()
                    }
                }
            } finally {
                alloc.close()
            }
            shouldThrow<UnsupportedArrowType> {
                ArrowReader.read(out.toByteArray())
            }
        }
    })
