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

/**
 * LG-P1·S1 — runtime proof that the Ktor 3 skeleton actually serves its endpoints (P-1: claims match
 * runtime). `/health/ready` is asserted **NOT_READY** here on purpose: the PG+Redis store probes land
 * in T4, so a truthful skeleton must not fake-green readiness (F-1). When T4 wires the probes this
 * spec flips to assert the real ready/unready behaviour.
 *
 * `environment { config = MapApplicationConfig() }` clears Ktor's auto-module loading (the
 * `application.conf` `ktor.application.modules` list) so the module is invoked once, explicitly, with
 * the typesafe [com.typesafe.config.Config] it actually reads.
 */
class HealthSpec :
    StringSpec({

        "live + health are UP; ready is truthfully NOT_READY until stores are wired (T4)" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load()) }

                client.get("/health").let {
                    it.status shouldBe HttpStatusCode.OK
                    it.bodyAsText() shouldContain "UP"
                }
                client.get("/health/live").status shouldBe HttpStatusCode.OK

                client.get("/health/ready").let {
                    it.status shouldBe HttpStatusCode.ServiceUnavailable
                    val body = it.bodyAsText()
                    body shouldContain "NOT_READY"
                    body shouldContain "postgres" // truthful per-store checks, not a fake status
                    body shouldContain "DOWN" // storeless boot → stores DOWN, never fake-green
                }
            }
        }

        "providers reports none registered (not fake-green) and metrics scrapes" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load()) }

                client.get("/health/providers").let {
                    it.status shouldBe HttpStatusCode.OK
                    it.bodyAsText() shouldContain "providers"
                }
                client.get("/metrics").status shouldBe HttpStatusCode.OK
            }
        }
    })
