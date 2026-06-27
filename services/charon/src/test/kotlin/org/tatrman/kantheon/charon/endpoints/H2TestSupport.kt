package org.tatrman.kantheon.charon.endpoints

import java.sql.Connection
import org.tatrman.kantheon.charon.core.AllowList
import org.tatrman.kantheon.charon.core.ConnectionHandle
import org.tatrman.kantheon.charon.core.DbConnectionProvider
import org.tatrman.kantheon.charon.core.DbDialect

/**
 * H2 in-memory test support — the unit-test stand-in JDBC driver for the
 * DB-edge reader/writer (charon/plan.md §4: real PG/MSSQL fidelity is the
 * separate integration suite; H2 exercises the JDBC↔Arrow control flow + the
 * write-mode semantics in the test JVM with no external infra).
 *
 * H2 runs in **PostgreSQL compatibility mode** so the PG dialect's DDL
 * (`TEXT`, `BOOLEAN`, `BYTEA`, `DOUBLE PRECISION`, `DROP TABLE IF EXISTS`)
 * parses. `DB_CLOSE_DELAY=-1` keeps the in-memory database alive across pooled
 * connections for the duration of the spec.
 */
object H2TestSupport {
    /** A fresh handle backed by a uniquely-named in-memory H2 DB (PG mode). */
    fun handle(
        id: String,
        dbName: String = "charon_${id}_${counter()}",
        read: Boolean = true,
        write: Boolean = true,
        schemas: Set<String> = setOf("public"),
    ): ConnectionHandle =
        ConnectionHandle(
            id = id,
            dialect = DbDialect.POSTGRES,
            jdbcUrl = "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            username = "sa",
            password = "",
            allow = AllowList(read = read, write = write, schemas = schemas),
            poolMax = 2,
        )

    private var seq = 0L

    @Synchronized
    private fun counter(): Long = ++seq

    /** Run raw setup SQL on the H2 DB behind [handle] via [provider]. */
    fun exec(
        provider: DbConnectionProvider,
        handle: ConnectionHandle,
        vararg statements: String,
    ) {
        provider.open(handle).use { conn ->
            conn.createStatement().use { st ->
                statements.forEach { st.execute(it) }
            }
        }
    }

    /** Open a raw connection (for assertions / row counts). */
    fun open(
        provider: DbConnectionProvider,
        handle: ConnectionHandle,
    ): Connection = provider.open(handle)
}
