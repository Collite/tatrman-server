package org.tatrman.worker.postgres.arrow

import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.Types
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import java.sql.JDBCType
import java.sql.ResultSetMetaData

/**
 * PostgreSQL-aware JDBC ResultSet → Arrow type mapper (the Brontes
 * `MssqlArrowTypeMapper` re-typed for Postgres; contracts §3 is the table).
 *
 * Disambiguates on `ResultSetMetaData.getColumnTypeName()` (Postgres reports
 * short native names — `int4`, `timestamptz`, `bpchar`, `_int4` for arrays —
 * which are more specific than the JDBC type code), falling back to JDBC type
 * codes and then to opaque VARBINARY for anything unrecognised.
 *
 * Field metadata (`pg.original_type`):
 *   - `timestamptz` → so consumers know the instant is normalised to UTC.
 *   - `uuid` → VARCHAR carrying a UUID string (Midas keys + `tenant_id` are UUID).
 *   - `json` / `jsonb` → VARCHAR carrying the JSON text.
 *   - `bytea` is faithful binary and carries NO metadata (not a fallback).
 *   - ranges / `inet` / `cidr` / arrays / `tsvector` / unrecognised → opaque
 *     VARBINARY + `pg.original_type=<type>`, which drives one
 *     `unsupported_type_as_binary` warning on the first batch.
 *
 * The v1 Midas query catalog only touches `int*`, `numeric(20,4)`,
 * `varchar/text`, `date`, `timestamptz`, `uuid`, `bool` — the must-pass set
 * in `PostgresArrowTypeMapperSpec`; the rest are defensive.
 */
object PostgresArrowTypeMapper {
    private const val ORIGINAL_TYPE_KEY = "pg.original_type"

    /**
     * Build an Arrow [Field] for a JDBC ResultSet column. Reads JDBC type code +
     * Postgres native type name + precision/scale; nullability from the
     * ResultSetMetaData.isNullable() probe.
     */
    fun fieldFor(
        meta: ResultSetMetaData,
        columnIndex: Int,
    ): Field {
        val name = meta.getColumnLabel(columnIndex).ifEmpty { meta.getColumnName(columnIndex) }
        val typeName = meta.getColumnTypeName(columnIndex).lowercase()
        val jdbcCode = JDBCType.valueOf(meta.getColumnType(columnIndex))
        val precision = meta.getPrecision(columnIndex)
        val scale = meta.getScale(columnIndex)
        val nullable = meta.isNullable(columnIndex) != ResultSetMetaData.columnNoNulls

        val (arrowType, metadata) = mapType(typeName, jdbcCode, precision, scale)
        val fieldType = FieldType(nullable, arrowType, null, metadata)
        return Field(name, fieldType, emptyList())
    }

    /**
     * Pure variant — used by tests and direct callers that don't have a
     * `ResultSetMetaData` handy. Returns the Arrow type and any field-level
     * metadata for the given Postgres native type name.
     */
    fun mapType(
        nativeTypeName: String,
        jdbcType: JDBCType,
        precision: Int,
        scale: Int,
    ): Pair<ArrowType, Map<String, String>?> {
        val name = nativeTypeName.lowercase()
        return when (name) {
            "int2", "smallint" -> Types.MinorType.SMALLINT.type to null
            "int4", "integer", "int" -> Types.MinorType.INT.type to null
            "int8", "bigint" -> Types.MinorType.BIGINT.type to null
            "bool", "boolean" -> Types.MinorType.BIT.type to null

            "float4", "real" -> ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE) to null
            "float8", "double", "double precision" ->
                ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE) to null

            "numeric", "decimal" -> {
                val p = if (precision in 1..38) precision else 38
                val s = if (scale in 0..p) scale else 0
                ArrowType.Decimal(p, s, 128) to null
            }
            // Postgres `money` is locale-scaled to 2 decimals; v1 Midas does not use it.
            "money" -> ArrowType.Decimal(19, 2, 128) to null

            "char", "bpchar", "character", "varchar", "character varying", "text", "name" ->
                Types.MinorType.VARCHAR.type to null

            "date" -> ArrowType.Date(DateUnit.DAY) to null
            "time", "time without time zone" -> ArrowType.Time(TimeUnit.NANOSECOND, 64) to null
            "timestamp", "timestamp without time zone" ->
                ArrowType.Timestamp(TimeUnit.NANOSECOND, null) to null
            "timestamptz", "timestamp with time zone" ->
                ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC") to mapOf(ORIGINAL_TYPE_KEY to "timestamptz")

