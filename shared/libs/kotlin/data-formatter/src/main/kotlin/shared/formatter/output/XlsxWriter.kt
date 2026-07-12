// SPDX-License-Identifier: Apache-2.0
package shared.formatter.output

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import shared.formatter.core.ColumnMeta
import shared.formatter.core.FormatOptions
import shared.formatter.core.RowIterable
import shared.formatter.types.LogicalType
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import java.util.Date

/**
 * Phase 08 D2 / DF-F02 — XLSX writer backed by POI's streaming `SXSSFWorkbook` so a results
 * export bounded by [FormatOptions.rowLimit] stays bounded in heap memory regardless of input
 * row count.
 *
 * Type mapping (preserves native Excel cell types, not just strings, so downstream consumers can
 * pivot / formula / chart without re-parsing):
 *   - `Int64`, `Double`, `Decimal` → numeric cell (Decimals as `setCellValue(BigDecimal.doubleValue())` — Excel's max precision is double anyway)
 *   - `StringT` → string cell
 *   - `Bool` → boolean cell
 *   - `Date` → date cell, formatted `yyyy-mm-dd`
 *   - `Timestamp` / `TimestampTz` → date cell, formatted `yyyy-mm-dd hh:mm:ss` (TimestampTz first converted to `opts.timestampZone`)
 *   - `Bytes` → string cell with the base64-encoded payload (binary in a spreadsheet is meaningless)
 *   - `NullType` → blank cell
 *   - any null value → blank cell
 *
 * Value-label substitution: applied to string-cell logical types iff `opts.substituteValueLabels`
 * and the rendered text matches a key in `col.valueLabels`. Numeric / date / bool cells preserve
 * their native type — labels are presentation-layer concerns; Excel users still want to sort and
 * pivot on the underlying value.
 *
 * Header row: bold; uses `Localisation.headerFor` (display label preferred over column name).
 *
 * Sheet name: "Results". Excel restricts sheet names to 31 chars and disallows `[ ] : * ? / \`;
 * "Results" is fine.
 */
internal object XlsxWriter {
    private const val SHEET_NAME = "Results"

    /** SXSSF flushes rows to disk once the window fills; smaller windows = less heap, more I/O. */
    private const val SXSSF_WINDOW_ROWS = 256

    fun write(
        rows: RowIterable,
        opts: FormatOptions,
    ): WriteOutcome {
        val cols = rows.columns
        val wb = SXSSFWorkbook(SXSSF_WINDOW_ROWS)
        wb.setCompressTempFiles(true)
        try {
            val sheet = wb.createSheet(SHEET_NAME)
            val headerStyle = headerStyle(wb)
            val dateStyle = cellStyle(wb, "yyyy-mm-dd")
            val timestampStyle = cellStyle(wb, "yyyy-mm-dd hh:mm:ss")

            val headerRow = sheet.createRow(0)
            for (i in cols.indices) {
                val c = headerRow.createCell(i)
                c.setCellValue(Localisation.headerFor(cols[i], opts))
                c.cellStyle = headerStyle
            }

            var written = 0
            var truncated = false
            val limit = opts.rowLimit
            val it = rows.iterator()
            var sheetRowIdx = 1
            while (it.hasNext()) {
                if (limit != null && written >= limit) {
                    if (!opts.truncateSilently) error("rowLimit ($limit) exceeded")
                    truncated = true
                    break
                }
                val row = it.next()
                val xlRow = sheet.createRow(sheetRowIdx)
                for (i in cols.indices) {
                    val cell = xlRow.createCell(i)
                    writeCell(cell, row[i], cols[i], opts, dateStyle, timestampStyle)
                }
                sheetRowIdx++
                written++
            }

            val baos = ByteArrayOutputStream()
            wb.write(baos)
            return WriteOutcome(
                bytes = baos.toByteArray(),
                rowsWritten = written,
                truncated = truncated,
                columns = cols,
            )
        } finally {
            // SXSSF spills temp files to disk; `close()` flushes and removes them (POI 5.x).
            wb.close()
        }
    }

