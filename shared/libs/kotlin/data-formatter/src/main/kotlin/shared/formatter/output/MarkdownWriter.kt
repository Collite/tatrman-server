// SPDX-License-Identifier: Apache-2.0
package shared.formatter.output

import shared.formatter.core.ColumnMeta
import shared.formatter.core.FormatOptions
import shared.formatter.core.MdAlign
import shared.formatter.core.RowIterable
import shared.formatter.types.LogicalType
import shared.formatter.types.ValueRenderer

/**
 * GFM table writer:
 *   `| col1 | col2 |`
 *   `| ---: | :--- |`
 *   `| val  | val  |`
 *
 * Numeric columns (Int64, Double, Decimal) right-align by default; everything
 * else left-aligns. `mdAlignmentOverrides` wins per column name.
 */
internal object MarkdownWriter {
    private const val LF = "\n"

    fun write(
        rows: RowIterable,
        opts: FormatOptions,
    ): WriteOutcome {
        val cols = rows.columns
        val sb = StringBuilder()

        // Header row.
        sb.append("| ")
        sb.append(cols.joinToString(" | ") { mdEscape(Localisation.headerFor(it, opts)) })
        sb.append(" |")
        sb.append(LF)

        // Alignment row.
        sb.append("| ")
        sb.append(cols.joinToString(" | ") { alignmentMarker(it, opts) })
        sb.append(" |")
        sb.append(LF)

        var written = 0
        var truncated = false
        val limit = opts.rowLimit
        val it = rows.iterator()
        while (it.hasNext()) {
            if (limit != null && written >= limit) {
                if (!opts.truncateSilently) error("rowLimit ($limit) exceeded")
                truncated = true
                break
            }
            val row = it.next()
            sb.append("| ")
            for (i in cols.indices) {
                if (i > 0) sb.append(" | ")
                val raw = ValueRenderer.renderForMarkdown(row[i], cols[i].logicalType, opts)
                val localised = Localisation.substituteText(raw, cols[i], opts)
                sb.append(mdEscape(localised))
            }
            sb.append(" |")
            sb.append(LF)
            written++
        }

        return WriteOutcome(
            bytes = sb.toString().toByteArray(Charsets.UTF_8),
            rowsWritten = written,
            truncated = truncated,
            columns = cols,
        )
    }

    private fun alignmentMarker(
        col: ColumnMeta,
        opts: FormatOptions,
    ): String {
        val explicit = opts.mdAlignmentOverrides[col.name]
        val align =
            explicit ?: when (col.logicalType) {
                LogicalType.Int64,
                LogicalType.Double,
                is LogicalType.Decimal,
                -> MdAlign.RIGHT
                else -> MdAlign.LEFT
            }
        return when (align) {
            MdAlign.LEFT -> ":---"
            MdAlign.RIGHT -> "---:"
            MdAlign.CENTER -> ":---:"
        }
    }

    /** GFM cells: pipes escaped; embedded newlines collapsed to a literal space (table cells must be single-line). */
    private fun mdEscape(value: String): String =
        value
            .replace("|", "\\|")
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
}
