package org.tatrman.kantheon.charon.core

import java.sql.JDBCType
import java.sql.ResultSetMetaData
import java.sql.Types
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema

/** The two SQL dialects Charon's DB edges support at v1 (contracts §4 `kind`). */
enum class DbDialect {
    POSTGRES,
    MSSQL,
}

/**
 * The deterministic DB ↔ Arrow type mapping (charon/contracts.md §5).
 *
 * Two directions, one matrix:
 *   - **ingest (Arrow → DDL):** [arrowToDdl] renders the column type for a
 *     `CREATE TABLE`. List/Struct/Map are unsupported at v1 — a named
 *     [CharonError.UnmappableType] (`FAILED_PRECONDITION`), no silent coercion.
 *   - **extract (JDBC → Arrow):** [jdbcColumnToField] reads a `ResultSetMetaData`
 *     column and produces the inverse Arrow `Field`. A driver-specific JDBC
 *     type outside the matrix is the same named error.
 *
 * The mapping is **dialect-aware** where the matrix differs (Float64, Utf8,
 * Bool, Timestamp, Binary). It is the source of truth; contracts §5's table is
 * hand-derived from it, and `DbTypeMappingSpec` walks the whole matrix so any
 * drift fails the build.
 */
object DbTypeMapping {
    /**
     * Render the DDL column type for an Arrow [field] in [dialect].
     * @throws UnmappableTypeException naming the column for List/Struct/Map.
     */
    fun arrowToDdl(
        field: Field,
        dialect: DbDialect,
    ): String =
        when (val t = field.type) {
            is ArrowType.Int ->
                when (t.bitWidth) {
                    8, 16 -> "SMALLINT"
                    32 -> if (dialect == DbDialect.POSTGRES) "INTEGER" else "INT"
                    64 -> "BIGINT"
                    else -> unmappable(field.name, "int width ${t.bitWidth}")
                }
            is ArrowType.FloatingPoint ->
                when (t.precision) {
                    FloatingPointPrecision.SINGLE -> "REAL"
                    FloatingPointPrecision.DOUBLE -> if (dialect == DbDialect.POSTGRES) "DOUBLE PRECISION" else "FLOAT"
                    FloatingPointPrecision.HALF -> unmappable(field.name, "float16")
                }
            is ArrowType.Decimal ->
                if (dialect ==
                    DbDialect.POSTGRES
                ) {
                    "NUMERIC(${t.precision},${t.scale})"
                } else {
                    "DECIMAL(${t.precision},${t.scale})"
                }
            is ArrowType.Utf8, is ArrowType.LargeUtf8 ->
                if (dialect == DbDialect.POSTGRES) "TEXT" else "NVARCHAR(MAX)"
            is ArrowType.Bool -> if (dialect == DbDialect.POSTGRES) "BOOLEAN" else "BIT"
            is ArrowType.Date -> "DATE"
            is ArrowType.Timestamp ->
                if (t.timezone != null) {
                    if (dialect == DbDialect.POSTGRES) "TIMESTAMPTZ" else "DATETIMEOFFSET"
                } else {
                    if (dialect == DbDialect.POSTGRES) "TIMESTAMP" else "DATETIME2"
                }
            is ArrowType.Binary, is ArrowType.LargeBinary ->
                if (dialect == DbDialect.POSTGRES) "BYTEA" else "VARBINARY(MAX)"
            is ArrowType.List, is ArrowType.LargeList, is ArrowType.FixedSizeList ->
                unmappable(field.name, "list type unsupported over DB edges at v1")
            is ArrowType.Struct -> unmappable(field.name, "struct type unsupported over DB edges at v1")
            is ArrowType.Map -> unmappable(field.name, "map type unsupported over DB edges at v1")
            else -> unmappable(field.name, "Arrow type $t")
        }

    /** Render a full `CREATE TABLE` body (`col TYPE [NOT NULL], …`). Quoting
     *  uses double-quotes (PG) / brackets (MSSQL) on identifiers. */
    fun createTableDdl(
        schema: Schema,
        dialect: DbDialect,
        qualifiedTable: String,
    ): String {
        val cols =
            schema.fields.joinToString(", ") { f ->
                val nn = if (f.isNullable) "" else " NOT NULL"
                "${quoteIdent(f.name, dialect)} ${arrowToDdl(f, dialect)}$nn"
            }
        return "CREATE TABLE $qualifiedTable ($cols)"
    }

