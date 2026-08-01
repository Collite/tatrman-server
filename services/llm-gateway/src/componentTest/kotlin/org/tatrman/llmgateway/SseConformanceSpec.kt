// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.llmgateway.auth.sha256Hex
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey

/**
 * LG-P2·S2·T7 — the streaming P-1 runtime smoke: a `stream:true` chat completion proxied through the
 * gateway against a REAL upstream (WireMock, in-JVM) serving a `text/event-stream` body. Proves the
 * FI-4 tool-call intent (streamed `tool_calls` argument fragments reassemble into the exact JSON, index
 * integrity intact) and documents the LG-P2·S2 before-first-token behavior (SSE error frame, not HTTP
 * status — the status-vs-frame retry distinction lands LG-P3·S2). Byte-boundary correctness (utf8-split)
 * and the tap observation script are proven at the unit tier (SseByteParserSpec / TapParserSpec).
 */
class SseConformanceSpec :
    StringSpec({

        val key = "ttrk-sse-conf-key-000000000000000000000000"
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())

        beforeSpec { wm.start() }
        afterSpec { wm.stop() }

        fun gateway(): GatewayConfig {
            val base = ConfigLoader.loadFromResources()
            val providers =
                base.providers.providers.mapValues { (_, p) ->
                    if (p.kind == "openai-wire") p.copy(baseUrl = wm.baseUrl()) else p
                }
            return base.copy(
                governance =
                    base.governance.copy(
                        keys =
                            base.governance.keys + SeededKey("golem", "sse", sha256Hex(key)),
                    ),
                providers = base.providers.copy(providers = providers),
            )
        }

        val toolCallStream =
            listOf(
                """{"id":"c","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}""",
                """{"id":"c","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]},"finish_reason":null}]}""",
                """{"id":"c","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Bratislava\"}"}}]},"finish_reason":null}]}""",
                """{"id":"c","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ).joinToString("") { "data: $it\n\n" }

        /** Reassemble tool-call arguments from the gateway's SSE output — the FI-4 property a client SDK relies on. */
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

        "streamed tool_calls reassemble into the exact arguments JSON with index integrity (FI-4)" {
            wm.resetAll()
            wm.stubFor(
                post(urlPathMatching("/openai/deployments/.*/chat/completions")).willReturn(
                    aResponse()
                        .withStatus(
                            200,
                        ).withHeader("Content-Type", "text/event-stream")
                        .withBody(toolCallStream),
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
                                """{"model":"gpt-4o","stream":true,"messages":[{"role":"user","content":"weather?"}]}""",
                            )
                        }.bodyAsText()

                reassembleToolArgs(body) shouldBe """{"city":"Bratislava"}"""
                body shouldContain "\"finish_reason\":\"tool_calls\""
                body shouldContain "data: [DONE]"
            }
        }

        "before-first-token upstream 429 → real HTTP 429 (S2 deviation resolved by the P3·S2 engine)" {
            wm.resetAll()
            wm.stubFor(
                post(urlPathMatching("/openai/deployments/.*/chat/completions")).willReturn(
                    aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"error":{"message":"slow down","type":"rate_limit_error","code":"rate_limit_exceeded"}}""",
                        ),
                ),
            )
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway()) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody("""{"model":"gpt-4o","stream":true,"messages":[{"role":"user","content":"hi"}]}""")
                    }
                // P3·S2: the attempt loop runs BEFORE the SSE writer attaches, so an all-exhausted stream
                // (gpt-4o has no fallback chain) commits a proper HTTP status — NOT a 200 + in-band error frame.
                res.status shouldBe HttpStatusCode.TooManyRequests
                val body = res.bodyAsText()
                body shouldContain "rate_limit"
                body shouldNotContain "data: [DONE]" // no SSE was ever committed
            }
        }
    })
