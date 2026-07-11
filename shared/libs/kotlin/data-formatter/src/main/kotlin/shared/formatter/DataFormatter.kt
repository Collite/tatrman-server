package shared.formatter

import shared.formatter.core.FormatOptions
import shared.formatter.core.FormattedResult
import shared.formatter.core.OutputFormat
import shared.formatter.core.Projection
import shared.formatter.core.RowIterable
import shared.formatter.input.ArrowReader
import shared.formatter.input.JsonRowsReader
import shared.formatter.output.CsvWriter
import shared.formatter.output.JsonWriter
import shared.formatter.output.MarkdownWriter
import shared.formatter.output.ParquetWriter
import shared.formatter.output.TsvWriter
import shared.formatter.output.WriteOutcome
import shared.formatter.output.XlsxWriter

/**
 * Public entry point for the library. Three façade methods:
 *
 *  * [fromArrow] — Arrow IPC byte stream → formatted output
 *  * [fromJsonRows] — `[ {col: val, ...}, ... ]` JSON byte array → formatted output
 *  * [convert] — internal, used by tests and downstream callers that already hold a [RowIterable]
 *
 * All entry points are stateless. Reader resources (Arrow allocator) are
 * scoped to the call.
 */
object DataFormatter {
    fun fromArrow(
        arrowIpc: ByteArray,
        output: OutputFormat,
        options: FormatOptions = FormatOptions(),
    ): FormattedResult {
        val rows = ArrowReader.read(arrowIpc)
        return rows.use { convert(rows, output, options) }
    }

    /**
     * Phase 08 D5 / DF-Q02 — multi-batch entry point. Each element of [arrowBatches] is a
     * self-contained Arrow IPC stream (the shape the worker emits per gRPC message); this method
     * decodes each in turn and concatenates their rows into a single [RowIterable] before
     * formatting. Useful for streaming consumers (`tools/query-mcp`) that need to render results
     * spanning more than one worker batch without re-encoding to a single Arrow stream.
     *
     * Schema check: the first non-empty batch's column list is canonical; subsequent batches must
     * agree (same names, same order). On mismatch the method throws `IllegalStateException` so
     * the caller can surface a clear error rather than emitting cells from misaligned schemas.
     *
     * Bounded buffering: [options.rowLimit] caps total rows across all batches; once reached,
     * remaining batches are ignored and the result is marked `truncated`. Callers that want a
     * hard cap independent of the per-batch `rowLimit` semantics should set `rowLimit` to that
     * cap.
     */
    fun fromArrowBatches(
        arrowBatches: List<ByteArray>,
        output: OutputFormat,
        options: FormatOptions = FormatOptions(),
    ): FormattedResult {
        if (arrowBatches.isEmpty()) {
            return fromArrow(ByteArray(0), output, options)
        }
        if (arrowBatches.size == 1) {
            return fromArrow(arrowBatches.single(), output, options)
        }

        var canonical: List<shared.formatter.core.ColumnMeta>? = null
        val accumulated = mutableListOf<Array<Any?>>()
        val cap = options.rowLimit ?: Int.MAX_VALUE
        var truncatedByCap = false

        for (batch in arrowBatches) {
            if (batch.isEmpty()) continue
            ArrowReader.read(batch).use { iter ->
                if (canonical == null) {
                    canonical = iter.columns
                } else {
                    val curr = iter.columns
                    val prev = canonical!!
                    if (curr.size != prev.size || curr.zip(prev).any { (a, b) -> a.name != b.name }) {
                        throw IllegalStateException(
                            "Arrow batch schema mismatch: expected ${prev.map {
                                it.name
                            }} but got ${curr.map { it.name }}",
                        )
                    }
                }
                val it = iter.iterator()
                while (it.hasNext()) {
                    if (accumulated.size >= cap) {
                        truncatedByCap = true
                        return@use
                    }
                    accumulated.add(it.next())
                }
            }
            if (truncatedByCap) break
        }

        if (canonical == null) {
            return fromArrow(ByteArray(0), output, options)
        }
        val merged =
            shared.formatter.core.InMemoryRowIterable(columns = canonical!!, rows = accumulated)
        // When the cap clipped us, [convert] won't see "more is available"; we add an explicit
        // truncated=true via FormattedResult.copy below since `convert`'s truncation is only set
        // when a writer hits `rowLimit`. Path-wise: rowLimit drives the writer too, so the
        // truncated flag may already be set by the writer; we OR with our own.
        val raw = merged.use { convert(merged, output, options) }
        return if (truncatedByCap && !raw.truncated) {
            FormattedResult(
                bytes = raw.bytes,
                mediaType = raw.mediaType,
                rowCount = raw.rowCount,
                columnCount = raw.columnCount,
                truncated = true,
                columns = raw.columns,
            )
        } else {
            raw
        }
    }

    fun fromJsonRows(
        jsonRows: ByteArray,
        output: OutputFormat,
        options: FormatOptions = FormatOptions(),
    ): FormattedResult {
        val rows = JsonRowsReader.read(jsonRows)
        return rows.use { convert(rows, output, options) }
    }

    internal fun convert(
        rows: RowIterable,
        output: OutputFormat,
        options: FormatOptions,
    ): FormattedResult {
        // Phase 2.2: merge the side-channel decorations onto each column
        // before any projection / writer step, so downstream code reads the
        // enriched ColumnMeta uniformly.
        val decorated =
            if (options.columnMetadata.isEmpty()) {
                rows
            } else {
                Projection.applyColumnDecorations(rows, options.columnMetadata)
            }
        // G3: apply hide-by-pattern projection before the writer. The projected
        // RowIterable forwards close(), so the upstream allocator (Arrow) is
        // still released by the caller's use{}.
        val hidden = Projection.hideColumns(decorated, options.hideColumnsMatching)
        // G3: row numbering wraps the (possibly hidden) iterable; numbers cover
        // only emitted rows and naturally clip to 1..N when the writer truncates.
        val numbered = Projection.rowNumbering(hidden, options.rowNumbering)
        // If projection collapses to zero columns, treat as an empty result
        // (header-only / empty output) — emitting N "rows" of zero cells is
        // semantically meaningless and produces awkward whitespace files.
        val effective =
            if (numbered.columns.isEmpty()) {
                shared.formatter.core.InMemoryRowIterable(columns = emptyList(), rows = emptyList())
            } else {
                numbered
            }
        val outcome: WriteOutcome =
            when (output) {
                OutputFormat.JSON -> JsonWriter.write(effective, options)
                OutputFormat.CSV -> CsvWriter.write(effective, options)
                OutputFormat.TSV -> TsvWriter.write(effective, options)
                OutputFormat.MARKDOWN -> MarkdownWriter.write(effective, options)
                OutputFormat.XLSX -> XlsxWriter.write(effective, options)
                OutputFormat.PARQUET -> ParquetWriter.write(effective, options)
            }
        return FormattedResult(
            bytes = outcome.bytes,
            mediaType = output.mediaType,
            rowCount = outcome.rowsWritten,
            columnCount = outcome.columns.size,
            truncated = outcome.truncated,
            columns = outcome.columns,
        )
    }
}
