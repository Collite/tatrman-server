package shared.formatter.types

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import shared.formatter.core.FormatOptions
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Renders a single cell value to one of the four output formats, according
 * to the [LogicalType] of the column.
 *
 * NULL handling is centralised here:
 *  * JSON → [JsonNull]
 *  * CSV  → empty string
 *  * TSV  → empty string
 *  * Markdown → literal `null`
 *
 * This way each writer can call the renderer and ignore null-vs-non-null;
 * the writer only deals with format-level concerns (quoting, separators).
 */
internal object ValueRenderer {
    private val isoLocalDate = DateTimeFormatter.ISO_LOCAL_DATE
    private val isoLocalDateTime = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val isoOffsetDateTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun renderForJson(
        value: Any?,
        type: LogicalType,
        opts: FormatOptions,
    ): JsonElement {
        if (value == null) return JsonNull
        return when (type) {
            LogicalType.Int64 ->
                JsonPrimitive(coerceLong(value))

            LogicalType.Double ->
                JsonPrimitive(coerceDouble(value))

            is LogicalType.Decimal ->
                // Preserve textual representation; strict JSON parsers may downcast.
                JsonUnquotedLiteral(coerceBigDecimal(value).toPlainString())

            LogicalType.StringT ->
                JsonPrimitive(value.toString())

            LogicalType.Bool ->
                JsonPrimitive(coerceBoolean(value))

            LogicalType.Date ->
                JsonPrimitive(renderDateText(value))

            LogicalType.Timestamp ->
                JsonPrimitive(renderTimestampText(value))

            LogicalType.TimestampTz ->
                JsonPrimitive(renderTimestampTzText(value, opts.timestampZone))

            LogicalType.Bytes ->
                JsonPrimitive(Base64.getEncoder().encodeToString(coerceBytes(value)))

            LogicalType.NullType ->
                JsonNull
        }
    }

    /** Renders a non-quoted string for CSV. The caller wraps in quotes if needed. */
    fun renderForCsv(
        value: Any?,
        type: LogicalType,
        opts: FormatOptions,
    ): String = renderText(value, type, opts, nullText = "")

    /** Renders a non-escaped string for TSV. The caller escapes embedded \t and \n. */
    fun renderForTsv(
        value: Any?,
        type: LogicalType,
        opts: FormatOptions,
    ): String = renderText(value, type, opts, nullText = "")

    /** Renders the cell content for a Markdown table. The caller escapes pipes. */
    fun renderForMarkdown(
        value: Any?,
        type: LogicalType,
        opts: FormatOptions,
    ): String = renderText(value, type, opts, nullText = "null")

    private fun renderText(
        value: Any?,
        type: LogicalType,
        opts: FormatOptions,
        nullText: String,
    ): String {
        if (value == null) return nullText
        return when (type) {
            LogicalType.Int64 -> coerceLong(value).toString()
            LogicalType.Double -> coerceDouble(value).toString()
            is LogicalType.Decimal -> coerceBigDecimal(value).toPlainString()
            LogicalType.StringT -> value.toString()
            LogicalType.Bool -> coerceBoolean(value).toString()
            LogicalType.Date -> renderDateText(value)
            LogicalType.Timestamp -> renderTimestampText(value)
            LogicalType.TimestampTz -> renderTimestampTzText(value, opts.timestampZone)
            LogicalType.Bytes -> Base64.getEncoder().encodeToString(coerceBytes(value))
            LogicalType.NullType -> nullText
        }
    }

    // ----- coercions ---------------------------------------------------------

    private fun coerceLong(v: Any): Long =
        when (v) {
            is Long -> v
            is Int -> v.toLong()
            is Short -> v.toLong()
            is Byte -> v.toLong()
            is Number -> v.toLong()
            is String -> v.toLong()
            else -> error("Cannot coerce ${v::class.qualifiedName} to Long")
        }

    private fun coerceDouble(v: Any): Double =
        when (v) {
            is Double -> v
            is Float -> v.toDouble()
            is Number -> v.toDouble()
            is String -> v.toDouble()
            else -> error("Cannot coerce ${v::class.qualifiedName} to Double")
        }

    private fun coerceBigDecimal(v: Any): BigDecimal =
        when (v) {
            is BigDecimal -> v
            is BigInteger -> BigDecimal(v)
            is Long -> BigDecimal.valueOf(v)
            is Int -> BigDecimal.valueOf(v.toLong())
            is Double -> BigDecimal.valueOf(v)
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

    // ----- temporal rendering ------------------------------------------------

    private fun renderDateText(v: Any): String =
        when (v) {
            is LocalDate -> v.format(isoLocalDate)
            is java.sql.Date -> v.toLocalDate().format(isoLocalDate)
            is Number -> LocalDate.ofEpochDay(v.toLong()).format(isoLocalDate)
            is String -> v
            else -> error("Cannot render ${v::class.qualifiedName} as DATE")
        }

    private fun renderTimestampText(v: Any): String =
        when (v) {
            is LocalDateTime -> v.format(isoLocalDateTime)
            is java.sql.Timestamp -> v.toLocalDateTime().format(isoLocalDateTime)
            is Instant -> v.atZone(ZoneOffset.UTC).toLocalDateTime().format(isoLocalDateTime)
            is Number ->
                instantFromEpochMicros(v.toLong())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDateTime()
                    .format(isoLocalDateTime)
            is String -> v
            else -> error("Cannot render ${v::class.qualifiedName} as TIMESTAMP")
        }

    private fun renderTimestampTzText(
        v: Any,
        zoneId: String,
    ): String {
        val zone = ZoneId.of(zoneId)
        return when (v) {
            is OffsetDateTime -> v.atZoneSameInstant(zone).toOffsetDateTime().format(isoOffsetDateTime)
            is ZonedDateTime -> v.withZoneSameInstant(zone).toOffsetDateTime().format(isoOffsetDateTime)
            is Instant -> v.atZone(zone).toOffsetDateTime().format(isoOffsetDateTime)
            is Number -> instantFromEpochMicros(v.toLong()).atZone(zone).toOffsetDateTime().format(isoOffsetDateTime)
            is String -> v
            else -> error("Cannot render ${v::class.qualifiedName} as TIMESTAMP_TZ")
        }
    }

    /**
     * Heuristic Arrow-friendly conversion: micros if the magnitude looks like
     * micros-since-epoch (within +/- 5000 years), otherwise fall back to millis.
     */
    private fun instantFromEpochMicros(value: Long): Instant {
        val microsAbsCap = 5_000L * 365L * 24L * 3600L * 1_000_000L
        return if (value > -microsAbsCap && value < microsAbsCap) {
            Instant.ofEpochSecond(value / 1_000_000L, (value % 1_000_000L) * 1_000L)
        } else {
            Instant.ofEpochMilli(value)
        }
    }
}
