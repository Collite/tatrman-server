package shared.formatter.output

import shared.formatter.core.FormatOptions
import shared.formatter.core.RowIterable
import shared.formatter.types.ValueRenderer

/** RFC 4180 CSV: comma separator, CRLF line endings, header row, double-quote escapes. */
internal object CsvWriter {
    private const val CRLF = "\r\n"

    fun write(
        rows: RowIterable,
        opts: FormatOptions,
    ): WriteOutcome {
        val cols = rows.columns
        val sb = StringBuilder()
        // Header row.
        sb.append(cols.joinToString(",") { csvEscape(Localisation.headerFor(it, opts)) })
        sb.append(CRLF)

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
                if (i > 0) sb.append(',')
                val raw = ValueRenderer.renderForCsv(row[i], cols[i].logicalType, opts)
                val localised = Localisation.substituteText(raw, cols[i], opts)
                sb.append(csvEscape(localised))
            }
            sb.append(CRLF)
            written++
        }

        return WriteOutcome(
            bytes = sb.toString().toByteArray(Charsets.UTF_8),
            rowsWritten = written,
            truncated = truncated,
            columns = cols,
        )
    }

    /** Quote iff the value contains any of `, " CR LF`; embedded `"` is doubled. */
    private fun csvEscape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        if (!needsQuote) return value
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
