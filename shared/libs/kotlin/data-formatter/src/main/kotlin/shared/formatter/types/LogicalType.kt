package shared.formatter.types

/**
 * Logical type of a column value, decoupled from any specific wire format.
 *
 * Mirrors the column-type taxonomy used across the v1 platform — Arrow IPC,
 * worker output, validator schemas. Workers may emit values typed by these
 * categories; consumers (writers, renderers) dispatch on this sealed
 * hierarchy.
 *
 * Sealed so adding a type forces an exhaustive update across all renderers
 * and writers — no silent fall-through.
 */
sealed interface LogicalType {
    data object Int64 : LogicalType

    data object Double : LogicalType

    /** Arbitrary-precision decimal with declared precision and scale. */
    data class Decimal(
        val precision: Int,
        val scale: Int,
    ) : LogicalType

    data object StringT : LogicalType

    data object Bool : LogicalType

    /** Calendar date (no time, no zone). */
    data object Date : LogicalType

    /** Timestamp without zone — local wall-clock. */
    data object Timestamp : LogicalType

    /** Timestamp with zone — UTC instant. */
    data object TimestampTz : LogicalType

    data object Bytes : LogicalType

    /** All-null column (rare; used when source could not infer a type). */
    data object NullType : LogicalType
}
