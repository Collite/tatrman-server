package org.tatrman.kantheon.charon.core

import java.security.MessageDigest
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.IntervalUnit
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.Schema

/**
 * Integrity primitives for the Charon move pipe (charon/architecture.md §5
 * "Invariants": "schema fingerprint verified end-to-end when
 * `expected_schema_fingerprint` is supplied, always computed and returned").
 *
 * ## Fingerprint = canonical logical-schema digest (NOT raw IPC bytes)
 *
 * review-006 R3 established that hashing raw Arrow IPC schema-message bytes is
 * **not** a stable cross-implementation identity: Arrow Java 18.3.0 and pyarrow
 * 18.0.0 emit different flatbuffer bytes for the *same logical schema*, and
 * pyarrow is not even self-consistent across a read/re-serialise round-trip.
 * Since the fingerprint is the cross-engine schema-identity check (a schema
 * Charon stages must verify equal to what a Polars/Steropes worker computes for
 * the same schema), the digest must be derived from the **logical** schema, not
 * any one library's wire encoding.
 *
 * The algorithm (decided 2026-06-15, Bora; charon/contracts.md §6):
 *
 *   - render each top-level field in declaration order, joined by `\n`;
 *   - each field renders as `name|type|nullability[<child;child;…>]`, where
 *     `nullability` is `null` (nullable) or `nonnull`, and the optional
 *     `<…>` block holds the recursively-encoded child fields (struct/list/map);
 *   - `type` is a canonical token with all parameters spelled out (int
 *     width+sign, float bits, decimal bit-width+precision+scale, timestamp
 *     unit+timezone, …), using **shared unit tokens** (`s|ms|us|ns`,
 *     `day`, `ym|dt|mdn`) rather than either library's native enum names;
 *   - field/schema **metadata is excluded** (it is provenance, not identity);
 *   - SHA-256 of the UTF-8 bytes of that string, lowercase hex.
 *
 * Steropes (`workers/steropes/.../fingerprint.py`) and Brontes
 * (`ArrowIpcSerializer.fingerprintFor`) implement the byte-identical algorithm
 * (fork Stage 3.4). The reference impl + regeneration command live at
 * `services/charon/src/test/resources/fixtures/integrity/regenerate.py`, and
 * `IntegritySpec` pins the Python-produced digest and asserts the Kotlin output
 * equals it (a genuine cross-engine cross-check, version-independent). The shared
 * fixture set `shared/testdata/fingerprints/` pins all three implementations
 * together; the `reference.arrow` digest there is this same schema's digest.
 */
object Integrity {
    /** SHA-256 (lowercase hex) of the canonical logical-schema string for [schema]. */
    fun fingerprint(schema: Schema): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonicalSchemaString(schema).toByteArray(Charsets.UTF_8))
            .toHex()

    /**
     * Same algorithm, but takes the [VectorSchemaRoot] that's already in
     * hand (the move-pipe hot path doesn't want to re-extract the schema
     * per chunk). The result is the same — the schema is the schema.
     */
    fun fingerprint(root: VectorSchemaRoot): String = fingerprint(root.schema)

    /**
     * The canonical, implementation-independent string form of [schema].
     * Exposed so the cross-check spec (and a debugging dump) can compare the
     * exact string, not just the digest. This is the contract the Python
     * reference must reproduce byte-for-byte.
     */
    fun canonicalSchemaString(schema: Schema): String = schema.fields.joinToString(separator = "\n") { encodeField(it) }

    private fun encodeField(field: Field): String {
        val nullability = if (field.isNullable) "null" else "nonnull"
        val children =
            if (field.children.isNullOrEmpty()) {
                ""
            } else {
                "<" + field.children.joinToString(";") { encodeField(it) } + ">"
            }
        return field.name + "|" + encodeType(field.type) + "|" + nullability + children
    }

    private fun encodeType(type: ArrowType): String =
        when (type) {
            is ArrowType.Null -> "null"
            is ArrowType.Bool -> "bool"
            is ArrowType.Int -> "int" + type.bitWidth + if (type.isSigned) "s" else "u"
            is ArrowType.FloatingPoint -> "float" + fpToken(type.precision)
            is ArrowType.Decimal -> "decimal" + type.bitWidth + "_" + type.precision + "_" + type.scale
            is ArrowType.Utf8 -> "utf8"
            is ArrowType.LargeUtf8 -> "large_utf8"
            is ArrowType.Binary -> "binary"
            is ArrowType.LargeBinary -> "large_binary"
            is ArrowType.FixedSizeBinary -> "fixed_size_binary_" + type.byteWidth
            is ArrowType.Date -> "date_" + dateToken(type.unit)
            is ArrowType.Time -> "time_" + timeToken(type.unit) + "_" + type.bitWidth
            is ArrowType.Timestamp -> "timestamp_" + timeToken(type.unit) + "_" + (type.timezone ?: "")
            is ArrowType.Duration -> "duration_" + timeToken(type.unit)
            is ArrowType.Interval -> "interval_" + intervalToken(type.unit)
            is ArrowType.List -> "list"
            is ArrowType.LargeList -> "large_list"
            is ArrowType.FixedSizeList -> "fixed_size_list_" + type.listSize
            is ArrowType.Struct -> "struct"
            is ArrowType.Map -> "map_" + if (type.keysSorted) "sorted" else "unsorted"
            else -> error("Charon fingerprint: unsupported Arrow type $type")
        }

    private fun fpToken(p: FloatingPointPrecision): String =
        when (p) {
            FloatingPointPrecision.HALF -> "16"
            FloatingPointPrecision.SINGLE -> "32"
            FloatingPointPrecision.DOUBLE -> "64"
        }

    /** Shared time-unit tokens — must match pyarrow's `unit` strings. */
    private fun timeToken(u: TimeUnit): String =
        when (u) {
            TimeUnit.SECOND -> "s"
            TimeUnit.MILLISECOND -> "ms"
            TimeUnit.MICROSECOND -> "us"
            TimeUnit.NANOSECOND -> "ns"
        }

    private fun dateToken(u: DateUnit): String =
        when (u) {
            DateUnit.DAY -> "day"
            DateUnit.MILLISECOND -> "ms"
        }

    private fun intervalToken(u: IntervalUnit): String =
        when (u) {
            IntervalUnit.YEAR_MONTH -> "ym"
            IntervalUnit.DAY_TIME -> "dt"
            IntervalUnit.MONTH_DAY_NANO -> "mdn"
        }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
}
