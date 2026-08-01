// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer

/**
 * LG-P1·S1·T6 — the phase-closing runtime proof (P-1): the module boots from config against a REAL
 * Postgres + Redis, Flyway `V1` applies, and `/health/ready` truthfully reports **UP** (the reachable
 * path the unit HealthSpec/StoreProbeSpec cannot prove). Runs in the `componentTest` tier
 * (`just test-component`) — Docker required; the mocked `test` PR gate stays Docker-free.
 */
class BootComponentSpec :
    StringSpec({

        // username = tatrman so V1's `GRANT ... TO tatrman` resolves (the 1.x migration grants to that role).
        val pg =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")
        val redis = GenericContainer("redis:7-alpine").apply { withExposedPorts(6379) }

        beforeSpec {
            pg.start()
            redis.start()
        }
        afterSpec {
            pg.stop()
            redis.stop()
        }

        "boots against real PG+Redis, applies Flyway V1, and /health/ready is truthfully UP" {
            // Override only the store connections; ktor{}/telemetry/db.type come from the real application.conf.
            val cfg =
                ConfigFactory
                    .parseString(
                        """
                        db {
                            enabled = true
                            host = "${pg.host}"
                            port = "${pg.firstMappedPort}"
                            database = "${pg.databaseName}"
                            user = "${pg.username}"
                            password = "${pg.password}"
                        }
                        redis {
                            enabled = true
                            host = "${redis.host}"
                            port = ${redis.getMappedPort(6379)}
                        }
                        """.trimIndent(),
                    ).withFallback(ConfigFactory.load())
                    .resolve()

            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg) }

                val ready = client.get("/health/ready")
                ready.status shouldBe HttpStatusCode.OK
                val body = ready.bodyAsText()
                body shouldContain "UP"
                body shouldContain "postgres"
                body shouldContain "redis"

                // data plane is virtual-key gated (D-1) even in a real-store boot — no key → 401
                client.get("/v1/models").status shouldBe HttpStatusCode.Unauthorized
            }

            // Flyway V1 actually ran at boot — the 1.x prompt_logs table exists.
            pg.createConnection("").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT to_regclass('public.prompt_logs') IS NOT NULL").use { rs ->
                        rs.next()
                        rs.getBoolean(1) shouldBe true
                    }
                }
            }
        }
    })
