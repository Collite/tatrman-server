// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.mcp

import org.tatrman.grounding.v1.ClarificationOption
import org.tatrman.grounding.v1.DateTimeInterval
import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.Normalized
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.tatrman.grounding.mcp.client.GroundingClient

/**
 * A11.1 — the grounding-mcp tool matrix over mocked backing clients: request mapping (assert on the
 * captured GroundRequest proto — data flow, not call counts), OK/clarification/ungroundable →
 * isError=false structured outcomes, transport failure → isError=true, arg validation, and routing.
 */
class ToolsTest :
    StringSpec({
        fun client(
            name: String,
            slot: CapturingSlot<GroundRequest>? = null,
            response: GroundResponse = okResponse(),
        ): GroundingClient {
            val c = mockk<GroundingClient>()
            every { c.serviceName } returns name
            every { c.close() } just Runs
            if (slot != null) {
                coEvery { c.ground(capture(slot)) } returns response
            } else {
                coEvery { c.ground(any()) } returns response
            }
            return c
        }

        fun request(args: JsonObject?): CallToolRequest {
            val r = mockk<CallToolRequest>()
            every { r.arguments } returns args
            return r
        }

        fun textOf(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
            (result.content.first() as TextContent).text ?: ""

        "ground_time maps camelCase args onto GroundRequest (spanText, kind, context)" {
            val slot = slot<GroundRequest>()
            val tools = Tools(chrono = client("chrono", slot), geo = client("geo"), money = client("money"))

            tools.groundTimeCallback(
                request(
                    buildJsonObject {
                        put("spanText", "May period")
                        put("package", "cnc")
                        putJsonObject("context") {
                            put("referenceDatetime", "2026-05-15T12:00:00+02:00")
                            put("timezone", "Europe/Prague")
                        }
                    },
                ),
            )

            val captured = slot.captured
            captured.spanText shouldBe "May period"
            captured.kind shouldBe EntityKind.DATE_TIME
            captured.getPackage() shouldBe "cnc"
            captured.context.referenceDatetime shouldBe "2026-05-15T12:00:00+02:00"
            captured.context.timezone shouldBe "Europe/Prague"
        }

        "OK response → isError=false, structuredContent mirror, text = explanation + sql_preview" {
            val tools = Tools(client("chrono"), client("geo"), client("money"))
            val result =
                tools.groundTimeCallback(
                    request(
                        buildJsonObject {
                            put("spanText", "May period")
                            putJsonObject("context") { put("referenceDatetime", "2026-05-15T12:00:00+02:00") }
                        },
                    ),
                )

            result.isError shouldBe false
            val structured = result.structuredContent as JsonObject
            structured["status"]!!.jsonPrimitive.content shouldBe "OK"
            structured["result"]!!.jsonObject["sqlPreview"]!!.jsonPrimitive.content shouldContain "\"date\""
            val text = textOf(result)
            text shouldContain "Resolved to May 2026"
            text shouldContain "SQL preview:"
        }

        "AWAITING_CLARIFICATION → isError=false with status + options in structuredContent" {
            val tools = Tools(client("chrono", response = awaitingResponse()), client("geo"), client("money"))
            val result =
                tools.groundTimeCallback(
                    request(
                        buildJsonObject {
                            put("spanText", "December")
                            putJsonObject("context") { put("referenceDatetime", "2026-05-15T12:00:00+02:00") }
                        },
                    ),
                )
            result.isError shouldBe false
            (result.structuredContent as JsonObject)["status"]!!.jsonPrimitive.content shouldBe "AWAITING_CLARIFICATION"
            textOf(result) shouldContain "202612"
        }

        "UNGROUNDABLE → isError=false with the reason surfaced" {
            val tools = Tools(client("chrono"), client("geo", response = ungroundableResponse()), client("money"))
            val result =
                tools.groundGeoCallback(request(buildJsonObject { put("spanText", "within 20 km of Atlantis") }))
            result.isError shouldBe false
            (result.structuredContent as JsonObject)["status"]!!.jsonPrimitive.content shouldBe "UNGROUNDABLE"
            textOf(result) shouldContain "could not resolve"
        }

        "transport failure → isError=true" {
            val failing = mockk<GroundingClient>()
            every { failing.serviceName } returns "geo"
            coEvery { failing.ground(any()) } throws RuntimeException("connection refused")
            val tools = Tools(client("chrono"), failing, client("money"))

            val result = tools.groundGeoCallback(request(buildJsonObject { put("spanText", "in Brno") }))
            result.isError shouldBe true
            textOf(result) shouldContain "connection refused"
        }

        "missing spanText → tool-arg error (isError=true), service not called" {
            val geo = client("geo")
            val tools = Tools(client("chrono"), geo, client("money"))
            val result = tools.groundGeoCallback(request(buildJsonObject { put("package", "cnc") }))
            result.isError shouldBe true
            textOf(result) shouldContain "spanText"
            coVerify(exactly = 0) { geo.ground(any()) }
        }

        "ground_time without context.referenceDatetime → tool-arg error (load-bearing)" {
            val chrono = client("chrono")
            val tools = Tools(chrono, client("geo"), client("money"))
            val result = tools.groundTimeCallback(request(buildJsonObject { put("spanText", "May period") }))
            result.isError shouldBe true
            textOf(result) shouldContain "referenceDatetime"
            coVerify(exactly = 0) { chrono.ground(any()) }
        }

        "routing: each tool calls only its own service" {
            val chrono = client("chrono")
            val geo = client("geo")
            val money = client("money")
            val tools = Tools(chrono, geo, money)

            tools.groundMoneyCallback(request(buildJsonObject { put("spanText", "over 100k CZK") }))
            coVerify(exactly = 1) { money.ground(any()) }
            coVerify(exactly = 0) { chrono.ground(any()) }
            coVerify(exactly = 0) { geo.ground(any()) }
        }

        "kind mismatch is passed through, not rerouted (A11.4): a geo span to ground_time hits chrono w/ DATE_TIME" {
            val slot = slot<GroundRequest>()
            val tools = Tools(chrono = client("chrono", slot), geo = client("geo"), money = client("money"))

            tools.groundTimeCallback(
                request(
                    buildJsonObject {
                        put("spanText", "within 20 km of Brno")
                        putJsonObject("context") { put("referenceDatetime", "2026-05-15T12:00:00+02:00") }
                    },
                ),
            )
            slot.captured.kind shouldBe EntityKind.DATE_TIME
            slot.captured.spanText shouldBe "within 20 km of Brno"
        }
    })

private fun okResponse(): GroundResponse =
    GroundResponse
        .newBuilder()
        .setStatus(GroundResponse.Status.OK)
        .setResult(
            GroundingResult
                .newBuilder()
                .setNormalized(
                    Normalized
                        .newBuilder()
                        .setInterval(
                            DateTimeInterval
                                .newBuilder()
                                .setStart("2026-05-01T00:00:00+02:00")
                                .setEnd("2026-06-01T00:00:00+02:00"),
                        ),
                ).setSqlPreview("t.\"date\" >= {start} AND t.\"date\" < {end}")
                .setConfidence(0.95f)
                .setSource(GroundingResult.Source.RULES)
                .setExplanation("Resolved to May 2026."),
        ).build()

private fun awaitingResponse(): GroundResponse =
    GroundResponse
        .newBuilder()
        .setStatus(GroundResponse.Status.AWAITING_CLARIFICATION)
        .addOptions(ClarificationOption.newBuilder().setId("202612").setLabel("December 2026 (202612)"))
        .build()

private fun ungroundableResponse(): GroundResponse =
    GroundResponse
        .newBuilder()
        .setStatus(GroundResponse.Status.UNGROUNDABLE)
        .addMessages(
            ResponseMessage
                .newBuilder()
                .setSeverity(Severity.INFO)
                .setCode("ungroundable")
                .setHumanMessage("could not resolve the place 'Atlantis'"),
        ).build()
