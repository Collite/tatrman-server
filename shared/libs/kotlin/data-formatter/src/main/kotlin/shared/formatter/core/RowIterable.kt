package shared.formatter.core

/**
 * Internal contract bridging input readers and output writers.
 *
 * Each row is an `Array<Any?>` in column order. Cell types follow the
 * mapping documented per-LogicalType in [shared.formatter.types.ValueRenderer].
 *
 * Closeable so input readers backed by Arrow allocators can release native
 * memory deterministically.
 */
internal interface RowIterable : AutoCloseable {
    val columns: List<ColumnMeta>

    fun iterator(): Iterator<Array<Any?>>

    override fun close() {
        // default: no-op
    }
}

/**
 * Trivial in-memory implementation, used by hide-by-pattern projection
 * and tests.
 */
internal class InMemoryRowIterable(
    override val columns: List<ColumnMeta>,
    private val rows: List<Array<Any?>>,
) : RowIterable {
    override fun iterator(): Iterator<Array<Any?>> = rows.iterator()
}
