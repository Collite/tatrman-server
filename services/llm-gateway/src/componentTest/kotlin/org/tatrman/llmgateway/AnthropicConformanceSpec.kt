// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.llmgateway.auth.sha256Hex
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey

/**
 * LG-P2·S3·T6 — the Anthropic converter P-1 runtime smoke against a REAL upstream (WireMock serving the
 * Messages API). Proves the caller sees ONLY OpenAI shapes on both edges: a non-stream round-trip
 * (Anthropic Messages JSON → chat.completion + §1.3 usage), a streamed tool-call round-trip through the
 * converter path (FI-4 over converted traffic), and 529 `overloaded_error` → a retryable-family 502.
 */
class AnthropicConformanceSpec :
    StringSpec({

        val key = "ttrk-anthropic-conf-key-0000000000000000"
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
                            base.governance.keys + SeededKey("golem", "anth", sha256Hex(key)),
                    ),
                providers = base.providers.copy(providers = providers),
            )
        }

        fun reassembleToolArgs(sse: String): String =
            sse
                .lineSequence()
                .filter { it.startsWith("data: ") && it.contains("tool_calls") }
                .mapNotNull { line ->
                    runCatching {
                        Json
                            .parseToJsonElement(line.removePrefix("data: "))
                            .jsonObject["choices"]!!
                            .jsonArray[0]
                            .jsonObject["delta"]!!
                            .jsonObject["tool_calls"]!!
                            .jsonArray[0]
                            .jsonObject["function"]!!
                            .jsonObject["arguments"]!!
                            .jsonPrimitive.content
                    }.getOrNull()
                }.joinToString("")

        "non-stream: Anthropic Messages response → OpenAI chat.completion + §1.3 usage; request is converted" {
            wm.resetAll()
            wm.stubFor(
                post(urlPathEqualTo("/v1/messages")).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"Hi"}],"model":"claude-sonnet-4-6","stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""",
                    ),
                ),
            )
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway()) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody(
                            """{"model":"claude-sonnet-4-6","system":"ignored","messages":[{"role":"system","content":"be brief"},{"role":"user","content":"hi"}]}""",
                        )
                    }
                res.status shouldBe HttpStatusCode.OK
                res.headers["X-Gateway-Provider"] shouldBe "anthropic"
                res.headers["X-Gateway-Model"] shouldBe "claude-sonnet-4-6"

                val body = res.bodyAsText().replace(" ", "")
                body shouldContain "\"object\":\"chat.completion\""
                body shouldContain "\"content\":\"Hi\""
                body shouldContain "\"finish_reason\":\"stop\""
                body shouldContain "\"input_tokens\":10" // §1.3 dual names injected by enrichment
                body shouldContain "\"cost\":"
            }

            // the request reached /v1/messages as a converted Anthropic body (system hoisted, max_tokens added)
            wm.verify(
                postRequestedFor(urlPathEqualTo("/v1/messages"))
                    .withHeader("anthropic-version", equalTo("2023-06-01"))
                    .withRequestBody(containing("\"max_tokens\""))
                    .withRequestBody(containing("\"system\":\"be brief\"")),
            )
        }

        "stream: Anthropic tool_use events → OpenAI chunks; args reassemble to exact JSON (FI-4, converter path)" {
            wm.resetAll()
            val toolStream =
                listOf(
                    "event: message_start" to
                        """{"type":"message_start","message":{"id":"m","role":"assistant","usage":{"input_tokens":3,"output_tokens":0}}}""",
                    "event: content_block_start" to
                        """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu1","name":"get_weather","input":{}}}""",
                    "event: content_block_delta" to
                        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""",
                    "event: content_block_delta" to
                        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"BA\"}"}}""",
                    "event: message_delta" to
                        """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":8}}""",
                    "event: message_stop" to """{"type":"message_stop"}""",
                ).joinToString("") { (ev, data) -> "$ev\ndata: $data\n\n" }

            wm.stubFor(
                post(urlPathEqualTo("/v1/messages")).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(toolStream),
                ),
            )
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway()) }

                val body =
                    client
                        .post("/v1/chat/completions") {
                            header(HttpHeaders.Authorization, "Bearer $key")
                            setBody(
                                """{"model":"claude-sonnet-4-6","stream":true,"messages":[{"role":"user","content":"weather?"}]}""",
                            )
                        }.bodyAsText()

                reassembleToolArgs(body) shouldBe """{"city":"BA"}"""
                body shouldContain "\"finish_reason\":\"tool_calls\""
                body shouldContain "data: [DONE]"
            }
        }

        "529 overloaded_error (non-stream) → retryable-family 502 upstream_error carrying the original status (C-3)" {
            wm.resetAll()
            wm.stubFor(
                post(urlPathEqualTo("/v1/messages")).willReturn(
                    aResponse()
                        .withStatus(529)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}"""),
                ),
            )
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway()) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody("""{"model":"claude-sonnet-4-6","messages":[{"role":"user","content":"hi"}]}""")
                    }
                res.status shouldBe HttpStatusCode.BadGateway // Provider5xx → 502 (retry/fallback decides in LG-P3)
                res.bodyAsText() shouldContain "529"
            }
        }
    })
