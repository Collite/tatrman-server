// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.admin

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey
import org.tatrman.llmgateway.governance.KeyMint
import org.tatrman.llmgateway.module
import org.testcontainers.containers.PostgreSQLContainer

/**
 * LG-P4·S3·T3/T5 — attribution headers (D-2, contracts §1.2) + the Hebe-shape regression (G-3, GI-1).
 * `X-Cost-Center` must be prefix-valid for the key's team (a key can't charge a foreign bucket → 400);
 * absent ⇒ the team default. `X-Turn-Ref` is trace-only and MUST NOT become a metric label (negative
 * assert on `/metrics`). Hebe's exact call shape authenticates + is served unchanged (zero caller changes).
 */
class AttributionSpec :
    StringSpec({

        val pgc =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())

        val golemKey = KeyMint.generate()
        val hebeKey = KeyMint.generate()
        lateinit var cfg: Config

        fun gateway(): GatewayConfig {
            val base = ConfigLoader.loadFromResources()
            return base.copy(
                providers =
                    base.providers.copy(
                        providers =
                            base.providers.providers.mapValues { (_, p) ->
                                p.copy(baseUrl = wm.baseUrl())
                            },
                    ),
                governance =
                    base.governance.copy(
                        keys =
                            base.governance.keys +
                                SeededKey("golem", "golem-k", KeyMint.hash(golemKey)) +
                                SeededKey("hebe", "hebe-k", KeyMint.hash(hebeKey)),
                    ),
            )
        }

        beforeSpec {
            pgc.start()
            wm.start()
            wm.stubFor(
                post(urlPathMatching("/openai/deployments/.*/chat/completions")).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":4,"total_tokens":12}}""",
                    ),
                ),
            )
            cfg =
                ConfigFactory
                    .parseString(
                        """db { enabled = true, host = "${pgc.host}", port = "${pgc.firstMappedPort}", database = "${pgc.databaseName}", user = "${pgc.username}", password = "${pgc.password}" }""",
                    ).withFallback(ConfigFactory.load())
                    .resolve()
        }
        afterSpec {
            pgc.stop()
            wm.stop()
        }

        val body = """{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}"""

        "X-Cost-Center within the key's team is accepted; a foreign bucket is rejected 400; absent ⇒ default" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                // same-team prefix → accepted
                client
                    .post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $golemKey")
                        header("X-Cost-Center", "golem/analytics")
                        setBody(body)
                    }.status shouldBe HttpStatusCode.OK

                // foreign bucket on golem's key → 400 (before any upstream work)
                val foreign =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $golemKey")
                        header("X-Cost-Center", "hebe/analytics")
                        setBody(body)
                    }
                foreign.status shouldBe HttpStatusCode.BadRequest
                foreign.bodyAsText() shouldContain "must start with"

                // absent header → team default (accepted)
                client
                    .post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $golemKey")
                        setBody(body)
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        "X-Turn-Ref is trace-only and never becomes a metric label" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                client
                    .post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $golemKey")
                        header("X-Turn-Ref", "turn-SECRET-abc123")
                        setBody(body)
                    }.status shouldBe HttpStatusCode.OK

                // the turn ref must not have leaked into any Prometheus label (D-2 — trace-only)
                client.get("/metrics").bodyAsText() shouldNotContain "turn-SECRET-abc123"
            }
        }

        "Hebe's exact call shape authenticates and is served (zero caller changes, G-3/GI-1)" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                client
                    .post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $hebeKey")
                        header("X-Cost-Center", "hebe/instance-7")
                        header("X-Turn-Ref", "turn-42")
                        header("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                        setBody(body)
                    }.status shouldBe HttpStatusCode.OK
            }
        }
    })
