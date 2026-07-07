package org.tatrman.kantheon.arges.pipeline

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.postgresql.ds.PGSimpleDataSource
import org.tatrman.kantheon.arges.client.TranslatorClient
import org.tatrman.kantheon.arges.connection.ConnectionConfig
import org.tatrman.kantheon.arges.connection.ConnectionPoolManager
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.kantheon.testkit.containers.Containers
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.proteus.v1.UnparseRequest
import org.tatrman.proteus.v1.UnparseResponse
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ExecutionOptions
import org.tatrman.worker.v1.ResultBatch
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.sql.Connection

/**
 * WS-C1 T1 (the `pg-tpcds` half) — the **no-RLS-envelope** path: a connection profile with
 * `requires-tenant-id=false` (Kyklop's `pg-tpcds`, a plain analytical warehouse with no tenant
 * column) must run **without** the `SET LOCAL app.tenant_id` transaction that the Midas/RLS path
 * requires — and it must run even though **no tenant_id is supplied at all**.
 *
 * This is the deliberate contrast to [RlsLeakageComponentSpec]: there, a null tenant on a
 * `requires-tenant-id=true` connection fails closed with `tenant_id_required` and runs nothing;
 * here, the identical null tenant on a `requires-tenant-id=false` connection returns rows. The two
 * specs together pin down the `ExecutePipeline` Step-1.5/Step-4.5 branch.
 *
 * Also asserts the PG type mapper at the declared **`numeric(20,4)` boundary** — the max
 * representable value round-trips through Decimal128(20,4) exactly. The read-only role is granted
 * **no** access to `app_current_tenant()` and the TPC-DS-subset table carries no RLS policy, so a
 * spurious `SET LOCAL` attempt is not what makes this pass — the absence of the envelope is.
 *
 * Postgres is native multi-arch, so no `@EnabledIf(CiOnly)` gate (unlike the Brontes/MSSQL specs).
 */
@Tags("component")
class ArgesNoRlsTpcdsComponentSpec :
    StringSpec({

        "a pg-tpcds (requires-tenant-id=false) scan runs with NO tenant and NO SET LOCAL, mapping numeric at the (20,4) boundary" {
            Containers.postgres().use { pg ->
                pg.start()

                // A TPC-DS-subset fact table with no tenant column and no RLS policy — the shape
                // Arges sees behind the `pg-tpcds` profile. Seed the numeric(20,4) boundary value.
                connect(pg.jdbcUrl, pg.username, pg.password).use { su ->
                    su.createStatement().use { st ->
                        st.execute(
                            "CREATE TABLE web_sales_small (" +
                                "ws_order_number bigint NOT NULL, " +
                                "ws_net_paid numeric(20,4) NOT NULL, " +
                                "ws_web_site_name varchar(50) NOT NULL)",
                        )
                        // The non-owner login role Arges connects as for pg-tpcds — SELECT only,
                        // and deliberately NOT granted app_current_tenant() (there is none).
                        st.execute("CREATE ROLE $ROLE LOGIN PASSWORD '$PW' NOSUPERUSER")
                        st.execute("GRANT USAGE ON SCHEMA public TO $ROLE")
                        st.execute("GRANT SELECT ON web_sales_small TO $ROLE")
                    }
                    su.prepareStatement(
                        "INSERT INTO web_sales_small VALUES (?, ?::numeric, ?)",
                    ).use { ps ->
                        // Row 1 carries the max value the column can hold: 16 integer + 4 fractional
                        // digits = precision 20 — the declared boundary.
                        ps.setLong(1, 1L); ps.setString(2, "9999999999999999.9999"); ps.setString(3, "site.example")
                        ps.executeUpdate()
                        ps.setLong(1, 2L); ps.setString(2, "0.0001"); ps.setString(3, "shop.example")
                        ps.executeUpdate()
                    }
                }

                val pool =
                    ConnectionPoolManager(
                        mapOf(
                            "pg-tpcds" to
                                ConnectionConfig(
                                    id = "pg-tpcds",
                                    jdbcUrl = pg.jdbcUrl,
                                    username = ROLE,
                                    password = PW,
                                    database = pg.databaseName,
                                    defaultSchema = "public",
                                    requiresTenantId = false,
                                    readOnly = true,
                                ),
                        ),
                    )

                val batches =
                    try {
                        // tenant = null — the SAME input that fails closed on a requires-tenant
                        // connection. Here it must run.
                        execute(pool, "pg-tpcds")
                    } finally {
                        pool.close()
                    }

                // Ran cleanly: no tenant_id_required, no rls_set_failed, real rows came back.
                batches.flatMap { it.messagesList }.none { it.severity == Severity.ERROR } shouldBe true
                batches.sumOf { it.batchRowCount } shouldBe 2L

                // The numeric(20,4) boundary value round-trips exactly through Decimal128(20,4).
                decode(batches) shouldContainExactly
                    listOf(
                        SalesRow(1L, BigDecimal("9999999999999999.9999"), "site.example"),
                        SalesRow(2L, BigDecimal("0.0001"), "shop.example"),
                    )
            }
        }
    })