            "uuid" -> Types.MinorType.VARCHAR.type to mapOf(ORIGINAL_TYPE_KEY to "uuid")

            // Faithful binary — NOT a fallback, carries no metadata.
            "bytea" -> Types.MinorType.VARBINARY.type to null

            "json" -> Types.MinorType.VARCHAR.type to mapOf(ORIGINAL_TYPE_KEY to "json")
            "jsonb" -> Types.MinorType.VARCHAR.type to mapOf(ORIGINAL_TYPE_KEY to "jsonb")

            // No native Arrow representation → opaque VARBINARY + original-type metadata so the
            // pipeline can warn once per column. None appear in the v1 Midas query catalog.
            "numrange", "tstzrange", "tsrange", "int4range", "int8range", "numrangearray",
            "inet", "cidr", "macaddr", "tsvector", "tsquery", "xml", "bit", "varbit",
            ->
                Types.MinorType.VARBINARY.type to mapOf(ORIGINAL_TYPE_KEY to name)

            else ->
                // Postgres array columns report a leading-underscore native name (`_int4`,
                // `_text`, …) — surface them as opaque binary with the original type recorded.
                if (name.startsWith("_")) {
                    Types.MinorType.VARBINARY.type to mapOf(ORIGINAL_TYPE_KEY to name)
                } else {
                    fallback(jdbcType, precision, scale)
                }
        }
    }

    /** Last-resort mapping when the Postgres native type name is unrecognised. */
    private fun fallback(
        jdbcType: JDBCType,
        precision: Int,
        scale: Int,
    ): Pair<ArrowType, Map<String, String>?> =
        when (jdbcType) {
            JDBCType.SMALLINT -> Types.MinorType.SMALLINT.type to null
            JDBCType.INTEGER -> Types.MinorType.INT.type to null
            JDBCType.BIGINT -> Types.MinorType.BIGINT.type to null
            JDBCType.BIT, JDBCType.BOOLEAN -> Types.MinorType.BIT.type to null
            JDBCType.DECIMAL, JDBCType.NUMERIC -> {
                val p = if (precision in 1..38) precision else 38
                val s = if (scale in 0..p) scale else 0
                ArrowType.Decimal(p, s, 128) to null
            }
            JDBCType.REAL -> ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE) to null
            JDBCType.FLOAT, JDBCType.DOUBLE -> ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE) to null
            JDBCType.CHAR, JDBCType.VARCHAR, JDBCType.LONGVARCHAR,
            JDBCType.NCHAR, JDBCType.NVARCHAR, JDBCType.LONGNVARCHAR,
            -> Types.MinorType.VARCHAR.type to null
            JDBCType.DATE -> ArrowType.Date(DateUnit.DAY) to null
            JDBCType.TIME -> ArrowType.Time(TimeUnit.NANOSECOND, 64) to null
            JDBCType.TIMESTAMP -> ArrowType.Timestamp(TimeUnit.NANOSECOND, null) to null
            JDBCType.TIMESTAMP_WITH_TIMEZONE ->
                ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC") to mapOf(ORIGINAL_TYPE_KEY to "timestamptz")
            JDBCType.BINARY, JDBCType.VARBINARY, JDBCType.LONGVARBINARY ->
                Types.MinorType.VARBINARY.type to null
            else -> Types.MinorType.VARBINARY.type to mapOf(ORIGINAL_TYPE_KEY to jdbcType.name.lowercase())
        }

    /** Exposed for tests. */
    internal fun originalTypeKey(): String = ORIGINAL_TYPE_KEY

    /**
     * Scan the schema for columns mapped to VARBINARY because the source type has no native Arrow
     * representation (ranges, inet/cidr, arrays, tsvector, or an unrecognised JDBC type — anything
     * carrying `pg.original_type` on a Binary field). Returns `(columnName, originalTypeName)` so
     * the ExecutePipeline can emit one `unsupported_type_as_binary` warning per column.
     *
     * `bytea` is excluded (VARBINARY with no metadata — faithful). VARCHAR-typed columns with an
     * `ORIGINAL_TYPE_KEY` (`uuid`, `json`, `jsonb`) are excluded (not binary fallbacks).
     */
    fun unsupportedBinaryFallbacks(schema: org.apache.arrow.vector.types.pojo.Schema): List<Pair<String, String>> =
        schema.fields.mapNotNull { f ->
            val arrowName = f.type.typeID
            val original = f.metadata[ORIGINAL_TYPE_KEY]
            if (original != null && arrowName == ArrowType.ArrowTypeID.Binary) {
                f.name to original
            } else {
                null
            }
        }
}
