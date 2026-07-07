package org.tatrman.kantheon.argos.policy

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.postgresql.ds.PGSimpleDataSource
import org.tatrman.kantheon.testkit.containers.Containers
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.security.v1.EvaluatePoliciesRequest
import java.sql.Connection

/**
 * WS-C1 T3 — Argos's RLS policy decision, **enforced against a real Postgres**. Where the unit
 * specs assert the shape of the `tenant_isolation` predicate in isolation, this proves the predicate
 * actually restricts rows: the `Expression` the [PolicyEngine] emits for a `tenant:user` identity is
 * turned into the WHERE clause the downstream worker would unparse, run against a two-tenant table in
 * Testcontainers Postgres, and shown to return **only the caller's tenant** — cross-tenant rows are
 * denied, a same-tenant query returns rows, and symmetry holds for the other tenant.
 *
 * The predicate's column and literal are read back **from Argos's decision** (not hardcoded in the
 * SQL), so a regression that changed the isolation column or dropped the tenant substitution would
 * fail here. Postgres is native multi-arch, so no `@EnabledIf(CiOnly)` gate.
 */
@Tags("component")
class ArgosRlsComponentSpec :
    StringSpec({

        "the tenant_isolation predicate denies cross-tenant rows and admits same-tenant rows on real Postgres" {
            Containers.postgres().use { pg ->
                pg.start()
                connect(pg.jdbcUrl, pg.username, pg.password).use { c ->
                    // Two tenants' rows in one physical table — the warehouse shape Argos guards.
                    c.createStatement().use { st ->
                        st.execute(
                            "CREATE TABLE customers (tenant_id text NOT NULL, id bigint NOT NULL, name text NOT NULL)",
                        )
                        st.execute("INSERT INTO customers VALUES ('tenant-7', 1, 'alice'), ('tenant-7', 2, 'amir')")
                        st.execute("INSERT INTO customers VALUES ('tenant-9', 3, 'bob'), ('tenant-9', 4, 'bella')")
                    }

                    val engine = PolicyEngine(PolicyRegistry(DefaultPolicies.core))

                    // Baseline: with no policy applied, the table holds both tenants' rows.
                    countWhere(c, null) shouldBe 4

                    // Tenant-7: Argos emits `tenant_id = 'tenant-7'`; enforcing it admits only alice+amir.
                    val whereA = policyWhere(engine, userId = "tenant-7:alice")
                    countWhere(c, whereA) shouldBe 2
                    countWhere(c, "$whereA AND name IN ('bob', 'bella')") shouldBe 0 // no cross-tenant leak

                    // Tenant-9: symmetric — Argos's decision restricts to bob+bella, denies tenant-7's rows.
                    val whereB = policyWhere(engine, userId = "tenant-9:bob")
                    countWhere(c, whereB) shouldBe 2
                    countWhere(c, "$whereB AND name IN ('alice', 'amir')") shouldBe 0
                }
            }
        }
    })

/** Build a db.dbo.customers scan — the `tenant_isolation` policy matches the `DB`/`dbo` namespace. */
private fun customersScan(): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(
            TableScanNode.newBuilder().setTable(
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName("customers"),
            ),
        ).build()

/**
 * Ask Argos to evaluate policies for [userId] and render its single tenant-isolation `eq` predicate
 * as a SQL `WHERE` fragment — the trivial unparse the worker does downstream, driven entirely by
 * Argos's emitted column + literal.
 */
private fun policyWhere(
    engine: PolicyEngine,
    userId: String,
): String {
    val resp =
        runBlocking {
            engine.evaluatePolicies(
                EvaluatePoliciesRequest
                    .newBuilder()
                    .setPlan(customersScan())
                    .setContext(PipelineContext.newBuilder().setUserId(userId))
                    .build(),
            )
        }
    require(resp.predicatesList.size == 1) { "expected exactly one tenant-isolation predicate, got ${resp.predicatesList.size}" }
    val fn = resp.predicatesList[0].predicate.function
    require(fn.operation == "eq") { "expected an `eq` predicate, got ${fn.operation}" }
    val column = fn.operandsList[0].columnRef.name
    val literal = fn.operandsList[1].literal.stringValue
    return "\"$column\" = '$literal'"
}

private fun countWhere(
    c: Connection,
    where: String?,
): Int {
    val sql = "SELECT count(*) FROM customers" + (where?.let { " WHERE $it" } ?: "")
    return c.createStatement().use { st ->
        st.executeQuery(sql).use {
            it.next()
            it.getInt(1)
        }
    }
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
