// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.tatrman.llmgateway.auth.sha256Hex
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey

/**
 * LG-P3·S2·T5/T8 — the hero shape (design §7, clauses 3–5), streaming: a tier **alias** resolves to an
 * azure model; azure fails **before the first token** (429); the engine replays the SAME request through
 * the fallback chain to the **Anthropic converter**, which streams cleanly. Because the attempt loop runs
 * BEFORE the SSE writer attaches, the client sees exactly ONE seamless stream from the serving provider —
 * no azure error frame leaks, and the served provider/model are surfaced (C-4). Additional §7 clauses join
 * this spec at LG-P5·S2 (HERO stage).
 */
class HeroScenarioSpec :
    StringSpec({

        val key = "ttrk-hero-conf-key-000000000000000000000"
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())

        beforeSpec { wm.start() }
        afterSpec { wm.stop() }

        fun gateway(): GatewayConfig {
            val base = ConfigLoader.loadFromResources()
            // gpt-4 (alias → gpt-4o, azure) falls back to the Anthropic converter
            val models =
                base.catalog.models.map {
                    if (it.id == "azure-gpt-4o") it.copy(fallback = listOf("anthropic-sonnet-4-6")) else it
                }
            val providers = base.providers.providers.mapValues { (_, p) -> p.copy(baseUrl = wm.baseUrl()) }
            return base.copy(
                catalog = base.catalog.copy(models = models),
                governance =
                    base.governance.copy(
                        keys =
                            base.governance.keys + SeededKey("golem", "hero", sha256Hex(key)),
                    ),
                providers = base.providers.copy(providers = providers),
            )
        }

        "hero (stream): alias → azure fails pre-first-token → chain replays through the converter → one clean stream" {
            wm.resetAll()
            // azure fails before any frame (429) → engine falls back, no bytes committed yet
            wm.stubFor(
                post(urlPathMatching("/openai/deployments/.*/chat/completions")).willReturn(
                    aResponse().withStatus(429).withHeader("Content-Type", "application/json").withBody(
                        """{"error":{"message":"slow down","type":"rate_limit_error","code":"rate_limit_exceeded"}}""",
                    ),
                ),
            )
            // the Anthropic converter path streams the real answer
            val anthropicStream =
                listOf(
                    "event: message_start" to
                        """{"type":"message_start","message":{"id":"m","role":"assistant","usage":{"input_tokens":6,"output_tokens":0}}}""",
                    "event: content_block_start" to
                        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                    "event: content_block_delta" to
                        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hero answer"}}""",
                    "event: message_delta" to
                        """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}""",
                    "event: message_stop" to """{"type":"message_stop"}""",
                ).joinToString("") { (ev, data) -> "$ev\ndata: $data\n\n" }
            wm.stubFor(
                post(urlPathEqualTo("/v1/messages")).willReturn(
                    aResponse()
                        .withStatus(
                            200,
                        ).withHeader("Content-Type", "text/event-stream")
                        .withBody(anthropicStream),
                ),
            )

            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway()) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody("""{"model":"gpt-4","stream":true,"messages":[{"role":"user","content":"hi"}]}""")
                    }
                res.status shouldBe HttpStatusCode.OK
                // the SERVING provider/model are surfaced — the fallback is honest, never silent (C-4)
                res.headers["X-Gateway-Provider"] shouldBe "anthropic"
                res.headers["X-Gateway-Model"] shouldBe "claude-sonnet-4-6"

                val body = res.bodyAsText()
                body shouldContain "\"content\":\"hero answer\"" // the converter's stream reached the client
                body shouldContain "data: [DONE]"
                body shouldContain "\"role\":\"assistant\"" // one clean OpenAI stream (role delta first)
                // the failed azure attempt left NO trace in the client stream (attach happened after fallback)
                body shouldNotContain "rate_limit"
                body shouldNotContain "slow down"
            }
        }
    })
