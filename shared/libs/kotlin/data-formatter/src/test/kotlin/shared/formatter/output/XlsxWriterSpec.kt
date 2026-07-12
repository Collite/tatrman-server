// SPDX-License-Identifier: Apache-2.0
package shared.formatter.output

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import shared.formatter.DataFormatter
import shared.formatter.core.ColumnMeta
import shared.formatter.core.FormatOptions
import shared.formatter.core.InMemoryRowIterable
import shared.formatter.core.LocalizedString
import shared.formatter.core.OutputFormat
import shared.formatter.types.LogicalType
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class XlsxWriterSpec :
    StringSpec({

        fun rowsOf(
            cols: List<ColumnMeta>,
            rows: List<Array<Any?>>,
        ) = InMemoryRowIterable(columns = cols, rows = rows)

        "XLSX — header + two typed rows decode back into a workbook with the right cell types" {
            val cols =
                listOf(
                    ColumnMeta("id", LogicalType.Int64, nullable = false),
                    ColumnMeta("name", LogicalType.StringT, nullable = true),
                    ColumnMeta("price", LogicalType.Decimal(precision = 12, scale = 2), nullable = true),
                    ColumnMeta("active", LogicalType.Bool, nullable = true),
                    ColumnMeta("created_on", LogicalType.Date, nullable = true),
                    ColumnMeta("created_at", LogicalType.Timestamp, nullable = true),
                )
            val rows =
                rowsOf(
                    cols,
                    listOf(
                        arrayOf<Any?>(
                            1L,
                            "Alice",
                            BigDecimal("19.95"),
                            true,
                            LocalDate.of(2026, 5, 13),
                            LocalDateTime.of(2026, 5, 13, 9, 30, 0),
                        ),
                        arrayOf<Any?>(
                            2L,
                            null,
                            null,
                            false,
                            null,
                            null,
                        ),
                    ),
                )
            val out = XlsxWriter.write(rows, FormatOptions())
            out.rowsWritten shouldBe 2
            out.truncated shouldBe false

            WorkbookFactory.create(ByteArrayInputStream(out.bytes)).use { wb ->
                wb.numberOfSheets shouldBe 1
                val sheet = wb.getSheetAt(0)
                sheet.sheetName shouldBe "Results"

                // Header row
                val header = sheet.getRow(0)
                header.getCell(0).stringCellValue shouldBe "id"
                header.getCell(1).stringCellValue shouldBe "name"

                // Row 1 — types preserved
                val r1 = sheet.getRow(1)
                r1.getCell(0).cellType shouldBe CellType.NUMERIC
                r1.getCell(0).numericCellValue shouldBe 1.0
                r1.getCell(1).cellType shouldBe CellType.STRING
                r1.getCell(1).stringCellValue shouldBe "Alice"
                r1.getCell(2).cellType shouldBe CellType.NUMERIC
                r1.getCell(2).numericCellValue shouldBe 19.95
                r1.getCell(3).cellType shouldBe CellType.BOOLEAN
                r1.getCell(3).booleanCellValue shouldBe true
                r1.getCell(4).cellType shouldBe CellType.NUMERIC
                // date cell — POI stores dates as numeric with a date format
                r1.getCell(5).cellType shouldBe CellType.NUMERIC

                // Row 2 — null cells are blank
                val r2 = sheet.getRow(2)
                r2.getCell(0).numericCellValue shouldBe 2.0
                // POI returns null for blank cells when reading; the column index just has no cell.
                (r2.getCell(1)?.cellType ?: CellType.BLANK) shouldBe CellType.BLANK
                r2.getCell(3).booleanCellValue shouldBe false
            }
        }

        "XLSX — hideColumnsMatching is honoured" {
            val cols =
                listOf(
                    ColumnMeta("id", LogicalType.Int64, nullable = false),
                    ColumnMeta("secret", LogicalType.StringT, nullable = true),
                    ColumnMeta("name", LogicalType.StringT, nullable = true),
                )
            val rows = rowsOf(cols, listOf(arrayOf<Any?>(1L, "shh", "Alice")))
            val xlsx =
                DataFormatter
                    .convert(
                        rows = rows,
                        output = OutputFormat.XLSX,
                        options = FormatOptions(hideColumnsMatching = listOf(Regex("^secret\$"))),
                    ).bytes

            WorkbookFactory.create(ByteArrayInputStream(xlsx)).use { wb ->
                val sheet = wb.getSheetAt(0)
                val header = sheet.getRow(0)
                // Only 2 columns survive: id, name
                header.getCell(0).stringCellValue shouldBe "id"
                header.getCell(1).stringCellValue shouldBe "name"
                header.getCell(2) shouldBe null
            }
        }

        "XLSX — display label localisation is applied to the header row" {
            val cols =
                listOf(
                    ColumnMeta(
                        name = "name",
                        logicalType = LogicalType.StringT,
                        nullable = true,
                        displayLabel = LocalizedString(mapOf("cs" to "Jméno", "en" to "Name")),
                    ),
                )
            val rows = rowsOf(cols, listOf(arrayOf<Any?>("Alice")))
            val out = XlsxWriter.write(rows, FormatOptions(preferredLanguage = "cs"))

            WorkbookFactory.create(ByteArrayInputStream(out.bytes)).use { wb ->
                wb
                    .getSheetAt(0)
                    .getRow(0)
                    .getCell(0)
                    .stringCellValue shouldBe "Jméno"
            }
        }

        "XLSX — value-label substitution affects string cells, not numeric cells" {
            val cols =
                listOf(
                    ColumnMeta("id", LogicalType.Int64, nullable = false),
                    ColumnMeta(
                        name = "status",
                        logicalType = LogicalType.StringT,
                        nullable = true,
                        valueLabels =
                            mapOf("A" to LocalizedString(mapOf("cs" to "Aktivní"))),
                    ),
                )
            val rows = rowsOf(cols, listOf(arrayOf<Any?>(7L, "A")))
            val out =
                XlsxWriter.write(rows, FormatOptions(preferredLanguage = "cs", substituteValueLabels = true))

            WorkbookFactory.create(ByteArrayInputStream(out.bytes)).use { wb ->
                val r = wb.getSheetAt(0).getRow(1)
                r.getCell(0).numericCellValue shouldBe 7.0
                r.getCell(1).stringCellValue shouldBe "Aktivní"
            }
        }

        "XLSX — rowLimit clips rows and sets truncated=true (silent)" {
            val cols = listOf(ColumnMeta("id", LogicalType.Int64, nullable = false))
            val rows = rowsOf(cols, (1..10).map { arrayOf<Any?>(it.toLong()) })
            val out = XlsxWriter.write(rows, FormatOptions(rowLimit = 3))
            out.rowsWritten shouldBe 3
            out.truncated shouldBe true
        }

        "XLSX — output passes the OutputFormat.binary contract" {
            OutputFormat.XLSX.binary shouldBe true
            OutputFormat.JSON.binary shouldBe false
            OutputFormat.CSV.binary shouldBe false
            OutputFormat.MARKDOWN.binary shouldBe false
        }

        "XLSX — bytes start with PK (zip magic) so consumers can content-sniff" {
            val cols = listOf(ColumnMeta("id", LogicalType.Int64, nullable = false))
            val rows = rowsOf(cols, listOf(arrayOf<Any?>(1L)))
            val out = XlsxWriter.write(rows, FormatOptions())
            // .xlsx is a zip — first two bytes are 'P' (0x50) 'K' (0x4B).
            out.bytes.size shouldNotBe 0
            out.bytes[0] shouldBe 0x50.toByte()
            out.bytes[1] shouldBe 0x4B.toByte()
        }
    })
