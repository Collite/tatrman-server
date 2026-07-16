// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.api

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.telemetry.FuzzyTelemetry

/**
 * Regression for the interceptor-scope bug (SV-P3·F1). `adminOnly`/`secured` used to install
 * their interceptor on the routing ROOT, so the "admin role required → 403" check leaked onto
 * EVERY sibling route — the /health + /ready probes 403'd and the pod crashlooped. The
 * interceptors are now scoped under `route("/refresh")` / `route("/match")`, so:
 *   - the health probes are reachable unauthenticated (no leak), and
 *   - the admin gate on /refresh still holds (regardless of `security.enabled`, per S-3).
 *
 * The probe routes here mirror the ones the module registers on the same root AFTER
 * configureRoutes — that co-registration on the shared root is exactly what the old bug broke.
 */
class RouteScopeTest :
    StringSpec({

        fun cfg() =
            AppConfig(
                serverPort = 7104,
                grpcPort = 7204,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        val loader =
            object : LoaderSource {
                override suspend fun loadNextCache() =
                    mapOf("product" to listOf(Candidate.fromValues("p-octavia", "Škoda Octavia")))
            }

        // security.enabled=false ⇒ `secured` is inert; the admin gate on /refresh is enforced
        // regardless (S-3). An admin API key is configured so that lane authorizes too.
        val securityConfig =
            ConfigFactory.parseString(
                """security { enabled = false, admin-api-keys = "admin-key-1" }""",
            )

        "health + ready probes are reachable without admin (the interceptor does not leak to root)" {
            val repo = StringRepository(cfg(), loader)
            runBlocking { repo.forceRefresh() }
            val matcher = FuzzyMatcher(repo)

            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    configureRoutes(matcher, repo, FuzzyTelemetry(), securityConfig)
                    routing {
                        get("/health") { call.respond(buildJsonObject { put("status", JsonPrimitive("UP")) }) }
                        get("/ready") {
                            if (repo.isCatalogReady()) {
                                call.respond(HttpStatusCode.OK)
                            } else {
                                call.respond(HttpStatusCode.ServiceUnavailable)
                            }
                        }
                    }
                }

                client.get("/health").status shouldBe HttpStatusCode.OK
                client.get("/ready").status shouldNotBe HttpStatusCode.Forbidden
            }
        }

        "the admin gate on /refresh still refuses a non-admin caller (403)" {
            val repo = StringRepository(cfg(), loader)
            val matcher = FuzzyMatcher(repo)

            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    configureRoutes(matcher, repo, FuzzyTelemetry(), securityConfig)
                }
                client.post("/refresh").status shouldBe HttpStatusCode.Forbidden
            }
        }

        "the admin gate on /refresh admits an admin-role caller" {
            val repo = StringRepository(cfg(), loader)
            runBlocking { repo.forceRefresh() }
            val matcher = FuzzyMatcher(repo)

            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    configureRoutes(matcher, repo, FuzzyTelemetry(), securityConfig)
                }
                client.post("/refresh") { header("X-Roles", "admin") }.status shouldBe HttpStatusCode.OK
            }
        }
    })
