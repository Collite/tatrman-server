// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
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
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.delay
import org.tatrman.llmgateway.auth.sha256Hex
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey
import org.tatrman.llmgateway.governance.BudgetUsageRepo
import org.tatrman.llmgateway.store.Pg
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * LG-P5·S2·T6 — THE HERO (design §7), the phase-exit gate. One streaming run exercises the whole stack:
 * a tier **alias** (`gpt-4`) resolves to an azure model; azure **429s before the first token**; the engine
 * replays the SAME request through the fallback chain to the **Anthropic converter**, which streams cleanly
 * — the client sees ONE seamless stream, the SERVING provider/model surfaced, no azure error leaked. Then
 * the observable truth (F-1): the **budget** moved (tap-settled), the **prompt log** carries the crossing
 * (`fallback_from`, cost-center, turn-ref, trace_id), an `llm-gateway.attempt` **span** exists, and the
 * caller's **traceparent is continued**. Real PG + Redis + WireMock + an in-memory OTel exporter.
 */
class HeroScenarioSpec :
    StringSpec({

        val key = "ttrk-hero-conf-key-000000000000000000000"
        val pgc =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")
        val redisC = GenericContainer("redis:7-alpine").apply { withExposedPorts(6379) }
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())
        val exporter = InMemorySpanExporter.create()
        val sdk =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(
                    SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build(),
                ).setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build()

        lateinit var cfg: Config
        lateinit var pg: Pg
        lateinit var budgets: BudgetUsageRepo
        val month: LocalDate = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)

        fun gateway(): GatewayConfig {
            val base = ConfigLoader.loadFromResources()
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
            pg = Pg.fromConfig(cfg).also { it.migrate() }
            budgets = BudgetUsageRepo(pg.db)
        }
        afterSpec {
            pgc.stop()
            redisC.stop()
            wm.stop()
        }

        "hero: alias→azure 429→chain replays to the converter→one clean stream, budget/prompt-log/trace reflect it" {
            wm.resetAll()
            exporter.reset()
            wm.stubFor(
                post(urlPathMatching("/openai/deployments/.*/chat/completions")).willReturn(
                    aResponse().withStatus(429).withHeader("Content-Type", "application/json").withBody(
                        """{"error":{"message":"slow down","type":"rate_limit_error","code":"rate_limit_exceeded"}}""",
                    ),
                ),
            )
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

            val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway(), sdk) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        header("X-Cost-Center", "golem/hero-instance")
                        header("X-Turn-Ref", "turn-hero-1")
                        header("traceparent", "00-$traceId-00f067aa0ba902b7-01")
                        setBody("""{"model":"gpt-4","stream":true,"messages":[{"role":"user","content":"hi"}]}""")
                    }
                res.status shouldBe HttpStatusCode.OK
                res.headers["X-Gateway-Provider"] shouldBe "anthropic" // fallback is honest, never silent (C-4)
                res.headers["X-Gateway-Model"] shouldBe "claude-sonnet-4-6"

                val body = res.bodyAsText()
                body shouldContain "\"content\":\"hero answer\"" // the converter stream reached the client
                body shouldContain "data: [DONE]"
                body shouldContain "\"cost\"" // usage.cost echoed in the final chunk (§1.3)
                body shouldNotContain "rate_limit" // no azure error leaked (attach after fallback)
                body shouldNotContain "slow down"

                // ── observable truth (assert while the app is alive: its PG pool + async log writer are up) ──

                // the caller's traceparent was continued (the named 1.x fix), and a manual attempt span exists
                exporter.finishedSpanItems.map { it.traceId } shouldContain traceId
                exporter.finishedSpanItems.any { it.name == "llm-gateway.attempt" } shouldBe true

                // the tap-settled budget moved (real spend on the serving provider)
                (budgets.usedUsd("golem", month) > 0.0) shouldBe true

                // the prompt log carries the crossing — poll (async write-behind)
                var found = false
                repeat(80) {
                    val row =
                        pg.db.getDataSource().connection.use { c ->
                            c.createStatement().use { st ->
                                st
                                    .executeQuery(
                                        "SELECT fallback_from, served_provider, served_model, requested_model," +
                                            " cost_center, cached, trace_id FROM prompt_logs WHERE turn_ref='turn-hero-1'",
                                    ).use { rs ->
                                        if (rs.next()) {
                                            listOf(
                                                rs.getString("fallback_from"),
                                                rs.getString("served_provider"),
                                                rs.getString("served_model"),
                                                rs.getString("requested_model"),
                                                rs.getString("cost_center"),
                                                rs.getBoolean("cached").toString(),
                                                rs.getString("trace_id"),
                                            )
                                        } else {
                                            null
                                        }
                                    }
                            }
                        }
                    if (row != null) {
                        row[0] shouldBe "gpt-4o" // fallback_from = the requested primary
                        row[1] shouldBe "anthropic" // served_provider
                        row[2] shouldBe "claude-sonnet-4-6" // served_model
                        row[3] shouldBe "gpt-4" // requested_model = the alias the caller sent
                        row[4] shouldBe "golem/hero-instance" // cost_center (prefix-validated)
                        row[5] shouldBe "false" // cached
                        row[6] shouldBe traceId // trace correlation reaches the sink
                        found = true
                        return@repeat
                    }
                    delay(50)
                }
                found shouldBe true
            }
        }
    })
