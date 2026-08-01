// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
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
import com.typesafe.config.ConfigFactory
import org.tatrman.llmgateway.auth.sha256Hex
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey

/**
 * LG-P2·S1·T1 (e/f) + T7 — the P-1 wire round-trip: a full non-stream chat completion and an embeddings
 * call against a REAL upstream (WireMock, in-JVM), driven through the gateway's `/v1` API. Proves the
 * caller gets the upstream body UNTOUCHED except the §1.3 usage extension + gateway headers, and that an
 * upstream 429 maps through the OpenAI-wire converter.
 */
class PassthroughConformanceSpec :
    StringSpec({

        val key = "ttrk-conformance-key-00000000000000000000"
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())

        beforeSpec { wm.start() }
        afterSpec { wm.stop() }

        // point every openai-wire provider at WireMock + seed the auth key
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
                            base.governance.keys + SeededKey("golem", "conf", sha256Hex(key)),
                    ),
                providers = base.providers.copy(providers = providers),
            )
        }

        "non-stream chat: upstream body passes through + usage extension injected + gateway headers" {
            wm.resetAll()
            wm.stubFor(
                post(urlPathEqualTo("/openai/deployments/gpt-4o/chat/completions")).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"id":"cmpl-1","object":"chat.completion","model":"gpt-4o","choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
                    ),
                ),
            )

            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway()) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody("""{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}""")
                    }
                res.status shouldBe HttpStatusCode.OK
                res.headers["X-Gateway-Provider"] shouldBe "azure"
                res.headers["X-Gateway-Model"] shouldBe "gpt-4o"

                // punctuation-agnostic (the response is pretty-printed by the shared JSON config)
                val body = res.bodyAsText().replace(" ", "")
                body shouldContain "\"content\":\"Hi\"" // upstream content untouched
                body shouldContain "\"input_tokens\":10" // §1.3 dual usage names injected
                body shouldContain "\"cost\":" // cost injected
                body shouldContain "\"cached\":false"
            }

            // the request reached the upstream with the model rewritten to the deployment name
            wm.verify(postRequestedFor(urlPathEqualTo("/openai/deployments/gpt-4o/chat/completions")))
        }

        "upstream 429 maps through the OpenAI-wire converter → 429 rate_limit" {
            wm.resetAll()
            wm.stubFor(
                post(urlPathEqualTo("/openai/deployments/tatrman-gpt-4.1/chat/completions")).willReturn(
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
                        setBody("""{"model":"gpt-4.1","messages":[{"role":"user","content":"hi"}]}""")
                    }
                res.status shouldBe HttpStatusCode.TooManyRequests
                res.bodyAsText() shouldContain "rate_limit_exceeded"
            }
        }

        "embeddings ride passthrough; the 1.x {text|texts} shape is rejected 400 (LG-D3)" {
            wm.resetAll()
            wm.stubFor(
                post(urlPathEqualTo("/openai/deployments/text-embedding-ada-002/embeddings")).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],"model":"text-embedding-ada-002","usage":{"prompt_tokens":8,"total_tokens":8}}""",
                    ),
                ),
            )

            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway()) }

                // OpenAI array input → passthrough OK
                val ok =
                    client.post("/v1/embeddings") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody("""{"model":"ada-002","input":["hello","world"]}""")
                    }
                ok.status shouldBe HttpStatusCode.OK
                ok.bodyAsText() shouldContain "embedding"
                ok.bodyAsText() shouldContain "\"cost\""

                // 1.x non-standard {text|texts} object → 400 (LG-D3)
                val rejected =
                    client.post("/v1/embeddings") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody("""{"model":"ada-002","input":{"texts":["hello"]}}""")
                    }
                rejected.status shouldBe HttpStatusCode.BadRequest
                rejected.bodyAsText() shouldNotContain "embedding"
            }
        }
    })
