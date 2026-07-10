package shared.formatter.core

import shared.formatter.types.LogicalType

/** Input format the library knows how to read. */
enum class InputFormat {
    ARROW_IPC,
    JSON_ROWS,
}

/** Output format the library can produce, with its IANA media type. */
enum class OutputFormat(
    val mediaType: String,
    /**
     * Phase 08 D2 — true for formats whose bytes are not valid UTF-8 text (XLSX, future Parquet).
     * Consumers that ship the bytes through a text-only channel (HTTP `formatted: String`, MCP
     * `TextContent`) must base64-encode or use a binary content type (`EmbeddedResource`).
     */
    val binary: Boolean = false,
) {
    JSON("application/json; charset=utf-8"),
    CSV("text/csv; charset=utf-8"),
    TSV("text/tab-separated-values; charset=utf-8"),
    MARKDOWN("text/markdown; charset=utf-8"),
    XLSX(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        binary = true,
    ),
    PARQUET(
        // No official IANA type yet; `application/vnd.apache.parquet` is the de-facto.
        "application/vnd.apache.parquet",
        binary = true,
    ),
}

/** Markdown column alignment. */
enum class MdAlign {
    LEFT,
    RIGHT,
    CENTER,
}

/** Row numbering strategy (G3). */
enum class RowNumbering {
    NONE,
    ONE_BASED,
}

/** A single column's name, type, and nullability. */
data class ColumnMeta(
    val name: String,
    val logicalType: LogicalType,
    val nullable: Boolean,
    /** Phase 2.2 — per-language column header. Empty when not provided. */
    val displayLabel: LocalizedString = LocalizedString.EMPTY,
    /** Phase 2.2 — code → localised label, e.g. "1" → cs:"Aktivní". */
    val valueLabels: Map<String, LocalizedString> = emptyMap(),
)

/**
 * Phase 2.2 — per-language string with [preferred] lookup.
 *
 * Mirrors the proto `LocalizedString` but lives in the data-formatter
 * library so the library has no proto dependency.
 *
 * Lookups are case-insensitive on language code: `cs`, `CS`, `Cs` all hit
 * the same entry. The proto convention is lower-case but consumers may
 * pass an arbitrary BCP-47 tag.
 */
data class LocalizedString(
    val byLanguage: Map<String, String> = emptyMap(),
) {
    private val normalised = byLanguage.mapKeys { (k, _) -> k.lowercase() }

    val isEmpty: Boolean get() = byLanguage.isEmpty()

    /**
     * Return the label for [language], falling back to [fallback], then to
     * `null` when neither is present. Language tags are matched
     * case-insensitively.
     */
    fun preferred(
        language: String,
        fallback: String = "en",
    ): String? = normalised[language.lowercase()] ?: normalised[fallback.lowercase()]

    companion object {
        val EMPTY: LocalizedString = LocalizedString(emptyMap())
    }
}

/**
 * Phase 2.2 — side-channel decoration for one output column. Carries the
 * Model-side metadata (display label + value-label map) that
 * [DataFormatter] merges into [ColumnMeta] before the writers run.
 *
 * The decorator pattern keeps the worker dumb: the worker emits raw Arrow
 * IPC; `query-mcp` (or any other library consumer) builds a
 * `Map<columnName, ColumnDecoration>` from a fresh metadata-service lookup
 * and passes it via [FormatOptions.columnMetadata].
 */
data class ColumnDecoration(
    val displayLabel: LocalizedString = LocalizedString.EMPTY,
    val valueLabels: Map<String, LocalizedString> = emptyMap(),
)

/**
 * Configuration for a formatting run.
 *
 * Defaults are deliberately the legacy / no-op values so existing callers
 * (sql-formatter migration) see byte-for-byte behaviour preservation.
 */
data class FormatOptions(
    val rowLimit: Int? = null,
    val truncateSilently: Boolean = true,
    val mdAlignmentOverrides: Map<String, MdAlign> = emptyMap(),
    val timestampZone: String = "Z",
    /** G3 — column hidden iff its name matches any regex in this list. */
    val hideColumnsMatching: List<Regex> = emptyList(),
    /** G3 — when ONE_BASED, prepends a `#` index column to the output. */
    val rowNumbering: RowNumbering = RowNumbering.NONE,
    /**
     * Phase 2.2 — column-name → side-channel decoration. Merged into
     * [ColumnMeta] by [DataFormatter.convert] before the writers run. Keys
     * are matched case-sensitively against the column's `name`.
     */
    val columnMetadata: Map<String, ColumnDecoration> = emptyMap(),
    /** Phase 2.2 — preferred BCP-47 language for header + value-label lookups. */
    val preferredLanguage: String = "cs",
    /** Phase 2.2 — when true, substitute integer/string codes via `valueLabels`. */
    val substituteValueLabels: Boolean = true,
    /**
     * Phase 2.2 — JSON output only. When true, emit a top-level
     * `{rows: [...], __columnLabels: {col: localised, ...}}` object instead
     * of the bare array. Defaults to false to preserve byte-for-byte
     * compatibility for legacy callers.
     */
    val includeColumnLabels: Boolean = false,
)

/** The result of a formatting run. Immutable; bytes are defensively copied at construction. */
class FormattedResult(
    bytes: ByteArray,
    val mediaType: String,
    val rowCount: Int,
    val columnCount: Int,
    val truncated: Boolean,
    val columns: List<ColumnMeta>,
) {
    private val _bytes: ByteArray = bytes.copyOf()

    /** Defensive copy — caller cannot mutate internal state. */
    val bytes: ByteArray
        get() = _bytes.copyOf()

    fun bytesUtf8(): String = String(_bytes, Charsets.UTF_8)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FormattedResult) return false
        return mediaType == other.mediaType &&
            rowCount == other.rowCount &&
            columnCount == other.columnCount &&
            truncated == other.truncated &&
            columns == other.columns &&
            _bytes.contentEquals(other._bytes)
    }

    override fun hashCode(): Int {
        var h = mediaType.hashCode()
        h = 31 * h + rowCount
        h = 31 * h + columnCount
        h = 31 * h + truncated.hashCode()
        h = 31 * h + columns.hashCode()
        h = 31 * h + _bytes.contentHashCode()
        return h
    }

    override fun toString(): String =
        "FormattedResult(mediaType=$mediaType, rowCount=$rowCount, columnCount=$columnCount, truncated=$truncated)"
}