private const val ROLE = "tpcds_readonly"
private const val PW = "ro_pw"
private const val QUERY =
    "SELECT ws_order_number, ws_net_paid, ws_web_site_name FROM web_sales_small ORDER BY ws_order_number"

private data class SalesRow(
    val orderNumber: Long,
    val netPaid: BigDecimal,
    val webSiteName: String,
)

private val limits =
    ExecutePipeline.ExecutionLimits(
        defaultBatchSizeRows = 100,
        maxBatchSizeRows = 1_000,
        defaultTimeoutSeconds = 30,
        maxTimeoutSeconds = 300,
        maxBlobBytesPerCell = 8 * 1024 * 1024,
    )

/** A faked Proteus that returns the TPC-DS-subset SELECT directly (the unit/Brontes convention). */
private fun translator(): TranslatorClient =
    object : TranslatorClient {
        override suspend fun unparse(request: UnparseRequest): UnparseResponse =
            UnparseResponse.newBuilder().setOutput(QUERY).setContext(request.context).build()

        override suspend fun probe() = Unit
    }

/** Run the worker against [connectionId] with **no** tenant in the PipelineContext. */
private fun execute(
    pool: ConnectionPoolManager,
    connectionId: String,
): List<ResultBatch> {
    val request =
        ExecuteRequest
            .newBuilder()
            .setPlan(
                PlanNode.newBuilder().setTableScan(
                    TableScanNode.newBuilder().setTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setNamespace("public")
                            .setName("web_sales_small"),
                    ),
                ),
            ).setContext(PipelineContext.getDefaultInstance())
            .setConnectionId(connectionId)
            .setOptions(ExecutionOptions.getDefaultInstance())
            .build()
    return runBlocking { ExecutePipeline(pool, translator(), limits).execute(request).toList() }
}

private fun decode(batches: List<ResultBatch>): List<SalesRow> {
    val rows = mutableListOf<SalesRow>()
    RootAllocator(Long.MAX_VALUE).use { alloc ->
        batches.filter { !it.arrowIpc.isEmpty }.forEach { b ->
            ArrowStreamReader(ByteArrayInputStream(b.arrowIpc.toByteArray()), alloc).use { reader ->
                while (reader.loadNextBatch()) {
                    val root = reader.vectorSchemaRoot
                    val order = root.getVector("ws_order_number") as BigIntVector
                    val paid = root.getVector("ws_net_paid") as DecimalVector
                    val site = root.getVector("ws_web_site_name") as VarCharVector
                    for (i in 0 until root.rowCount) {
                        rows.add(SalesRow(order.get(i), paid.getObject(i), String(site.get(i))))
                    }
                }
            }
        }
    }
    return rows
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
