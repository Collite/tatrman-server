package org.tatrman.validate.stages

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.serverError
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.tatrman.validate.client.KtorLlmGatewayClient
import org.tatrman.validate.client.LlmGatewayClient

class LlmGuardSpec :
    StringSpec({

        val plan =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode.newBuilder().setTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName("customers"),
                    ),
                ).build()

        fun startWiremock(): WireMockServer =
            WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }

        fun gatewayClient(baseUrl: String): LlmGatewayClient =
            KtorLlmGatewayClient(baseUrl = baseUrl, timeoutMs = 5_000L)

        fun stubChat(
            wm: WireMockServer,
            verdictJson: String,
        ) {
            wm.stubFor(
                post(urlPathEqualTo("/api/v1/chat/responses")).willReturn(
                    okJson(
                        """
                        {"content": ${jsonEscape(verdictJson)}}
                        """.trimIndent(),
                    ),
                ),
            )
        }

        "evaluate returns null when disabled (no gateway call)" {
            val wm = startWiremock()
            try {
                val client = gatewayClient("http://localhost:${wm.port()}")
                val decision =
                    runBlocking {
                        LlmGuard(enabled = false, gateway = client).evaluate(plan, PipelineContext.getDefaultInstance())
                    }
                decision shouldBe null
                wm.allServeEvents.size shouldBe 0
                client.close()
            } finally {
                wm.stop()
            }
        }

        "enabled but no gateway wired → Approved with skeleton warning (dev fallback)" {
            val decision =
                runBlocking {
                    LlmGuard(enabled = true, gateway = null).evaluate(plan, PipelineContext.getDefaultInstance())
                }
            val approved = decision.shouldBeInstanceOf<LlmGuard.Decision.Approved>()
            approved.warning.code shouldBe "llm_guard_skeleton"
        }

        "benign plan + gateway approves → Decision.Approved with llm_guard_approved warning" {
            val wm = startWiremock()
            try {
                stubChat(wm, """{"verdict":"approve","reason":"ordinary analytics query"}""")
                val client = gatewayClient("http://localhost:${wm.port()}")
                val decision =
                    runBlocking {
                        LlmGuard(enabled = true, gateway = client).evaluate(plan, PipelineContext.getDefaultInstance())
                    }
                val approved = decision.shouldBeInstanceOf<LlmGuard.Decision.Approved>()
                approved.warning.code shouldBe "llm_guard_approved"
                approved.warning.humanMessage shouldContain "ordinary analytics"
                wm.verify(postRequestedFor(urlPathEqualTo("/api/v1/chat/responses")))
                client.close()
            } finally {
                wm.stop()
            }
        }

        "sketchy plan + gateway rejects → Decision.Rejected with the model's reason" {
            val wm = startWiremock()
            try {
                stubChat(wm, """{"verdict":"reject","reason":"raw PII dump without aggregation"}""")
                val client = gatewayClient("http://localhost:${wm.port()}")
                val decision =
                    runBlocking {
                        LlmGuard(enabled = true, gateway = client).evaluate(plan, PipelineContext.getDefaultInstance())
                    }
                val rejected = decision.shouldBeInstanceOf<LlmGuard.Decision.Rejected>()
                rejected.reason shouldContain "raw PII dump"
                client.close()
            } finally {
                wm.stop()
            }
        }

        "caveat verdict → Decision.Approved with llm_guard_caveat warning carrying the model's reason" {
            val wm = startWiremock()
            try {
                stubChat(wm, """{"verdict":"caveat","reason":"large fan-out join, consider an aggregate"}""")
                val client = gatewayClient("http://localhost:${wm.port()}")
                val decision =
                    runBlocking {
                        LlmGuard(enabled = true, gateway = client).evaluate(plan, PipelineContext.getDefaultInstance())
                    }
                val approved = decision.shouldBeInstanceOf<LlmGuard.Decision.Approved>()
                approved.warning.code shouldBe "llm_guard_caveat"
                approved.warning.humanMessage shouldContain "large fan-out"
                client.close()
            } finally {
                wm.stop()
            }
        }

        "gateway 5xx + FAIL_CLOSED (default) → Decision.Rejected naming the failure" {
            val wm = startWiremock()
            try {
                wm.stubFor(post(urlPathEqualTo("/api/v1/chat/responses")).willReturn(serverError()))
                val client = gatewayClient("http://localhost:${wm.port()}")
                val decision =
                    runBlocking {
                        LlmGuard(enabled = true, gateway = client)
                            .evaluate(plan, PipelineContext.getDefaultInstance())
                    }
                val rejected = decision.shouldBeInstanceOf<LlmGuard.Decision.Rejected>()
                rejected.reason shouldContain "unavailable"
                client.close()
            } finally {
                wm.stop()
            }
        }

        "gateway 5xx + FAIL_OPEN → Decision.Approved with llm_guard_unavailable warning" {
            val wm = startWiremock()
            try {
                wm.stubFor(post(urlPathEqualTo("/api/v1/chat/responses")).willReturn(serverError()))
                val client = gatewayClient("http://localhost:${wm.port()}")
                val decision =
                    runBlocking {
                        LlmGuard(
                            enabled = true,
                            gateway = client,
                            failurePosture = LlmGuard.FailurePosture.FAIL_OPEN,
                        ).evaluate(plan, PipelineContext.getDefaultInstance())
                    }
                val approved = decision.shouldBeInstanceOf<LlmGuard.Decision.Approved>()
                approved.warning.code shouldBe "llm_guard_unavailable"
                client.close()
            } finally {
                wm.stop()
            }
        }

        "malformed verdict JSON + FAIL_CLOSED (default) → Decision.Rejected (model returned garbage)" {
            val wm = startWiremock()
            try {
                // Inner content is non-JSON; the gateway envelope itself parses fine, so the
                // failure happens at parseVerdict — the FAIL_CLOSED branch returns a Rejected
                // with the "malformed verdict" reason rather than the "unavailable" reason.
                wm.stubFor(
                    post(urlPathEqualTo("/api/v1/chat/responses")).willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"content":"this is not JSON"}"""),
                    ),
                )
                val client = gatewayClient("http://localhost:${wm.port()}")
                val decision =
                    runBlocking {
                        LlmGuard(enabled = true, gateway = client).evaluate(plan, PipelineContext.getDefaultInstance())
                    }
                val rejected = decision.shouldBeInstanceOf<LlmGuard.Decision.Rejected>()
                rejected.reason shouldContain "malformed"
                client.close()
            } finally {
                wm.stop()
            }
        }

        "passes model + response_format=json_object to the gateway" {
            val wm = startWiremock()
            try {
                stubChat(wm, """{"verdict":"approve","reason":"ok"}""")
                val client = gatewayClient("http://localhost:${wm.port()}")
                runBlocking {
                    LlmGuard(
                        enabled = true,
                        gateway = client,
                        model = "claude-sonnet-4-6",
                    ).evaluate(plan, PipelineContext.getDefaultInstance())
                }
                wm.verify(
                    postRequestedFor(urlPathEqualTo("/api/v1/chat/responses"))
                        .withRequestBody(
                            matchingJsonPath(
                                "$.model",
                                com.github.tomakehurst.wiremock.client.WireMock
                                    .equalTo("claude-sonnet-4-6"),
                            ),
                        ).withRequestBody(
                            matchingJsonPath(
                                "$.response_format.type",
                                com.github.tomakehurst.wiremock.client.WireMock
                                    .equalTo("json_object"),
                            ),
                        ).withRequestBody(matchingJsonPath("$.messages[?(@.role == 'system')]"))
                        .withRequestBody(matchingJsonPath("$.messages[?(@.role == 'user')]")),
                )
                client.close()
            } finally {
                wm.stop()
            }
        }
    })

/** Embed a JSON string as a JSON string literal — i.e. produce `"foo\"bar"`. */
private fun jsonEscape(s: String): String =
    buildString {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
