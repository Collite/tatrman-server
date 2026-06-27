package org.tatrman.kantheon.arges.pipeline

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.kantheon.testkit.containers.Containers
import java.util.UUID

/**
 * Stage 1.3 T4 — the worker-side twin of Midas-core's `RlsLeakageComponentSpec`: the security
 * guarantee that the Postgres worker cannot leak across tenants. Two tenants' rows are seeded; a
 * query dispatched **through Arges** under tenant A returns **zero** of tenant B's rows (and vice
 * versa), proving the `midas_app_readonly` role does not bypass RLS and the `SET LOCAL` bind scopes
 * every row. Also asserts the fail-closed path: no tenant_id → `tenant_id_required`, nothing runs.
 *
 * Postgres is native multi-arch — no `@EnabledIf(CiOnly)` gate.
 */
@Tags("component")
class RlsLeakageComponentSpec :
    StringSpec({

        "a query under tenant A returns none of tenant B's rows through the worker, and fails closed without a tenant" {
            Containers.postgres().use { pg ->
                pg.start()

                val tenantA = UUID.randomUUID()
                val tenantB = UUID.randomUUID()
                ArgesPgFixture.connect(pg.jdbcUrl, pg.username, pg.password).use { su ->
                    ArgesPgFixture.provision(su)
                    ArgesPgFixture.insert(su, tenantA, 1L, "10.0000", "A-pos-1")
                    ArgesPgFixture.insert(su, tenantA, 2L, "20.0000", "A-pos-2")
                    ArgesPgFixture.insert(su, tenantB, 3L, "30.0000", "B-pos-1")
                    ArgesPgFixture.insert(su, tenantB, 4L, "40.0000", "B-pos-2")
                    ArgesPgFixture.insert(su, tenantB, 5L, "50.0000", "B-pos-3")
                }

                val pool = ArgesComponentSupport.pool(pg.jdbcUrl, pg.databaseName)
                try {
                    // Tenant A sees exactly its two rows — none of B's three.
                    val aRows = ArgesComponentSupport.decode(ArgesComponentSupport.execute(pool, tenantA))
                    aRows.map { it.label } shouldContainExactlyInAnyOrder listOf("A-pos-1", "A-pos-2")
                    aRows.none { it.label.startsWith("B-") } shouldBe true

                    // Tenant B sees exactly its three rows — none of A's two.
                    val bRows = ArgesComponentSupport.decode(ArgesComponentSupport.execute(pool, tenantB))
                    bRows.map { it.label } shouldContainExactlyInAnyOrder listOf("B-pos-1", "B-pos-2", "B-pos-3")
                    bRows.none { it.label.startsWith("A-") } shouldBe true

                    // Fail closed: no tenant_id → the worker rejects up front and runs no query.
                    val closed = ArgesComponentSupport.execute(pool, tenant = null)
                    closed.size shouldBe 1
                    val msg = closed[0].messagesList.first()
                    msg.severity shouldBe Severity.ERROR
                    msg.code shouldBe "tenant_id_required"
                } finally {
                    pool.close()
                }
            }
        }
    })
