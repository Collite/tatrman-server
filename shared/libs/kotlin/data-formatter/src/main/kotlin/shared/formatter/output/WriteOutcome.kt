package shared.formatter.output

import shared.formatter.core.ColumnMeta

/**
 * Per-writer return type. The columns mirror the column list visible to the
 * writer (after any pre-projection done by the façade), so that
 * [shared.formatter.core.FormattedResult] can reflect them faithfully.
 */
internal data class WriteOutcome(
    val bytes: ByteArray,
    val rowsWritten: Int,
    val truncated: Boolean,
    val columns: List<ColumnMeta>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WriteOutcome) return false
        return rowsWritten == other.rowsWritten &&
            truncated == other.truncated &&
            columns == other.columns &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var h = rowsWritten
        h = 31 * h + truncated.hashCode()
        h = 31 * h + columns.hashCode()
        h = 31 * h + bytes.contentHashCode()
        return h
    }
}
