package org.tatrman.kantheon.charon.endpoints

import java.math.BigDecimal
import java.sql.Connection
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDate
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.BitVector
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.Float4Vector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.SmallIntVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.TimeStampMicroVector
import org.apache.arrow.vector.TinyIntVector
import org.apache.arrow.vector.VarBinaryVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.types.pojo.Schema
import org.slf4j.LoggerFactory
import org.tatrman.charon.v1.DbWriteMode
import org.tatrman.kantheon.charon.core.AdbcWriter
import org.tatrman.kantheon.charon.core.ConnectionHandle
import org.tatrman.kantheon.charon.core.DbConnectionProvider
import org.tatrman.kantheon.charon.core.DbTypeMapping
import org.tatrman.kantheon.charon.core.DbWritePreconditionException
import org.tatrman.kantheon.charon.core.LocationKind
import org.tatrman.kantheon.charon.core.Target

private val log = LoggerFactory.getLogger(JdbcAdbcWriter::class.java)

/**
 * The JDBC implementation of [AdbcWriter] (Stage 2.2). Ingests an Arrow stream
 * into `schema.table` in a **single transaction** (contracts §1 no-partial-write):
 *
 *   - **CREATE** — fail if the table exists ([DbWritePreconditionException] →
 *     `FAILED_PRECONDITION`); else `CREATE TABLE` from the Arrow schema
 *     ([DbTypeMapping.createTableDdl]; unmappable column → `FAILED_PRECONDITION`).
 *   - **REPLACE** — `DROP TABLE IF EXISTS` + `CREATE` + insert, all inside the
 *     one transaction; both PG and MSSQL have transactional DDL, so a reader
 *     sees the **old table until COMMIT, then the new** — never partial.
 *   - **APPEND** — the table must exist; its schema must match the source
 *     (name + Arrow type per column), else `FAILED_PRECONDITION` naming the
 *     column; rows are inserted into the existing table.
 *
 * A mid-stream fault rolls the whole transaction back ([Target.discard]).
 */
