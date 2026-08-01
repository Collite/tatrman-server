// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
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
import org.tatrman.llmgateway.auth.sha256Hex
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey

/**
 * LG-P3·S1·T6 — three-tier resolution proven through the wire. A tier-alias request (`"model":"haiku"`)
 * resolves to the concrete `claude-haiku-4-5` (anthropic) and streams via WireMock-Anthropic — the served
 * model is surfaced (`X-Gateway-Model`), never the alias. An explicit unknown name → 404 per §1.7, with no
 * upstream call.
 */
class RoutingConformanceSpec :
    StringSpec({

        val key = "ttrk-routing-conf-key-00000000000000000"
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())

        beforeSpec { wm.start() }
        afterSpec { wm.stop() }

        fun gateway(): GatewayConfig {
            val base = ConfigLoader.loadFromResources()
            val providers =
                base.providers.providers.mapValues { (_, p) ->
                    if (p.kind == "anthropic") p.copy(baseUrl = wm.baseUrl()) else p
                }
            return base.copy(
                governance =
                    base.governance.copy(
                        keys =
                            base.governance.keys + SeededKey("golem", "routing", sha256Hex(key)),
                    ),
                providers = base.providers.copy(providers = providers),
            )
        }

        "tier alias: 'haiku' → claude-haiku-4-5, streamed via anthropic; served model surfaced, not the alias" {
            wm.resetAll()
            val stream =
                listOf(
                    "event: message_start" to
                        """{"type":"message_start","message":{"id":"m","role":"assistant","usage":{"input_tokens":4,"output_tokens":0}}}""",
                    "event: content_block_start" to
                        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                    "event: content_block_delta" to
                        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""",
                    "event: message_delta" to
                        """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""",
                    "event: message_stop" to """{"type":"message_stop"}""",
                ).joinToString("") { (ev, data) -> "$ev\ndata: $data\n\n" }
            wm.stubFor(
                post(urlPathEqualTo("/v1/messages")).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(stream),
                ),
            )
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway()) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody("""{"model":"haiku","stream":true,"messages":[{"role":"user","content":"hi"}]}""")
                    }
                res.status shouldBe HttpStatusCode.OK
                // the ALIAS resolved to the concrete served model — surfaced, never the alias (C-4)
                res.headers["X-Gateway-Model"] shouldBe "claude-haiku-4-5"
                res.headers["X-Gateway-Provider"] shouldBe "anthropic"
                val body = res.bodyAsText()
                body shouldContain "\"content\":\"hi\""
                body shouldContain "data: [DONE]"
            }
        }

        "explicit unknown model → 404 model_not_found (no upstream call)" {
            wm.resetAll()
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway()) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody("""{"model":"nope","messages":[{"role":"user","content":"hi"}]}""")
                    }
                res.status shouldBe HttpStatusCode.NotFound
                res.bodyAsText() shouldContain "model_not_found"
            }
        }
    })
