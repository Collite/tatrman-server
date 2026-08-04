// SPDX-License-Identifier: Apache-2.0
package shared.ktor

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/**
 * RV-P1.6 — S-3: an operator endpoint is **never unauthenticated in the open offering**.
 *
 * The grounding kernels (chrono/money/geo) each grew a `POST /refresh` that reloads their `ground:`
 * slice. lex-matcher's `/refresh` was admin-gated from the start (RG-P2.S2.T6); these assert the
 * same gate now guards the kernels', from the one shared implementation.
 */
class AdminGateSpec :
    StringSpec({

        val config =
            ConfigFactory.parseString(
                """
                security { admin-api-keys = "key-1, key-2" }
                """.trimIndent(),
            )

        fun io.ktor.server.application.Application.gatedModule() {
            routing {
                get("/health") { call.respondText("UP") }
                route("/refresh") {
                    adminOnly(config) {
                        post { call.respondText("reloaded") }
                    }
                }
            }
        }

        "an unauthenticated refresh is refused" {
            testApplication {
                application { gatedModule() }
                client.post("/refresh").status shouldBe HttpStatusCode.Forbidden
            }
        }

        "the admin role authorizes" {
            testApplication {
                application { gatedModule() }
                val res = client.post("/refresh") { header("X-Roles", "reader,admin") }
                res.status shouldBe HttpStatusCode.OK
            }
        }

        "an admin API key authorizes" {
            testApplication {
                application { gatedModule() }
                client.post("/refresh") { header("X-API-Key", "key-2") }.status shouldBe HttpStatusCode.OK
            }
        }

        "a non-admin role and a wrong key are both refused" {
            testApplication {
                application { gatedModule() }
                client.post("/refresh") { header("X-Roles", "reader") }.status shouldBe HttpStatusCode.Forbidden
                client.post("/refresh") { header("X-API-Key", "nope") }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        // SV-P3·F1: the interceptor is installed on the route it is called on. If it ever reached
        // the root, the probes would start demanding an admin role and the pods would go unready.
        "the gate does not reach the probes" {
            testApplication {
                application { gatedModule() }
                client.get("/health").status shouldBe HttpStatusCode.OK
            }
        }

        "with no admin keys configured, role authority still works and keys do not" {
            val noKeys = ConfigFactory.parseString("security {}")
            testApplication {
                application {
                    routing {
                        route("/refresh") { adminOnly(noKeys) { post { call.respondText("reloaded") } } }
                    }
                }
                client.post("/refresh") { header("X-Roles", "admin") }.status shouldBe HttpStatusCode.OK
                client.post("/refresh") { header("X-API-Key", "anything") }.status shouldBe HttpStatusCode.Forbidden
            }
        }
    })
