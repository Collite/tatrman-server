package org.tatrman.kantheon.charon.core

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

/**
 * The JDBC connection seam (charon/architecture.md §2). Resolves a
 * [ConnectionHandle] to a live [Connection]. Behind it sits a per-connection
 * pool; the [AdbcReader]/[AdbcWriter] (the DB endpoints) borrow and return
 * connections through it.
 *
 * Abstracted as an interface so unit tests inject an in-JVM H2 provider (no
 * external infra) while production uses [HikariConnectionProvider] (a
 * `HikariDataSource` per connection id). This is the "mocked ADBC-JDBC driver"
 * seam of the Stage 2.1 spike — both the H2 test driver and the real PG/MSSQL
 * drivers sit behind it.
 */
interface DbConnectionProvider : AutoCloseable {
    /** Borrow a connection for [handle]; the caller closes it (returns it to
     *  the pool). */
    fun open(handle: ConnectionHandle): Connection

    override fun close() {}
}

/**
 * Production provider — one lazily-built [HikariDataSource] per connection id.
 * Pools are keyed by id and built on first use; [close] disposes them all.
 */
class HikariConnectionProvider : DbConnectionProvider {
    private val pools = ConcurrentHashMap<String, HikariDataSource>()

    override fun open(handle: ConnectionHandle): Connection =
        pools
            .computeIfAbsent(handle.id) {
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = handle.jdbcUrl
                        username = handle.username
                        password = handle.password
                        maximumPoolSize = handle.poolMax
                        poolName = "charon-${handle.id}"
                        // Pool default is autoCommit=true. The writer flips it
                        // off per ingest for its single explicit transaction
                        // (Stage 2.2); the reader flips it off so PG honours the
                        // server-side cursor (JdbcAdbcReader) — each restores the
                        // default before returning the connection to the pool.
                        isAutoCommit = true
                    },
                )
            }.connection

    override fun close() {
        pools.values.forEach { runCatching { it.close() } }
        pools.clear()
    }
}