    /** Quote a SQL identifier for [dialect]. The dialect's own quote char is
     *  doubled (the injection defense); on top of that we reject blank and
     *  control-char identifiers as defense-in-depth, since `table`/`schema`
     *  reach here straight from the request.
     *  @throws InvalidIdentifierException for a blank or control-char identifier. */
    fun quoteIdent(
        ident: String,
        dialect: DbDialect,
    ): String {
        if (ident.isBlank()) throw InvalidIdentifierException(ident, "identifier is blank")
        if (ident.any { it.isISOControl() }) {
            throw InvalidIdentifierException(ident, "identifier contains a control character")
        }
        return when (dialect) {
            DbDialect.POSTGRES -> "\"" + ident.replace("\"", "\"\"") + "\""
            DbDialect.MSSQL -> "[" + ident.replace("]", "]]") + "]"
        }
    }

    /** Fully-qualified `schema.table`, both identifiers quoted. */
    fun qualify(
        schema: String,
        table: String,
        dialect: DbDialect,
    ): String = quoteIdent(schema, dialect) + "." + quoteIdent(table, dialect)

    /**
     * Map a single `ResultSetMetaData` column (1-based [idx]) to its inverse
     * Arrow [Field]. The JDBC `java.sql.Types` code drives the mapping; the
     * column's `isNullable` flag sets Arrow nullability.
     * @throws UnmappableTypeException naming the column for an unmapped JDBC type.
     */
    fun jdbcColumnToField(
        md: ResultSetMetaData,
        idx: Int,
    ): Field {
        val name = md.getColumnLabel(idx).ifEmpty { md.getColumnName(idx) }
        val nullable = md.isNullable(idx) != ResultSetMetaData.columnNoNulls
        val arrow = jdbcTypeToArrow(md.getColumnType(idx), md.getPrecision(idx), md.getScale(idx), name)
        return Field(name, FieldType(nullable, arrow, null), null)
    }

    /** Build the full extract Arrow [Schema] from a `ResultSetMetaData`. */
    fun schemaFromMetadata(md: ResultSetMetaData): Schema {
        val fields = (1..md.columnCount).map { jdbcColumnToField(md, it) }
        return Schema(fields)
    }

    private fun jdbcTypeToArrow(
        sqlType: Int,
        precision: Int,
        scale: Int,
        column: String,
    ): ArrowType =
        when (sqlType) {
            Types.TINYINT -> ArrowType.Int(8, true)
            Types.SMALLINT -> ArrowType.Int(16, true)
            Types.INTEGER -> ArrowType.Int(32, true)
            Types.BIGINT -> ArrowType.Int(64, true)
            Types.REAL -> ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)
            Types.FLOAT, Types.DOUBLE -> ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
            Types.DECIMAL, Types.NUMERIC -> {
                // Guard against drivers reporting precision 0 (treat as the
                // Decimal128 max so the vector is well-formed).
                val p = if (precision in 1..38) precision else 38
                val s = scale.coerceIn(0, p)
                ArrowType.Decimal(p, s, 128)
            }
            Types.BOOLEAN, Types.BIT -> ArrowType.Bool()
            Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
            Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB,
            -> ArrowType.Utf8()
            Types.DATE -> ArrowType.Date(DateUnit.DAY)
            Types.TIMESTAMP -> ArrowType.Timestamp(TimeUnit.MICROSECOND, null)
            Types.TIMESTAMP_WITH_TIMEZONE -> ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC")
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> ArrowType.Binary()
            else ->
                unmappable(
                    column,
                    "JDBC type ${runCatching { JDBCType.valueOf(sqlType).name }.getOrDefault(sqlType.toString())}",
                )
        }

    private fun unmappable(
        column: String,
        detail: String,
    ): Nothing = throw UnmappableTypeException(column, detail)
}

/** Thrown by [DbTypeMapping] for a type outside the contracts §5 matrix; the
 *  endpoint maps it to [CharonError.UnmappableType] (`FAILED_PRECONDITION`). */
class UnmappableTypeException(
    val column: String,
    val detail: String,
) : RuntimeException("column '$column': $detail")

/** Thrown by the DB writer when a write mode's precondition fails (CREATE on an
 *  existing table, APPEND onto a missing/incompatible table); the executor maps
 *  it to [CharonError.DbWritePrecondition] (`FAILED_PRECONDITION`). */
class DbWritePreconditionException(
    val schemaName: String,
    val table: String,
    val detail: String,
) : RuntimeException("$schemaName.$table: $detail")

/** Thrown by [DbTypeMapping.quoteIdent] for a structurally invalid SQL
 *  identifier (blank, control characters); the executor maps it to
 *  [CharonError.InvalidIdentifier] (`INVALID_ARGUMENT`). */
class InvalidIdentifierException(
    val ident: String,
    val detail: String,
) : RuntimeException("invalid SQL identifier: $detail")
