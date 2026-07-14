// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.resolver.mcp.ResolveDoor
import org.tatrman.resolver.mcp.ResolveDoorHandler
import org.tatrman.resolver.v1.AwaitingClarification
import org.tatrman.resolver.v1.Option
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.Resolution

/**
 * RG-P6.S2.T2 — the `calls:` core-tier seeds. All fixtures under
 * resources/conformance/calls are well-formed per SCHEMA.md (the durable artifact
 * SV-P4 extends to ~25–40 @100% with the reference Golem), and the drivable seeds
 * run at the door seam now. `seed_only` fixtures (hero E2E, geo-dark) are shape-
 * validated but not driven — their live run needs nlp/fuzzy/grounding (SV-P4).
 */
class CallsSeedConformanceTest :
    StringSpec({

        val fixtures =
            listOf(
                "refusal-ambiguous-member.json",
                "refusal-below-threshold.json",
                "hero-e2e.json",
                "clarification-roundtrip.json",
                "geo-dark-degrade.json",
            )
        val validOutcomes = setOf("clarification", "resolution", "empty", "error")
        val json = Json { ignoreUnknownKeys = true }

        fun load(name: String): JsonObject =
            json
                .parseToJsonElement(
                    requireNotNull(javaClass.getResourceAsStream("/conformance/calls/$name")) { "missing $name" }
                        .bufferedReader()
                        .use { it.readText() },
                ).jsonObject

        "every calls: seed is well-formed per SCHEMA.md" {
            for (name in fixtures) {
                val fixture = load(name)
                fixture["id"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
                val turns = fixture["turns"]!!.jsonArray
                turns.isEmpty() shouldBe false
                for (turnEl in turns) {
                    val turn = turnEl.jsonObject
                    turn["tool"]!!.jsonPrimitive.content shouldBe "resolve.bind:v1"
                    turn["args"]!!
                        .jsonObject["conversation_id"]!!
                        .jsonPrimitive.content
                        .isNotBlank() shouldBe true
                    val expect = turn["expect"]!!.jsonObject
                    validOutcomes shouldContain expect["outcome"]!!.jsonPrimitive.content
                    // The refusal-over-guess invariant is asserted by every seed.
                    expect["no_binding_below_threshold"]!!.jsonPrimitive.content shouldBe "true"
                }
            }
        }

        // The scenario→core map for the drivable seeds (S2 swaps for a live pipeline).
        fun coreFor(scenario: String?): suspend (ResolveRequest) -> ResolveResponse =
            { _ ->
                when (scenario) {
                    "ambiguous_member" ->
                        ResolveResponse
                            .newBuilder()
                            .setAwaiting(
                                AwaitingClarification
                                    .newBuilder()
                                    .addOptions(Option.newBuilder().setId("M:df-adnak").setLabel("DF ADNAK"))
                                    .addOptions(Option.newBuilder().setId("M:df-belus").setLabel("DF BELUS"))
                                    .setResumeToken("tok-clar"),
                            ).build()
                    "resume_pin" ->
                        ResolveResponse
                            .newBuilder()
                            .setResolution(
                                Resolution.newBuilder().setConfidence(1.0).setRationale("resumed via signed pin"),
                            ).build()
                    else -> error("unknown scenario '$scenario'")
                }
            }

        "clarification round-trip: the signed token carries forward and resumes to a pin binding" {
            val fixture = load("clarification-roundtrip.json")
            val turns = fixture["turns"]!!.jsonArray
            var carriedToken: String? = null

            for (turnEl in turns) {
                val turn = turnEl.jsonObject
                val scenario = turn["scenario"]!!.jsonPrimitive.content
                // Substitute ${turn0.resumeToken} with the token the prior turn emitted.
                val args = substituteToken(turn["args"]!!.jsonObject, carriedToken)

                val handler = ResolveDoorHandler(ResolveDoor(coreFor(scenario)), requireIdentity = false)
                val result = runBlocking { handler.handle(args, null, null) }
                val structured = result.structuredContent.shouldNotBeNull()

                when (turn["expect"]!!.jsonObject["outcome"]!!.jsonPrimitive.content) {
                    "clarification" -> {
                        val awaiting = structured["awaiting"]!!.jsonObject
                        awaiting["options"]!!.jsonArray.size shouldBe 2
                        carriedToken = awaiting["resumeToken"]!!.jsonPrimitive.content
                    }
                    "resolution" -> {
                        structured["resolution"]!!
                            .jsonObject["rationale"]!!
                            .jsonPrimitive.content shouldBe "resumed via signed pin"
                    }
                }
            }
            carriedToken shouldBe "tok-clar" // the token was actually carried forward
        }
    })

/** Replace a `${turn0.resumeToken}` placeholder in `resume_token` with [token]. */
private fun substituteToken(
    args: JsonObject,
    token: String?,
): JsonObject =
    buildJsonObject {
        for ((key, value) in args) {
            val raw = (value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            if (key == "resume_token" && raw == "\${turn0.resumeToken}") {
                put(key, requireNotNull(token) { "no prior-turn token to substitute" })
            } else {
                put(key, value)
            }
        }
    }
