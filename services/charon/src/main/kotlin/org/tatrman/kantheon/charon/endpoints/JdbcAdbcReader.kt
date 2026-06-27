package org.tatrman.kantheon.charon.endpoints

import java.sql.SQLException
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowReader
import org.slf4j.LoggerFactory
import org.tatrman.charon.v1.DescribeResult
import org.tatrman.kantheon.charon.core.AdbcReader
import org.tatrman.kantheon.charon.core.ConnectionHandle
import org.tatrman.kantheon.charon.core.DbConnectionProvider
import org.tatrman.kantheon.charon.core.DbTypeMapping
import org.tatrman.kantheon.charon.core.Integrity
import org.tatrman.kantheon.charon.core.LocationKind
import org.tatrman.kantheon.charon.core.Source

private val log = LoggerFactory.getLogger(JdbcAdbcReader::class.java)

/**
 * The JDBC implementation of [AdbcReader] (Stage 2.1 T5). Extracts
 * `schema.table` to a streaming Arrow [Source] via [JdbcArrowReader], and
 * answers [describe] from the catalog without a row scan.
 *
 * Table-level reads only — `SELECT * FROM <qualified>` with no predicate (no
 * query-path bypass; contracts §5 security note). The qualified name is built
 * from quoted identifiers ([DbTypeMapping.qualify]); the connection's
 * allow-list (read + schema) is enforced upstream in the executor before this
 * is reached.
 */
class JdbcAdbcReader(
    private val provider: DbConnectionProvider,
    private val parentAllocator: RootAllocator,
) : AdbcReader {
    override fun source(
        handle: ConnectionHandle,
        schema: String,
        table: String,
        chunkRows: Int,
    ): Source =
        object : Source {
            override fun open(): ArrowReader {
                val conn = provider.open(handle)
                // A real server-side cursor needs autoCommit OFF: Hikari hands
                // out autoCommit=true connections (Stage 2.2 write contract), and
                // with autoCommit on the PG driver IGNORES fetchSize and buffers
                // the whole ResultSet client-side — defeating the bounded-memory
                // guarantee this reader exists to provide. Flip it for the read
                // and restore it when the connection returns to the pool.
                val priorAutoCommit = conn.autoCommit
                if (priorAutoCommit) conn.autoCommit = false
                val stmt =
                    conn.createStatement().apply {
                        // Chunked cursor — the driver streams rather than
                        // buffering the whole result (the fetch size is the
                        // hint, honoured best-effort by each driver).
                        fetchSize = chunkRows
                    }
                val sql = "SELECT * FROM ${DbTypeMapping.qualify(schema, table, handle.dialect)}"
                val rs =
                    try {
                        stmt.executeQuery(sql)
                    } catch (e: SQLException) {
                        stmt.close()
                        restoreAndClose(conn, priorAutoCommit)
                        throw e
                    }
                // Build the Arrow schema from the result metadata — may throw
                // UnmappableTypeException (→ FAILED_PRECONDITION in the executor).
                val arrowSchema =
                    try {
                        DbTypeMapping.schemaFromMetadata(rs.metaData)
                    } catch (e: Exception) {
                        rs.close()
                        stmt.close()
                        restoreAndClose(conn, priorAutoCommit)
                        throw e
                    }
                return try {
                    JdbcArrowReader(parentAllocator, rs, arrowSchema, chunkRows) {
                        stmt.close()
                        restoreAndClose(conn, priorAutoCommit)
                    }
                } catch (e: Throwable) {
                    // Constructing the reader allocates; if it fails, the rs /
                    // stmt / conn would leak (pool exhaustion) since the caller
                    // never receives a reader to close.
                    rs.close()
                    stmt.close()
                    restoreAndClose(conn, priorAutoCommit)
                    throw e
                }
            }

            override fun kind(): LocationKind = LocationKind.DB_TABLE

            override fun ref(): String = "${handle.id}:$schema.$table"
        }

    /** Return a read connection to the pool, rolling back the read-only
     *  transaction and restoring the pool's autoCommit default first. */
    private fun restoreAndClose(
        conn: java.sql.Connection,
        priorAutoCommit: Boolean,
    ) {
        if (priorAutoCommit) {
            runCatching { conn.rollback() }
            runCatching { conn.autoCommit = true }
        }
        conn.close()
    }

    override fun describe(
        handle: ConnectionHandle,
        schema: String,
        table: String,
    ): DescribeResult {
        val conn = provider.open(handle)
        return try {
            conn.createStatement().use { stmt ->
                // `WHERE 1=0` returns the column metadata with no rows — the
                // cheap "schema + exists" probe (Pythia PD-5). Absent table /
                // no access throws SQLException → exists = false.
                val rs =
                    stmt.executeQuery(
                        "SELECT * FROM ${DbTypeMapping.qualify(schema, table, handle.dialect)} WHERE 1=0",
                    )
                rs.use {
                    val arrowSchema = DbTypeMapping.schemaFromMetadata(rs.metaData)
                    DescribeResult
                        .newBuilder()
                        .setExists(true)
                        .setSchemaFingerprint(Integrity.fingerprint(arrowSchema))
                        .setSchemaJson(arrowSchema.toString())
                        .setRowCount(-1L)
                        .setRowCountExact(false)
                        .setSizeBytes(-1L)
                        .build()
                }
            }
        } catch (e: SQLException) {
            log.debug("describe({}.{}) on conn {}: {}", schema, table, handle.id, e.message)
            DescribeResult.newBuilder().setExists(false).build()
        } finally {
            conn.close()
        }
    }
}
