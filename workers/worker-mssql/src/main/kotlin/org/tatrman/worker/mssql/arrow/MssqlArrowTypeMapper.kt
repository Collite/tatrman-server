// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.mssql.arrow

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
 * MS SQL-aware JDBC ResultSet → Arrow type mapper.
 *
 * Handles every row of the Round 6.C.2 type table. Where MS SQL's type name
 * is more specific than its JDBC type code (rowversion → BINARY,
 * datetimeoffset → microsoft.sql.DateTimeOffset, money → DECIMAL with
 * fixed scale, etc.) we read `ResultSetMetaData.getColumnTypeName()` to
 * disambiguate; otherwise we fall back to JDBC type codes.
 *
 * Cast values that arrow-jdbc would mis-map (uniqueidentifier becoming a
 * BINARY blob; rowversion becoming a phantom timestamp) are special-cased
 * here. The corresponding fixtures in `MssqlArrowTypeMapperSpec` document
 * the contract.
 *
 * Field metadata:
 *   - rowversion → `mssql.original_type = "rowversion"` so consumers can
 *     distinguish "8 random bytes" from a real BLOB.
 *   - datetimeoffset → `mssql.original_type = "datetimeoffset"` plus the
 *     normalised UTC instant in the value (Bora's confirmation in 6.C.3).
 *   - xml/hierarchyid/geography/geometry/sql_variant → metadata flag
 *     `mssql.original_type = <type>` so callers can re-interpret if they
 *     have engine-specific knowledge.
 */
object MssqlArrowTypeMapper {
    private const val ORIGINAL_TYPE_KEY = "mssql.original_type"
    private const val ORIGINAL_OFFSET_KEY = "mssql.original_offset"

    /**
     * Build an Arrow [Field] for a JDBC ResultSet column. Reads JDBC type
     * code + MS SQL native type name + precision/scale; nullability comes
     * from the ResultSetMetaData.isNullable() probe.
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
     * metadata for the given MS SQL native type name.
     */
    fun mapType(
        nativeTypeName: String,
        jdbcType: JDBCType,
        precision: Int,
        scale: Int,
    ): Pair<ArrowType, Map<String, String>?> {
        val name = nativeTypeName.lowercase()
        return when {
            name == "tinyint" -> Types.MinorType.UINT1.type to null
            name == "smallint" -> Types.MinorType.SMALLINT.type to null
            name == "int" || name == "integer" -> Types.MinorType.INT.type to null
            name == "bigint" -> Types.MinorType.BIGINT.type to null
            name == "bit" -> Types.MinorType.BIT.type to null

            name == "money" -> ArrowType.Decimal(19, 4, 128) to null
            name == "smallmoney" -> ArrowType.Decimal(10, 4, 128) to null
            name == "decimal" || name == "numeric" -> {
                val p = if (precision in 1..38) precision else 38
                val s = if (scale in 0..p) scale else 0
                ArrowType.Decimal(p, s, 128) to null
            }

            name == "real" -> ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE) to null
            name == "float" || name == "double" -> ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE) to null

            name == "char" ||
                name == "nchar" ||
                name == "varchar" ||
                name == "nvarchar" ||
                name == "text" ||
                name == "ntext" -> Types.MinorType.VARCHAR.type to null

            name == "date" -> ArrowType.Date(DateUnit.DAY) to null
            name == "time" -> ArrowType.Time(TimeUnit.NANOSECOND, 64) to null
            name == "datetime" -> ArrowType.Timestamp(TimeUnit.MILLISECOND, null) to null
            name == "datetime2" -> ArrowType.Timestamp(TimeUnit.NANOSECOND, null) to null
            name == "smalldatetime" -> ArrowType.Timestamp(TimeUnit.SECOND, null) to null
            name == "datetimeoffset" ->
                ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC") to mapOf(ORIGINAL_TYPE_KEY to "datetimeoffset")

            name == "binary" -> Types.MinorType.VARBINARY.type to null
            name == "image" -> Types.MinorType.VARBINARY.type to null
            name == "varbinary" -> Types.MinorType.VARBINARY.type to null

            name == "uniqueidentifier" -> Types.MinorType.VARCHAR.type to mapOf(ORIGINAL_TYPE_KEY to "uniqueidentifier")
            name == "rowversion" || name == "timestamp" ->
                Types.MinorType.VARBINARY.type to mapOf(ORIGINAL_TYPE_KEY to "rowversion")

            name == "xml" -> Types.MinorType.VARCHAR.type to mapOf(ORIGINAL_TYPE_KEY to "xml")
            name == "hierarchyid" -> Types.MinorType.VARBINARY.type to mapOf(ORIGINAL_TYPE_KEY to "hierarchyid")
            name == "geography" -> Types.MinorType.VARBINARY.type to mapOf(ORIGINAL_TYPE_KEY to "geography")
            name == "geometry" -> Types.MinorType.VARBINARY.type to mapOf(ORIGINAL_TYPE_KEY to "geometry")
            name == "sql_variant" -> Types.MinorType.VARCHAR.type to mapOf(ORIGINAL_TYPE_KEY to "sql_variant")

            else -> fallback(jdbcType, precision, scale)
        }
    }

    /** Last-resort mapping when the MS SQL native type name is unrecognised. */
    private fun fallback(
        jdbcType: JDBCType,
        precision: Int,
        scale: Int,
    ): Pair<ArrowType, Map<String, String>?> =
        when (jdbcType) {
            JDBCType.TINYINT -> Types.MinorType.UINT1.type to null
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
                ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC") to mapOf(ORIGINAL_TYPE_KEY to "datetimeoffset")
            JDBCType.BINARY, JDBCType.VARBINARY, JDBCType.LONGVARBINARY ->
                Types.MinorType.VARBINARY.type to null
            else -> Types.MinorType.VARBINARY.type to mapOf(ORIGINAL_TYPE_KEY to jdbcType.name.lowercase())
        }

    /** Exposed for tests. */
    internal fun originalTypeKey(): String = ORIGINAL_TYPE_KEY

    /**
     * DF-W03 / G7 — scan the schema for columns that were mapped to VARBINARY because the source
     * type has no native Arrow representation (rowversion, hierarchyid, geography, geometry,
     * sql_variant, or any unrecognised JDBC type — the `fallback` else-branch). Returns
     * `(columnName, originalTypeName)` for each such column so the ExecutePipeline can emit one
     * `unsupported_type_as_binary` pipeline warning per column on the first batch.
     *
     * Columns whose source type *is* truly binary (`binary`, `varbinary`, `image`, JDBC `BINARY`)
     * are NOT included — the VARBINARY result is faithful, not a fallback. Likewise VARCHAR-typed
     * columns with an `ORIGINAL_TYPE_KEY` (`xml`, `uniqueidentifier`, `sql_variant`) are not
     * binary fallbacks and are excluded.
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

    /** Exposed for tests. */
    internal fun originalOffsetKey(): String = ORIGINAL_OFFSET_KEY
}
