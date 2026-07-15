// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.store

import com.typesafe.config.Config
import org.flywaydb.core.Flyway
import shared.libs.db.common.DatabaseConnection

/**
 * LG-P1·S1·T4 — Postgres wrapper the inference engine consumes. Wraps the shared
 * [DatabaseConnection] (Hikari + Exposed DSL, not ORM — house rule); runs Flyway at startup and
 * exposes a live [probe] for the readiness check (F-1 — no fake probes). Exposed-DSL repositories
 * (prompt logs, governance) build on `db.query { }` in later stages.
 */
class Pg private constructor(
    val db: DatabaseConnection,
) {
    /** Apply Flyway migrations (V1 exists; V2/V3 land in LG-P4/P5). Runs at startup, before serving. */
    fun migrate() {
        Flyway
            .configure()
            .dataSource(db.getDataSource())
            .load()
            .migrate()
    }

    /** Live reachability check — a real `SELECT 1`, never a cached/fake status. */
    fun probe(): Boolean =
        try {
            db.getDataSource().connection.use { c ->
                c.createStatement().use { it.execute("SELECT 1") }
            }
            true
        } catch (e: Exception) {
            false
        }

    fun close() = db.close()

    companion object {
        /** Build + init from the `db { … }` config block (contracts §5 stores). */
        fun fromConfig(config: Config): Pg {
            val db = DatabaseConnection.fromConfig(config, "db")
            db.init()
            return Pg(db)
        }
    }
}
