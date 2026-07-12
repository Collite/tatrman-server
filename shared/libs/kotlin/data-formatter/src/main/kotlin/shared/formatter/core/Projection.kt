// SPDX-License-Identifier: Apache-2.0
package shared.formatter.core

import shared.formatter.types.LogicalType

/**
 * Stateless column-projection helpers used by the [shared.formatter.DataFormatter]
 * façade to apply [FormatOptions.hideColumnsMatching] and
 * [FormatOptions.rowNumbering] (both G3) before any writer sees the data.
 */
internal object Projection {
    /**
     * Returns a [RowIterable] view over [source] with columns whose names match
     * any regex in [hidePatterns] removed. The original RowIterable's [close]
     * is forwarded so allocator lifetimes are preserved.
     *
     * If [hidePatterns] is empty, returns [source] unchanged.
     */
    fun hideColumns(
        source: RowIterable,
        hidePatterns: List<Regex>,
    ): RowIterable {
        if (hidePatterns.isEmpty()) return source
        val keepIndices =
            source.columns
                .mapIndexedNotNull { idx, col ->
                    if (hidePatterns.any { it.containsMatchIn(col.name) }) null else idx
                }
        if (keepIndices.size == source.columns.size) return source
        val keptCols = keepIndices.map { source.columns[it] }
        return ProjectedRowIterable(source = source, keepIndices = keepIndices, columns = keptCols)
    }

    /**
     * Returns a [RowIterable] view that prepends a one-based `#` index column
     * to [source]. The index covers only emitted rows (numbering is generated
     * during iteration, so writer-side truncation via `rowLimit` clips the
     * sequence at 1..N where N is the clipped row count).
     *
     * Returns [source] unchanged when [numbering] is [RowNumbering.NONE].
     */
    fun rowNumbering(
        source: RowIterable,
        numbering: RowNumbering,
    ): RowIterable {
        if (numbering == RowNumbering.NONE) return source
        val indexColumn = ColumnMeta(name = "#", logicalType = LogicalType.Int64, nullable = false)
        return NumberedRowIterable(source = source, columns = listOf(indexColumn) + source.columns)
    }

    /**
     * Phase 2.2 — overlay [decorations] onto each column's [ColumnMeta]. Returns
     * [source] unchanged when no column matches a key in [decorations].
     *
     * Match is by exact column name. A matching key replaces the column's
     * [ColumnMeta.displayLabel] and [ColumnMeta.valueLabels] (the columns'
     * own values, normally empty unless the upstream reader populated them
     * from Arrow schema metadata, are overwritten by the side-channel).
     */
    fun applyColumnDecorations(
        source: RowIterable,
        decorations: Map<String, ColumnDecoration>,
    ): RowIterable {
        val updated =
            source.columns.map { col ->
                val deco = decorations[col.name] ?: return@map col
                col.copy(
                    displayLabel = deco.displayLabel,
                    valueLabels = deco.valueLabels,
                )
            }
        if (updated == source.columns) return source
        return DecoratedRowIterable(source = source, columns = updated)
    }
}

/** Identity-row but new column metadata. */
private class DecoratedRowIterable(
    private val source: RowIterable,
    override val columns: List<ColumnMeta>,
) : RowIterable {
    override fun iterator(): Iterator<Array<Any?>> = source.iterator()

    override fun close() = source.close()
}

/** Lazily prepends a 1-based row index to each row from [source]. */
private class NumberedRowIterable(
    private val source: RowIterable,
    override val columns: List<ColumnMeta>,
) : RowIterable {
    override fun iterator(): Iterator<Array<Any?>> {
        val it = source.iterator()
        var n = 0L
        return object : Iterator<Array<Any?>> {
            override fun hasNext(): Boolean = it.hasNext()

            override fun next(): Array<Any?> {
                val row = it.next()
                n++
                val out = arrayOfNulls<Any?>(row.size + 1)
                out[0] = n
                for (i in row.indices) out[i + 1] = row[i]
                return out
            }
        }
    }

    override fun close() {
        source.close()
    }
}

/** Internal RowIterable that lazily projects each row from [source] via [keepIndices]. */
private class ProjectedRowIterable(
    private val source: RowIterable,
    private val keepIndices: List<Int>,
    override val columns: List<ColumnMeta>,
) : RowIterable {
    override fun iterator(): Iterator<Array<Any?>> {
        val it = source.iterator()
        return object : Iterator<Array<Any?>> {
            override fun hasNext(): Boolean = it.hasNext()

            override fun next(): Array<Any?> {
                val row = it.next()
                return Array(keepIndices.size) { i -> row[keepIndices[i]] }
            }
        }
    }

    override fun close() {
        source.close()
    }
}
