package org.tatrman.kantheon.arges.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.postgresql.PGConnection
import org.postgresql.ds.PGSimpleDataSource
import org.tatrman.kantheon.testkit.containers.Containers
import java.io.StringReader
import java.sql.Connection

/**
 * WS-T Stage 1 T1 — proves the TPC-DS **trailing-pipe-safe load form** before the cluster
 * `tpcds-load` Job replicates it (deploy-test contracts §4.2). Every TPC-DS `.dat` row ends
 * with a trailing `|`, so an N-column table emits N+1 fields and a naive `COPY` is rejected
 * ("extra data after last expected column"). The load form: strip the trailing `|` per row,
 * then `COPY … WITH (DELIMITER '|', NULL '')` — an empty last field becomes NULL.
 *
 * Runs the actual vendored `tpcds.sql` (all 25 CREATE TABLEs — also proving it's valid
 * PostgreSQL DDL) on a Testcontainers Postgres, then loads a tiny `reason` fixture and
 * asserts exact row + column fidelity, including the empty-last-column → NULL case.
 * Postgres is native multi-arch, so no CiOnly gate (unlike the Brontes/MSSQL specs).
 */
@Tags("component")
class TpcdsLoadComponentSpec :
    StringSpec({

        "the vendored tpcds.sql loads on Postgres and the trailing-pipe .dat COPYs with exact fidelity" {
            Containers.postgres().use { pg ->
                pg.start()
                connect(pg.jdbcUrl, pg.username, pg.password).use { c ->
                    // Schema from the vendored DDL — proves all 25 CREATE TABLEs are valid PG DDL.
                    runDdl(c, resource("tpcds/tpcds.sql"))

                    val dat = resource("tpcds/reason.fixture.dat")

                    // The gotcha: raw trailing-pipe rows have N+1 fields → COPY rejects them.
                    shouldThrow<Exception> { copyInto(c, "reason", dat) }

                    // The load form: strip the trailing pipe, then COPY. (truncate also proves
                    // the connection survived the failed COPY above.)
                    truncate(c, "reason")
                    copyInto(c, "reason", stripTrailingPipe(dat))

                    // Exact fidelity: 4 rows, values intact, empty last field → NULL.
                    count(c, "reason") shouldBe 4
                    reasonCol(c, 1, "r_reason_id") shouldBe "AAAAAAAABAAAAAAA"
                    reasonCol(c, 1, "r_reason_desc") shouldBe "Package was damaged"
                    reasonCol(c, 3, "r_reason_desc") shouldBe "Did not get it on time"
                    reasonCol(c, 99, "r_reason_desc").shouldBeNull() // empty last field → NULL
                }
            }
        }
    })

// --- the trailing-pipe-safe load form (the cluster tpcds-load Job replicates this in psql/bash) ---

/** Drop the single trailing `|` per row so an N-column table sees exactly N fields. */
private fun stripTrailingPipe(dat: String): String =
    dat
        .lineSequence()
        .filter { it.isNotEmpty() }
        .joinToString(separator = "\n", postfix = "\n") { it.removeSuffix("|") }

private fun copyInto(
    c: Connection,
    table: String,
    data: String,
) {
    c
        .unwrap(PGConnection::class.java)
        .copyAPI
        .copyIn("COPY $table FROM STDIN WITH (DELIMITER '|', NULL '')", StringReader(data))
}

private fun runDdl(
    c: Connection,
    ddl: String,
) = c.createStatement().use { it.execute(ddl) }

private fun truncate(
    c: Connection,
    table: String,
) = c.createStatement().use { it.execute("TRUNCATE $table") }

private fun count(
    c: Connection,
    table: String,
): Long =
    c.createStatement().use { st ->
        st.executeQuery("SELECT count(*) FROM $table").use {
            it.next()
            it.getLong(1)
        }
    }

/** `char(N)` columns come back space-padded — trim to the logical value. */
private fun reasonCol(
    c: Connection,
    sk: Int,
    col: String,
): String? =
    c.prepareStatement("SELECT $col FROM reason WHERE r_reason_sk = ?").use { ps ->
        ps.setInt(1, sk)
        ps.executeQuery().use { if (it.next()) it.getString(1)?.trim() else null }
    }

private fun connect(
    url: String,
    user: String,
    pw: String,
): Connection =
    PGSimpleDataSource()
        .apply {
            setURL(url)
            this.user = user
            this.password = pw
        }.connection

private fun resource(path: String): String =
    TpcdsLoadComponentSpec::class.java.classLoader
        .getResourceAsStream(path)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("component-test resource not found on classpath: $path")
