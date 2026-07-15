// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.exactly
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.http.Fault
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.tatrman.llmgateway.auth.sha256Hex
import org.tatrman.llmgateway.config.CircuitConfig
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey

/**
 * LG-P3·S2·T2/T4/T7 — fallback chains + circuit-breaker-lite through the wire. Azure retry-exhausts, the
 * chain crosses to the Anthropic converter (served model + provider surfaced, not the alias); a non-chain-
 * eligible error does NOT fall back; and a tripped circuit skips a provider's entries on the next request
 * (reflected in `/health/providers`). One WireMock serves both the azure (`/openai/deployments/…`) and
 * anthropic (`/v1/messages`) upstreams on distinct paths.
 */
class FallbackChainSpec :
    StringSpec({

        val key = "ttrk-fallback-conf-key-0000000000000000"
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())

        beforeSpec { wm.start() }
        afterSpec { wm.stop() }

        // Base: point every provider at WireMock; seed the key. Optionally give gpt-4o a fallback + tune circuit.
        fun gateway(
            withFallback: Boolean = false,
            circuit: CircuitConfig? = null,
        ): GatewayConfig {
            val base = ConfigLoader.loadFromResources()
            val models =
                base.catalog.models.map {
                    if (withFallback &&
                        it.id == "azure-gpt-4o"
                    ) {
                        it.copy(fallback = listOf("anthropic-sonnet-4-6"))
                    } else {
                        it
                    }
                }
            val providers = base.providers.providers.mapValues { (_, p) -> p.copy(baseUrl = wm.baseUrl()) }
            return base.copy(
                catalog = base.catalog.copy(models = models),
                governance =
                    base.governance.copy(
                        keys = base.governance.keys + SeededKey("golem", "fb", sha256Hex(key)),
                    ),
                providers =
                    base.providers.copy(
                        providers = providers,
                        circuit = circuit ?: base.providers.circuit,
                    ),
            )
        }

        fun stubAzure(status: Int) =
            wm.stubFor(
                post(urlPathMatching("/openai/deployments/.*/chat/completions")).willReturn(
                    aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(
                        """{"error":{"message":"upstream","type":"error"}}""",
                    ),
                ),
            )

        fun stubAnthropicOk() =
            wm.stubFor(
                post(urlPathEqualTo("/v1/messages")).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"id":"msg_fb","type":"message","role":"assistant","content":[{"type":"text","text":"fallback served"}],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":3}}""",
                    ),
                ),
            )

        fun body(model: String) = """{"model":"$model","messages":[{"role":"user","content":"hi"}]}"""

        "retry-exhaust on azure (429) crosses the chain to the Anthropic converter; served model surfaced" {
            wm.resetAll()
            stubAzure(429) // retryable + chain-eligible
            stubAnthropicOk()
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway(withFallback = true)) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody(body("gpt-4o"))
                    }
                res.status shouldBe HttpStatusCode.OK
                // the crossing is observable: the SERVING provider/model are surfaced, not the requested gpt-4o
                res.headers["X-Gateway-Provider"] shouldBe "anthropic"
                res.headers["X-Gateway-Model"] shouldBe "claude-sonnet-4-6"
                res.bodyAsText() shouldContain "fallback served"
            }
            // azure was retried to exhaustion (maxAttempts=3), then the converter path served once
            wm.verify(postRequestedFor(urlPathMatching("/openai/deployments/.*/chat/completions")))
            wm.verify(exactly(1), postRequestedFor(urlPathEqualTo("/v1/messages")))
        }

        "a transport failure on azure (connection reset) is classified, retried, then crosses the chain (H-1)" {
            wm.resetAll()
            // No HTTP status ever reaches the gateway — the upstream connection is reset. Before the H-1 fix
            // this threw straight through the engine to a 500 with no retry and no fallback.
            wm.stubFor(
                post(urlPathMatching("/openai/deployments/.*/chat/completions"))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
            )
            stubAnthropicOk()
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway(withFallback = true)) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody(body("gpt-4o"))
                    }
                // Network → retryable + chain-eligible: azure exhausts, the converter path serves.
                res.status shouldBe HttpStatusCode.OK
                res.headers["X-Gateway-Provider"] shouldBe "anthropic"
                res.headers["X-Gateway-Model"] shouldBe "claude-sonnet-4-6"
                res.bodyAsText() shouldContain "fallback served"
            }
            wm.verify(exactly(1), postRequestedFor(urlPathEqualTo("/v1/messages")))
        }

        "a non-chain-eligible error (401 Auth) does NOT fall back — typed error out, no converter call" {
            wm.resetAll()
            stubAzure(401) // Auth: not retryable, not chain-eligible
            stubAnthropicOk()
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway(withFallback = true)) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody(body("gpt-4o"))
                    }
                res.status shouldBe HttpStatusCode.Unauthorized
            }
            // Auth is non-retryable → one azure try, and non-chain-eligible → the converter is never called.
            wm.verify(exactly(1), postRequestedFor(urlPathMatching("/openai/deployments/.*/chat/completions")))
            wm.verify(exactly(0), postRequestedFor(urlPathEqualTo("/v1/messages")))
        }

        "a tripped circuit skips the provider next request; /health/providers reports it open (T7)" {
            wm.resetAll()
            stubAzure(500) // Provider5xx: retryable but no fallback here → exhausts, records a failure
            // failureThreshold=1 → one exhausted request opens azure; long cooldown so it stays open
            val cfg = CircuitConfig(failureThreshold = 1, cooldownMs = 60_000)
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), gateway(circuit = cfg)) }

                // 1st request: azure retried to exhaustion → 502, and the circuit opens
                client
                    .post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody(body("gpt-4o"))
                    }.status shouldBe HttpStatusCode.BadGateway
                val callsAfterFirst =
                    wm
                        .countRequestsMatching(
                            postRequestedFor(urlPathMatching("/openai/.*")).build(),
                        ).count

                // health reflects the open circuit (normalize whitespace — the ops plane pretty-prints JSON)
                val health =
                    client
                        .get("/health/providers")
                        .bodyAsText()
                        .replace(" ", "")
                        .replace("\n", "")
                health shouldContain "\"azure\":{\"circuit\":\"open\""

                // 2nd request: azure is circuit-open with no fallback → 502 FAST, no new upstream call
                client
                    .post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        setBody(body("gpt-4o"))
                    }.status shouldBe HttpStatusCode.BadGateway
                val callsAfterSecond =
                    wm
                        .countRequestsMatching(
                            postRequestedFor(urlPathMatching("/openai/.*")).build(),
                        ).count
                callsAfterSecond shouldBe callsAfterFirst // the skip made zero upstream calls
            }
        }
    })
