package org.tatrman.worker.mssql.arrow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.apache.arrow.memory.RootAllocator
import shared.formatter.DataFormatter
import shared.formatter.core.OutputFormat
import java.sql.JDBCType
import java.sql.ResultSet
import java.sql.ResultSetMetaData

/**
 * Fork Stage 3.3 T5 — the worker → data-formatter Arrow IPC contract.
 *
 * Drives Brontes's *real* Arrow path against a mocked JDBC driver: a fixture
 * ResultSet → [ResultSetToArrow] → [ArrowIpcSerializer.serializeBatch] produces
 * the exact `ResultBatch.arrow_ipc` bytes Brontes streams. We then decode those
 * bytes with `shared/libs/kotlin/data-formatter` (`DataFormatter.fromArrow`) and
 * assert a full round-trip — schema (names + order), row count, and a value probe.
 *
 * This pins the worker ↔ formatter boundary before Theseus (the query orchestrator)
 * arrives, so a divergence in either the worker's encoder or the formatter's
 * decoder is caught at unit level. Real-MSSQL confirmation lives in the separate
 * integration-test suite.
 */
class ArrowIpcFormatterContractSpec :
    StringSpec({

        "Brontes Arrow IPC round-trips through data-formatter (schema + rows + value)" {
            RootAllocator(Long.MAX_VALUE).use { allocator ->
                // --- fixture ResultSet: two columns (id INT, name VARCHAR), two rows ---
                val meta =
                    mockk<ResultSetMetaData> {
                        every { columnCount } returns 2
                        // column 1: id INT
                        every { getColumnLabel(1) } returns "id"
                        every { getColumnName(1) } returns "id"
                        every { getColumnTypeName(1) } returns "int"
                        every { getColumnType(1) } returns JDBCType.INTEGER.vendorTypeNumber
                        every { getPrecision(1) } returns 10
                        every { getScale(1) } returns 0
                        every { isNullable(1) } returns ResultSetMetaData.columnNoNulls
                        // column 2: name VARCHAR
                        every { getColumnLabel(2) } returns "name"
                        every { getColumnName(2) } returns "name"
                        every { getColumnTypeName(2) } returns "varchar"
                        every { getColumnType(2) } returns JDBCType.VARCHAR.vendorTypeNumber
                        every { getPrecision(2) } returns 128
                        every { getScale(2) } returns 0
                        every { isNullable(2) } returns ResultSetMetaData.columnNullable
                    }
                val rs =
                    mockk<ResultSet> {
                        every { metaData } returns meta
                        every { next() } returnsMany listOf(true, true, false)
                        every { getInt(1) } returnsMany listOf(1, 2)
                        every { wasNull() } returnsMany listOf(false, false)
                        every { getObject(2) } returnsMany listOf("Brontes-alpha", "Brontes-beta")
                    }

                // --- worker path: ResultSet → Arrow → IPC bytes (what Brontes emits) ---
                val converter =
                    ResultSetToArrow(allocator = allocator, batchRows = 1024, maxBlobBytesPerCell = 8_388_608)
                val batches = converter.convert(rs).toList()
                batches.size shouldBe 1
                val batch = batches.single()
                batch.rowCount shouldBe 2

                val ipcBytes = batch.root!!.use { ArrowIpcSerializer.serializeBatch(it) }

                // --- formatter path: decode the same bytes back ---
                val decoded = DataFormatter.fromArrow(ipcBytes, OutputFormat.CSV)

                decoded.rowCount shouldBe 2
                decoded.columnCount shouldBe 2
                decoded.columns.map { it.name } shouldContainExactly listOf("id", "name")
                String(decoded.bytes) shouldContain "Brontes-alpha"
                String(decoded.bytes) shouldContain "Brontes-beta"
            }
        }
    })