    private fun writeCell(
        cell: Cell,
        value: Any?,
        col: ColumnMeta,
        opts: FormatOptions,
        dateStyle: CellStyle,
        timestampStyle: CellStyle,
    ) {
        if (value == null || col.logicalType == LogicalType.NullType) {
            // Leave blank (no setCellValue) — Excel treats this as an empty cell.
            return
        }
        when (col.logicalType) {
            LogicalType.Int64 -> cell.setCellValue(coerceLong(value).toDouble())
            LogicalType.Double -> cell.setCellValue(coerceDouble(value))
            is LogicalType.Decimal -> cell.setCellValue(coerceBigDecimal(value).toDouble())
            LogicalType.Bool -> cell.setCellValue(coerceBoolean(value))
            LogicalType.StringT -> {
                val raw = value.toString()
                cell.setCellValue(Localisation.substituteText(raw, col, opts))
            }
            LogicalType.Date -> {
                cell.setCellValue(toLocalDate(value))
                cell.cellStyle = dateStyle
            }
            LogicalType.Timestamp -> {
                cell.setCellValue(toLocalDateTime(value))
                cell.cellStyle = timestampStyle
            }
            LogicalType.TimestampTz -> {
                cell.setCellValue(toZonedDateTime(value, opts.timestampZone).toLocalDateTime())
                cell.cellStyle = timestampStyle
            }
            LogicalType.Bytes -> cell.setCellValue(Base64.getEncoder().encodeToString(coerceBytes(value)))
            LogicalType.NullType -> Unit
        }
    }

    private fun headerStyle(wb: Workbook): CellStyle {
        val style = wb.createCellStyle()
        val font = wb.createFont()
        font.bold = true
        style.setFont(font)
        return style
    }

    private fun cellStyle(
        wb: Workbook,
        format: String,
    ): CellStyle {
        val style = wb.createCellStyle()
        style.dataFormat = wb.creationHelper.createDataFormat().getFormat(format)
        return style
    }

    private fun coerceLong(v: Any): Long =
        when (v) {
            is Long -> v
            is Number -> v.toLong()
            is String -> v.toLong()
            else -> error("Cannot coerce ${v::class.qualifiedName} to Long")
        }

    private fun coerceDouble(v: Any): Double =
        when (v) {
            is Number -> v.toDouble()
            is String -> v.toDouble()
            else -> error("Cannot coerce ${v::class.qualifiedName} to Double")
        }

    private fun coerceBigDecimal(v: Any): BigDecimal =
        when (v) {
            is BigDecimal -> v
            is BigInteger -> BigDecimal(v)
            is Number -> BigDecimal.valueOf(v.toDouble())
            is String -> BigDecimal(v)
            else -> error("Cannot coerce ${v::class.qualifiedName} to BigDecimal")
        }

    private fun coerceBoolean(v: Any): Boolean =
        when (v) {
            is Boolean -> v
            is Number -> v.toLong() != 0L
            is String -> v.toBoolean()
            else -> error("Cannot coerce ${v::class.qualifiedName} to Boolean")
        }

    private fun coerceBytes(v: Any): ByteArray =
        when (v) {
            is ByteArray -> v
            is String -> v.toByteArray(Charsets.UTF_8)
            else -> error("Cannot coerce ${v::class.qualifiedName} to ByteArray")
        }

    private fun toLocalDate(v: Any): Date =
        when (v) {
            is LocalDate -> Date.from(v.atStartOfDay(ZoneOffset.UTC).toInstant())
            is java.sql.Date -> v
            is Number -> Date.from(LocalDate.ofEpochDay(v.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant())
            is String -> Date.from(LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant())
            else -> error("Cannot render ${v::class.qualifiedName} as DATE")
        }

    private fun toLocalDateTime(v: Any): LocalDateTime =
        when (v) {
            is LocalDateTime -> v
            is java.sql.Timestamp -> v.toLocalDateTime()
            is Instant -> v.atZone(ZoneOffset.UTC).toLocalDateTime()
            is Number -> instantFromEpochMicros(v.toLong()).atZone(ZoneOffset.UTC).toLocalDateTime()
            is String -> LocalDateTime.parse(v)
            else -> error("Cannot render ${v::class.qualifiedName} as TIMESTAMP")
        }

    private fun toZonedDateTime(
        v: Any,
        zoneId: String,
    ): ZonedDateTime {
        val zone = ZoneId.of(zoneId)
        return when (v) {
            is OffsetDateTime -> v.atZoneSameInstant(zone)
            is ZonedDateTime -> v.withZoneSameInstant(zone)
            is Instant -> v.atZone(zone)
            is Number -> instantFromEpochMicros(v.toLong()).atZone(zone)
            is String -> OffsetDateTime.parse(v).atZoneSameInstant(zone)
            else -> error("Cannot render ${v::class.qualifiedName} as TIMESTAMP_TZ")
        }
    }

    private fun instantFromEpochMicros(value: Long): Instant {
        val microsAbsCap = 5_000L * 365L * 24L * 3600L * 1_000_000L
        return if (value > -microsAbsCap && value < microsAbsCap) {
            Instant.ofEpochSecond(value / 1_000_000L, (value % 1_000_000L) * 1_000L)
        } else {
            Instant.ofEpochMilli(value)
        }
    }
}
