package shared.formatter.output

import shared.formatter.core.FormatOptions
import shared.formatter.core.RowIterable
import shared.formatter.types.ValueRenderer

/**
 * Tab-separated values: tab separator, LF line endings, header row.
 *
 * Embedded tabs and newlines inside cells are escaped as the literal
 * two-character sequences `\t` / `\n` — Excel-paste safe (Excel treats
 * those as literal text inside a cell rather than a separator). Backslashes
 * themselves are escaped as `\\` so the encoding is unambiguous.
 */
internal object TsvWriter {
    private const val LF = "\n"

    fun write(
        rows: RowIterable,
        opts: FormatOptions,
    ): WriteOutcome {
        val cols = rows.columns
        val sb = StringBuilder()
        sb.append(cols.joinToString("\t") { tsvEscape(Localisation.headerFor(it, opts)) })
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
            for (i in cols.indices) {
                if (i > 0) sb.append('\t')
                val raw = ValueRenderer.renderForTsv(row[i], cols[i].logicalType, opts)
                val localised = Localisation.substituteText(raw, cols[i], opts)
                sb.append(tsvEscape(localised))
            }
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

    private fun tsvEscape(value: String): String {
        if (value.isEmpty()) return value
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