class JdbcAdbcWriter(
    private val provider: DbConnectionProvider,
    private val insertBatchRows: Int = 1_000,
) : AdbcWriter {
    override fun target(
        handle: ConnectionHandle,
        schema: String,
        table: String,
        writeMode: DbWriteMode,
    ): Target =
        object : Target {
            override fun begin(arrowSchema: Schema): Any {
                val conn = provider.open(handle)
                conn.autoCommit = false
                val qualified = DbTypeMapping.qualify(schema, table, handle.dialect)
                try {
                    when (writeMode) {
                        DbWriteMode.CREATE -> {
                            if (tableExists(conn, qualified)) {
                                throw DbWritePreconditionException(schema, table, "table already exists (CREATE)")
                            }
                            execute(conn, DbTypeMapping.createTableDdl(arrowSchema, handle.dialect, qualified))
                        }
                        DbWriteMode.REPLACE -> {
                            execute(conn, "DROP TABLE IF EXISTS $qualified")
                            execute(conn, DbTypeMapping.createTableDdl(arrowSchema, handle.dialect, qualified))
                        }
                        DbWriteMode.APPEND -> {
                            if (!tableExists(conn, qualified)) {
                                throw DbWritePreconditionException(schema, table, "table does not exist (APPEND)")
                            }
                            assertAppendCompatible(conn, qualified, arrowSchema, schema, table)
                        }
                        DbWriteMode.DB_WRITE_MODE_UNSPECIFIED, DbWriteMode.UNRECOGNIZED ->
                            throw DbWritePreconditionException(schema, table, "db_write_mode unspecified")
                    }
                    val insert = prepareInsert(conn, qualified, arrowSchema, handle.dialect)
                    return DbWriteReceipt(conn, insert, arrowSchema, schema, table)
                } catch (e: Exception) {
                    runCatching { conn.rollback() }
                    conn.close()
                    throw e
                }
            }

            override fun writeBatch(
                receipt: Any,
                root: VectorSchemaRoot,
            ) {
                val r = receipt as DbWriteReceipt
                val ps = r.insert
                for (row in 0 until root.rowCount) {
                    for (col in 0 until root.fieldVectors.size) {
                        bindCell(ps, col + 1, root.fieldVectors[col], row)
                    }
                    ps.addBatch()
                    r.pending++
                    if (r.pending >= insertBatchRows) {
                        ps.executeBatch()
                        r.pending = 0
                    }
                }
            }

            override fun commit(receipt: Any): Any {
                val r = receipt as DbWriteReceipt
                try {
                    if (r.pending > 0) r.insert.executeBatch()
                    r.conn.commit()
                } finally {
                    r.close()
                }
                return DbCommitReceipt("${handle.id}:$schema.$table")
            }

            override fun discard(receipt: Any) {
                val r = receipt as? DbWriteReceipt ?: return
                try {
                    r.conn.rollback()
                } catch (e: SQLException) {
                    log.debug("discard: rollback on {}.{} failed: {}", schema, table, e.message)
                } finally {
                    r.close()
                }
            }

            override fun kind(): LocationKind = LocationKind.DB_TABLE

            override fun ref(): String = "${handle.id}:$schema.$table"
        }

    private fun prepareInsert(
        conn: Connection,
        qualified: String,
        schema: Schema,
        dialect: org.tatrman.kantheon.charon.core.DbDialect,
    ): PreparedStatement {
        val cols = schema.fields.joinToString(", ") { DbTypeMapping.quoteIdent(it.name, dialect) }
        val placeholders = schema.fields.joinToString(", ") { "?" }
        return conn.prepareStatement("INSERT INTO $qualified ($cols) VALUES ($placeholders)")
    }

    private fun tableExists(
        conn: Connection,
        qualified: String,
    ): Boolean =
        try {
            conn.createStatement().use { it.executeQuery("SELECT 1 FROM $qualified WHERE 1=0").close() }
            true
        } catch (e: SQLException) {
            // Only a genuine "table/view not found" means absent. A permission
            // error, lock timeout, or dropped connection must NOT be silently
            // read as "table does not exist" (it would mis-report APPEND
            // preconditions and mask real faults) — rethrow those.
            if (e.sqlState in TABLE_NOT_FOUND_SQL_STATES) false else throw e
        }

    private fun assertAppendCompatible(
        conn: Connection,
        qualified: String,
        source: Schema,
        schemaName: String,
        table: String,
    ) {
        val existing =
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM $qualified WHERE 1=0").use { rs ->
                    DbTypeMapping.schemaFromMetadata(rs.metaData)
                }
            }
        if (existing.fields.size != source.fields.size) {
            throw DbWritePreconditionException(
                schemaName,
                table,
                "APPEND column count ${source.fields.size} != existing ${existing.fields.size}",
            )
        }
        source.fields.forEachIndexed { i, sf ->
            val ef = existing.fields[i]
            if (sf.name != ef.name || sf.type != ef.type) {
                throw DbWritePreconditionException(
                    schemaName,
                    table,
                    "APPEND column '${sf.name}' (${sf.type}) is incompatible with existing '${ef.name}' (${ef.type})",
                )
            }
        }
    }

    private fun execute(
        conn: Connection,
        sql: String,
    ) {
        conn.createStatement().use { it.execute(sql) }
    }

    private fun bindCell(
        ps: PreparedStatement,
        idx: Int,
        vec: FieldVector,
        row: Int,
    ) {
        if (vec.isNull(row)) {
            ps.setNull(idx, sqlTypeOf(vec))
            return
        }
        when (vec) {
            is TinyIntVector -> ps.setInt(idx, vec.get(row).toInt())
            is SmallIntVector -> ps.setInt(idx, vec.get(row).toInt())
            is IntVector -> ps.setInt(idx, vec.get(row))
            is BigIntVector -> ps.setLong(idx, vec.get(row))
            is Float4Vector -> ps.setFloat(idx, vec.get(row))
            is Float8Vector -> ps.setDouble(idx, vec.get(row))
            is DecimalVector -> ps.setBigDecimal(idx, vec.getObject(row) as BigDecimal)
            is BitVector -> ps.setBoolean(idx, vec.get(row) != 0)
            is VarCharVector -> ps.setString(idx, String(vec.get(row), Charsets.UTF_8))
            is DateDayVector -> ps.setDate(idx, Date.valueOf(LocalDate.ofEpochDay(vec.get(row).toLong())))
            is TimeStampMicroVector -> ps.setTimestamp(idx, timestampOf(vec.get(row)))
            is TimeStampMicroTZVector -> ps.setTimestamp(idx, timestampOf(vec.get(row)))
            is VarBinaryVector -> ps.setBytes(idx, vec.get(row))
            else -> error("JdbcAdbcWriter: no bind for ${vec.javaClass.simpleName}")
        }
    }

    private fun timestampOf(micros: Long): Timestamp {
        // Reconstruct via Instant with floor arithmetic so pre-1970 (negative)
        // micros round towards the correct second and the nanos field stays
        // non-negative.
        val secs = Math.floorDiv(micros, 1_000_000L)
        val microOfSec = Math.floorMod(micros, 1_000_000L)
        return Timestamp.from(java.time.Instant.ofEpochSecond(secs, microOfSec * 1_000L))
    }

    private fun sqlTypeOf(vec: FieldVector): Int =
        when (vec) {
            is TinyIntVector, is SmallIntVector -> Types.SMALLINT
            is IntVector -> Types.INTEGER
            is BigIntVector -> Types.BIGINT
            is Float4Vector -> Types.REAL
            is Float8Vector -> Types.DOUBLE
            is DecimalVector -> Types.NUMERIC
            is BitVector -> Types.BOOLEAN
            is VarCharVector -> Types.VARCHAR
            is DateDayVector -> Types.DATE
            is TimeStampMicroVector -> Types.TIMESTAMP
            is TimeStampMicroTZVector -> Types.TIMESTAMP_WITH_TIMEZONE
            is VarBinaryVector -> Types.VARBINARY
            else -> Types.OTHER
        }
}

/** Threaded through [Target.begin] → `writeBatch` → `commit`/`discard`. Owns
 *  the transaction's [Connection] + the prepared `INSERT`. */
private data class DbWriteReceipt(
    val conn: Connection,
    val insert: PreparedStatement,
    val arrowSchema: Schema,
    val schemaName: String,
    val table: String,
    var pending: Int = 0,
) {
    fun close() {
        runCatching { insert.close() }
        runCatching { conn.close() }
    }
}

private data class DbCommitReceipt(
    val target: String,
)

/** SQLStates meaning "the target table is not there", across the drivers
 *  Charon's DB edges support + the H2 test driver (values verified, not the
 *  vendor error codes):
 *    - PostgreSQL `42P01` (undefined_table),
 *    - SQL Server (mssql-jdbc) `S0002` / X/Open `42S02` (invalid object name),
 *    - H2 `42S04` (table or view not found) and `90079` (schema not found —
 *      a missing schema implies the table is absent too).
 *  Any *other* SQLState from the existence probe (permission denied, lock
 *  timeout, connection drop) is a real fault and is rethrown rather than read
 *  as "absent". */
private val TABLE_NOT_FOUND_SQL_STATES = setOf("42P01", "42S02", "S0002", "42S04", "90079")
