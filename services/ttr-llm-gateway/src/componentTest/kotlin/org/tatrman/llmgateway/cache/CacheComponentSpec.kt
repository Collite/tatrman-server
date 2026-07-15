// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.cache

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.exactly
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.RateLimitDef
import org.tatrman.llmgateway.config.SeededKey
import org.tatrman.llmgateway.config.Team
import org.tatrman.llmgateway.governance.BudgetUsageRepo
import org.tatrman.llmgateway.governance.KeyMint
import org.tatrman.llmgateway.module
import org.tatrman.llmgateway.store.Pg
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * LG-P5·S1·T2/T5/T6 — the exact-match cache through the wire (E-1). Real PG + Redis + a WireMock upstream.
 * Proves: a non-stream miss stores and the second identical call is a hit that hits the upstream ZERO more
 * times AND does not charge the budget (settle-as-cached); a stream miss is served on replay from a
 * synthetic stream; `X-Gateway-Cache: bypass` re-hits the upstream; and a would-be hit still spends a
 * rate-limit token (admission runs before the cache). Assertions are data-flow (upstream call counts +
 * budget rows), not implementation detail.
 */
class CacheComponentSpec :
    StringSpec({

        val pgc =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")
        val redisC = GenericContainer("redis:7-alpine").apply { withExposedPorts(6379) }
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())

        val key = KeyMint.generate()
        val tinyKey = KeyMint.generate()
        lateinit var cfg: Config
        lateinit var budgets: BudgetUsageRepo
        val month: LocalDate = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)

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
                        teams =
                            base.governance.teams +
                                Team(
                                    "tiny",
                                    "tiny/",
                                    base.governance.teams
                                        .first()
                                        .budget,
                                    RateLimitDef("tinyrl", 1),
                                ),
                        keys =
                            base.governance.keys +
                                SeededKey("golem", "cache-k", KeyMint.hash(key)) +
                                SeededKey("tiny", "tiny-k", KeyMint.hash(tinyKey)),
                    ),
            )
        }

        val nonStreamBody =
            """{"id":"live-1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"live answer"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        val streamBody =
            buildString {
                append("data: {\"id\":\"s1\",\"object\":\"chat.completion.chunk\",")
                append("\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"streamed hi\"},")
                append("\"finish_reason\":null}]}\n\n")
                append("data: {\"id\":\"s1\",\"object\":\"chat.completion.chunk\",")
                append("\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                append("data: [DONE]\n\n")
            }

        fun stubAzureNonStream() =
            wm.stubFor(
                post(urlPathMatching("/openai/deployments/.*/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(
                                200,
                            ).withHeader("Content-Type", "application/json")
                            .withBody(nonStreamBody),
                    ),
            )

        fun stubAzureStream() =
            wm.stubFor(
                post(urlPathMatching("/openai/deployments/.*/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(
                                200,
                            ).withHeader("Content-Type", "text/event-stream")
                            .withBody(streamBody),
                    ),
            )

        beforeSpec {
            pgc.start()
            redisC.start()
            wm.start()
            cfg =
                ConfigFactory
                    .parseString(
                        """
                        db { enabled = true, host = "${pgc.host}", port = "${pgc.firstMappedPort}", database = "${pgc.databaseName}", user = "${pgc.username}", password = "${pgc.password}" }
                        redis { enabled = true, host = "${redisC.host}", port = ${redisC.getMappedPort(6379)} }
                        """.trimIndent(),
                    ).withFallback(ConfigFactory.load())
                    .resolve()
            budgets = BudgetUsageRepo(Pg.fromConfig(cfg).also { it.migrate() }.db)
        }
        afterSpec {
            pgc.stop()
            redisC.stop()
            wm.stop()
        }

        fun chat(
            content: String,
            stream: Boolean = false,
        ) =
            """{"model":"gpt-4o","messages":[{"role":"user","content":"$content"}]${if (stream) ",\"stream\":true" else ""}}"""

        "non-stream: a second identical request is a cache hit — upstream called once, budget not charged again" {
            wm.resetAll()
            stubAzureNonStream()
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                val first =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody(chat("cache-me"))
                    }
                first.status shouldBe HttpStatusCode.OK
                Json
                    .parseToJsonElement(first.bodyAsText())
                    .jsonObject["cached"]!!
                    .jsonPrimitive.content shouldBe
                    "false"
                val usedAfterFirst = budgets.usedUsd("golem", month)

                val second =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody(chat("cache-me"))
                    }
                second.status shouldBe HttpStatusCode.OK
                second.headers["X-Gateway-Cache"] shouldBe "hit"
                Json
                    .parseToJsonElement(second.bodyAsText())
                    .jsonObject["cached"]!!
                    .jsonPrimitive.content shouldBe
                    "true"
                second.bodyAsText() shouldContain "live answer" // served from the stored body
                // the cache hit did not add to budget_usage (settle-as-cached), while the first call did charge
                (usedAfterFirst > 0.0) shouldBe true
                budgets.usedUsd("golem", month) shouldBe usedAfterFirst
            }
            // upstream hit exactly once across the two calls
            wm.verify(exactly(1), postRequestedFor(urlPathMatching("/openai/deployments/.*/chat/completions")))
        }

        "stream: a second identical request is served as a synthetic replay — no second upstream call" {
            wm.resetAll()
            stubAzureStream()
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                val first =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody(chat("stream-me", stream = true))
                    }
                first.status shouldBe HttpStatusCode.OK
                first.bodyAsText() shouldContain "streamed hi"

                val second =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody(chat("stream-me", stream = true))
                    }
                second.status shouldBe HttpStatusCode.OK
                val body = second.bodyAsText()
                body shouldContain "streamed hi" // replayed content
                body shouldContain "\"cached\":true"
                body shouldContain "[DONE]"
            }
            wm.verify(exactly(1), postRequestedFor(urlPathMatching("/openai/deployments/.*/chat/completions")))
        }

        "X-Gateway-Cache: bypass skips the read and re-hits the upstream" {
            wm.resetAll()
            stubAzureNonStream()
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                repeat(1) {
                    client
                        .post("/v1/chat/completions") {
                            header(HttpHeaders.Authorization, "Bearer $key")
                            setBody(chat("bypass-me"))
                        }.status shouldBe HttpStatusCode.OK
                }
                // bypass → read skipped → live upstream again (even though the entry exists)
                client
                    .post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        header("X-Gateway-Cache", "bypass")
                        setBody(chat("bypass-me"))
                    }.status shouldBe HttpStatusCode.OK
            }
            wm.verify(exactly(2), postRequestedFor(urlPathMatching("/openai/deployments/.*/chat/completions")))
        }

        "a would-be cache hit still spends a rate-limit token (admission runs before the cache)" {
            wm.resetAll()
            stubAzureNonStream()
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                // tiny team: rpm=1 → first call OK (populates cache), second is rate-limited before the cache read
                client
                    .post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $tinyKey")
                        setBody(chat("rl-me"))
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $tinyKey")
                        setBody(chat("rl-me"))
                    }.status shouldBe HttpStatusCode.TooManyRequests
            }
        }
    })
