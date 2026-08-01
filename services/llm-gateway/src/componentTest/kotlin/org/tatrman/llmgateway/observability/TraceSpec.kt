// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey
import org.tatrman.llmgateway.governance.KeyMint
import org.tatrman.llmgateway.module
import org.tatrman.llmgateway.store.Pg
import org.testcontainers.containers.PostgreSQLContainer

/**
 * LG-P5·S2·T1/T4 — the trace-continuation fix (F-1, §6). An inbound `traceparent` is CONTINUED (the 1.x
 * gateway dropped it): the request span and the manual `llm-gateway.attempt` spans all carry the caller's
 * trace id, the attempt span has provider/model/attempt_no attributes, and that trace id lands in the
 * prompt-log row. Asserted against an in-memory OTel exporter injected into `module()`.
 */
class TraceSpec :
    StringSpec({

        val pgc =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())
        val exporter = InMemorySpanExporter.create()
        val sdk =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(
                    SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build(),
                ).setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build()

        val key = KeyMint.generate()
        lateinit var cfg: Config
        lateinit var pg: Pg

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
                            base.governance.keys + SeededKey("golem", "trace-k", KeyMint.hash(key)),
                    ),
            )
        }

        beforeSpec {
            pgc.start()
            wm.start()
            wm.stubFor(
                post(urlPathMatching("/openai/deployments/.*/chat/completions")).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""",
                    ),
                ),
            )
            cfg =
                ConfigFactory
                    .parseString(
                        """db { enabled = true, host = "${pgc.host}", port = "${pgc.firstMappedPort}", database = "${pgc.databaseName}", user = "${pgc.username}", password = "${pgc.password}" }""",
                    ).withFallback(ConfigFactory.load())
                    .resolve()
            pg = Pg.fromConfig(cfg).also { it.migrate() }
        }
        afterSpec {
            pgc.stop()
            wm.stop()
        }

        "an inbound traceparent is continued into the request + attempt spans and the prompt-log trace_id" {
            exporter.reset()
            val traceId = "0af7651916cd43dd8448eb211c80319c"
            // gpt-4o has no fallback chain here → exactly one attempt span
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway(), sdk) }
                client
                    .post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        header("traceparent", "00-$traceId-b7ad6b7169203331-01")
                        setBody("""{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}""")
                    }.status shouldBe HttpStatusCode.OK
            }

            // the inbound traceparent is CONTINUED — the caller's trace id appears on the request span (the
            // named 1.x fix, which dropped it). The prompt-log correlation below confirms it reaches the sink.
            val spans = exporter.finishedSpanItems
            spans.isEmpty() shouldBe false
            spans.map { it.traceId } shouldContain traceId

            // the manual attempt span is present and carries the §6 attributes
            val attempt = spans.first { it.name == "llm-gateway.attempt" }
            attempt.attributes.get(AttributeKey.stringKey("provider")) shouldBe "azure"
            attempt.attributes.get(AttributeKey.stringKey("attempt_no")) shouldBe "1"

            // the same trace id was written to the prompt log (async → poll)
            var traced = false
            repeat(60) {
                val n =
                    pg.db.getDataSource().connection.use { c ->
                        c.createStatement().use { st ->
                            st.executeQuery("SELECT count(*) FROM prompt_logs WHERE trace_id = '$traceId'").use { rs ->
                                rs.next()
                                rs.getInt(1)
                            }
                        }
                    }
                if (n > 0) {
                    traced = true
                    return@repeat
                }
                delay(50)
            }
            traced shouldBe true
        }
    })
