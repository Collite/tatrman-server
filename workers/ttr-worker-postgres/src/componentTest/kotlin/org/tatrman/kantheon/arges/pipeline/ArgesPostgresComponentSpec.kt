package org.tatrman.kantheon.arges.pipeline

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.kantheon.testkit.containers.Containers
import java.math.BigDecimal
import java.util.UUID

/**
 * Stage 1.3 T3 — the real-Postgres round-trip: a Proteus-emitted SQL string executed through the
 * **actual Arges pipeline** ([ExecutePipeline]) against a **real Postgres** in Testcontainers,
 * under the RLS session contract, asserting the Arrow result shape + values. This is what catches
 * real-dialect / type-mapping / RLS-binding divergences the mocked unit specs can't.
 *
 * Postgres is native multi-arch, so no `@EnabledIf(CiOnly)` gate is needed (unlike Brontes/MSSQL).
 * The translator is faked exactly as the unit spec does — no real Proteus needed.
 */
@Tags("component")
class ArgesPostgresComponentSpec :
    StringSpec({

        "a Proteus-emitted SELECT runs under RLS against real Postgres and yields the tenant's Arrow rows" {
            Containers.postgres().use { pg ->
                pg.start()

                val tenant = UUID.randomUUID()
                ArgesPgFixture.connect(pg.jdbcUrl, pg.username, pg.password).use { su ->
                    ArgesPgFixture.provision(su)
                    ArgesPgFixture.insert(su, tenant, 1001L, "123.4500", "alpha")
                    ArgesPgFixture.insert(su, tenant, 1002L, "67.8900", "beta")
                }

                val pool = ArgesComponentSupport.pool(pg.jdbcUrl, pg.databaseName)
                val batches =
                    try {
                        ArgesComponentSupport.execute(pool, tenant)
                    } finally {
                        pool.close()
                    }

                // No error surfaced; the first data batch announces the schema fingerprint.
                batches.flatMap { it.messagesList }.any { it.severity == Severity.ERROR } shouldBe false
                batches.sumOf { it.batchRowCount } shouldBe 2L
                batches.first { !it.arrowIpc.isEmpty }.schemaFingerprint.isNotBlank() shouldBe true

                // The decoded Arrow rows round-trip exactly, incl. the NUMERIC(20,4) → Decimal128(20,4).
                val rows = ArgesComponentSupport.decode(batches)
                rows shouldContainExactly
                    listOf(
                        PositionRow(1001L, BigDecimal("123.4500"), "alpha"),
                        PositionRow(1002L, BigDecimal("67.8900"), "beta"),
                    )
            }
        }
    })
