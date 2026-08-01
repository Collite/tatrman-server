// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey
import org.tatrman.llmgateway.module
import org.tatrman.llmgateway.store.Pg
import org.testcontainers.containers.PostgreSQLContainer

/**
 * LG-P4·S1·T7 — stage smoke through the wire (P-1). A PG-backed boot: a `governance.yaml` seeded key
 * authenticates `/v1/models`; a key issued at runtime (via the service over the same DB) works on first
 * use (positive-only cache → no stale negative); a revoked key is rejected 401 within the TTL. Real
 * Postgres (Testcontainers), Redis disabled (this path needs neither Redis nor upstreams).
 */
class GovernanceSmokeSpec :
    StringSpec({

        val pgc =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")

        // A seeded key whose plaintext we hold in the test; its hash goes into governance (as at cutover).
        val seededPlaintext = KeyMint.generate()

        lateinit var cfg: com.typesafe.config.Config
        lateinit var service: KeyService

        fun gateway(): GatewayConfig {
            val base = ConfigLoader.loadFromResources()
            return base.copy(
                governance =
                    base.governance.copy(
                        keys = base.governance.keys + SeededKey("golem", "smoke-seed", KeyMint.hash(seededPlaintext)),
                    ),
            )
        }

        beforeSpec {
            pgc.start()
            cfg =
                ConfigFactory
                    .parseString(
                        """
                        db {
                            enabled = true
                            host = "${pgc.host}"
                            port = "${pgc.firstMappedPort}"
                            database = "${pgc.databaseName}"
                            user = "${pgc.username}"
                            password = "${pgc.password}"
                        }
                        """.trimIndent(),
                    ).withFallback(ConfigFactory.load())
                    .resolve()
            // Test-side handle on the SAME database, to issue/revoke out of band during the smoke.
            val pg = Pg.fromConfig(cfg).also { it.migrate() }
            val keys = VirtualKeyRepo(pg.db)
            val teams = TeamRepo(pg.db)
            teams.upsertAll(gateway().governance.teams) // ensure golem exists for issuance FK
            service = KeyService(keys, teams)
        }
        afterSpec { pgc.stop() }

        "seeded key authenticates; unknown/absent 401; issued key works immediately; revoked key 401" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                // seeded key (imported into PG at boot) → authenticated
                client
                    .get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $seededPlaintext") }
                    .status shouldBe HttpStatusCode.OK

                // no key → 401
                client.get("/v1/models").status shouldBe HttpStatusCode.Unauthorized

                // freshly issued key works on first presentation (no negative caching)
                val live = service.issueKey("golem", "smoke-live")
                client
                    .get("/v1/models") { header(HttpHeaders.Authorization, "Bearer ${live.plaintext}") }
                    .status shouldBe HttpStatusCode.OK

                // a key revoked before ever being validated → 401 immediately (never cached)
                val doomed = service.issueKey("golem", "smoke-doomed")
                service.revoke(doomed.row.id) shouldBe true
                client
                    .get("/v1/models") { header(HttpHeaders.Authorization, "Bearer ${doomed.plaintext}") }
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
