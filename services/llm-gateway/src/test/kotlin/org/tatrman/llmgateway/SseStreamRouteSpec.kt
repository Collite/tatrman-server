// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.tatrman.llmgateway.auth.sha256Hex
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey
import org.tatrman.llmgateway.conformance.ConformanceFixtures
import org.tatrman.llmgateway.conformance.SseFixtureServer

/**
 * LG-P2·S2·T5/T6/T7 (transport half) — the SSE data plane end-to-end: a real Ktor client streams
 * `stream:true` through the gateway, whose upstream is a byte-controlled [SseFixtureServer]. Proves the
 * client receives the upstream frames byte-faithfully (usage chunk excepted), heartbeats appear on idle,
 * and a mid-stream drop closes with an error frame + `[DONE]` (§1.4) — never a hang. Byte-boundary
 * correctness (utf8-split) is proven at the framer unit level; here we prove the wired route.
 */
class SseStreamRouteSpec :
    StringSpec({

        val key = "ttrk-sse-route-key-00000000000000000000000"

        /** Point every openai-wire provider at [upstreamBaseUrl]; seed the key; set the heartbeat period. */
        fun gateway(
            upstreamBaseUrl: String,
            heartbeatSeconds: Long = 15,
        ): GatewayConfig {
            val base = ConfigLoader.loadFromResources()
            val providers =
                base.providers.providers.mapValues { (_, p) ->
                    if (p.kind == "openai-wire") p.copy(baseUrl = upstreamBaseUrl) else p
                }
            return base.copy(
                governance =
                    base.governance.copy(
                        keys =
                            base.governance.keys + SeededKey("golem", "sse", sha256Hex(key)),
                    ),
                providers =
                    base.providers.copy(
                        providers = providers,
                        sse = base.providers.sse.copy(heartbeatSeconds = heartbeatSeconds),
                    ),
            )
        }

        fun streamBody(model: String) =
            """{"model":"$model","stream":true,"messages":[{"role":"user","content":"hi"}]}"""

        "happy path: the client receives the upstream frames byte-faithfully + gateway headers" {
            val fixtureBytes = ConformanceFixtures.load("done-terminator").body
            SseFixtureServer.start { bytes(fixtureBytes) }.use { upstream ->
                testApplication {
                    environment { config = MapApplicationConfig() }
                    application { module(ConfigFactory.load(), gateway(upstream.baseUrl)) }

                    val res =
                        client.post("/v1/chat/completions") {
                            header(HttpHeaders.Authorization, "Bearer $key")
                            contentType(ContentType.Application.Json)
                            setBody(streamBody("gpt-4o"))
                        }
                    res.status shouldBe HttpStatusCode.OK
                    res.headers["X-Gateway-Provider"] shouldBe "azure"
                    res.headers["X-Gateway-Model"] shouldBe "gpt-4o"
                    res.contentType()?.withoutParameters() shouldBe ContentType.Text.EventStream

                    // no usage chunk in this fixture ⇒ the body is byte-identical to the upstream stream
                    res.bodyAsText().encodeToByteArray().toList() shouldBe fixtureBytes.toList()
                }
            }
        }

        "usage chunk is rewritten over the wire; content + [DONE] frames pass verbatim" {
            val fixtureBytes = ConformanceFixtures.load("usage-final-chunk").body
            SseFixtureServer.start { bytes(fixtureBytes) }.use { upstream ->
                testApplication {
                    environment { config = MapApplicationConfig() }
                    application { module(ConfigFactory.load(), gateway(upstream.baseUrl)) }

                    val body =
                        client
                            .post("/v1/chat/completions") {
                                header(HttpHeaders.Authorization, "Bearer $key")
                                contentType(ContentType.Application.Json)
                                setBody(streamBody("gpt-4o"))
                            }.bodyAsText()

                    body shouldContain "\"content\":\"Hi\"" // upstream content frame untouched
                    body shouldContain "data: [DONE]" // terminator intact
                    body shouldContain "\"input_tokens\":11" // §1.3 extension injected into the usage frame
                    body shouldContain "\"output_tokens\":3"
                    body shouldContain "\"cost\":"
                    body shouldContain "\"cached_tokens\":7" // upstream detail preserved
                }
            }
        }

        "heartbeats appear on idle between upstream frames" {
            val frames = ConformanceFixtures.load("done-terminator").body
            // send the first ~half (a content frame), idle past the 1s heartbeat, then the remainder
            val cut = frames.size / 2
            SseFixtureServer
                .start {
                    bytes(frames.copyOfRange(0, cut))
                    bytes(frames.copyOfRange(cut, frames.size), delayMillis = 1_300)
                }.use { upstream ->
                    testApplication {
                        environment { config = MapApplicationConfig() }
                        application { module(ConfigFactory.load(), gateway(upstream.baseUrl, heartbeatSeconds = 1)) }

                        val body =
                            client
                                .post("/v1/chat/completions") {
                                    header(HttpHeaders.Authorization, "Bearer $key")
                                    contentType(ContentType.Application.Json)
                                    setBody(streamBody("gpt-4o"))
                                }.bodyAsText()

                        body shouldContain ": hb" // gateway-injected keep-alive during the idle gap
                        body shouldContain "data: [DONE]"
                    }
                }
        }

        "mid-stream drop (upstream closes without [DONE]) → error frame + [DONE], never a hang" {
            // script only the first content frame, then close the socket (no [DONE] = a drop, §1.4)
            val frames = ConformanceFixtures.load("done-terminator").body
            val firstFrameEnd = frames.decodeToString().indexOf("\n\n") + 2
            SseFixtureServer.start { bytes(frames.copyOfRange(0, firstFrameEnd)) }.use { upstream ->
                testApplication {
                    environment { config = MapApplicationConfig() }
                    application { module(ConfigFactory.load(), gateway(upstream.baseUrl)) }

                    val body =
                        client
                            .post("/v1/chat/completions") {
                                header(HttpHeaders.Authorization, "Bearer $key")
                                contentType(ContentType.Application.Json)
                                setBody(streamBody("gpt-4o"))
                            }.bodyAsText()

                    body shouldContain "\"content\":\"Hello\"" // the token that arrived
                    body shouldContain "\"error\":" // the synthesized drop frame
                    body shouldContain "data: [DONE]" // and a clean terminator
                }
            }
        }
    })
