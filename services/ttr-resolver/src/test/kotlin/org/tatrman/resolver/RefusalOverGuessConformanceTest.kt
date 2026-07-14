// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.resolver.mcp.ResolveDoor
import org.tatrman.resolver.mcp.ResolveDoorHandler
import org.tatrman.resolver.v1.AwaitingClarification
import org.tatrman.resolver.v1.Option
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.Resolution

/**
 * RG-P6.S1.T3 — the door's signature assertion: refusal-over-guess. Replays the
 * `calls:` conformance seed fixtures (under resources/conformance/calls, the schema
 * S2.T2 extends) through `resolve.bind:v1` and asserts each turn's declared outcome
 * plus the invariant that NO turn ever surfaces a guessed binding — an ambiguous
 * span clarifies, a below-threshold span binds nothing (RS-27).
 *
 * S1 seed: the `scenario` tag maps to the real-shaped core response here; S2 swaps
 * this stub for a live pipeline run driven by fixture-carried nlp/fuzzy data. The
 * fixtures + the invariant are the durable artifact.
 */
class RefusalOverGuessConformanceTest :
    StringSpec({

        val fixtures = listOf("refusal-ambiguous-member.json", "refusal-below-threshold.json")
        val json = Json { ignoreUnknownKeys = true }

        // The S1 scenario→core map (S2 replaces with a live pipeline run).
        fun coreFor(scenario: String): suspend (ResolveRequest) -> ResolveResponse =
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
                                    .setResumeToken("tok-amb"),
                            ).build()
                    "below_threshold" ->
                        ResolveResponse
                            .newBuilder()
                            .setResolution(
                                Resolution.newBuilder().setConfidence(0.0).setRationale("deterministic bind: 0 domain"),
                            ).build()
                    else -> error("unknown scenario '$scenario'")
                }
            }

        // The resolution's bindings array, or empty when the core bound nothing
        // (JsonFormat omits an empty repeated field) — the refuse-over-guess probe.
        fun bindingsOf(structured: JsonObject): JsonArray =
            structured["resolution"]?.jsonObject?.get("bindings")?.jsonArray ?: JsonArray(emptyList())

        fun loadFixture(name: String): JsonObject {
            val text =
                requireNotNull(javaClass.getResourceAsStream("/conformance/calls/$name")) {
                    "missing conformance fixture $name"
                }.bufferedReader().use { it.readText() }
            return json.parseToJsonElement(text).jsonObject
        }

        for (name in fixtures) {
            "refuse-over-guess: $name" {
                val fixture = loadFixture(name)
                val turns = fixture["turns"]!!.jsonArray
                turns.isEmpty() shouldBe false

                for (turnEl in turns) {
                    val turn = turnEl.jsonObject
                    turn["tool"]!!.jsonPrimitive.content shouldBe "resolve.bind:v1"
                    val args = turn["args"]!!.jsonObject
                    val scenario = turn["scenario"]!!.jsonPrimitive.content
                    val expect = turn["expect"]!!.jsonObject

                    val handler = ResolveDoorHandler(ResolveDoor(coreFor(scenario)), requireIdentity = false)
                    val result = runBlocking { handler.handle(args, null, null) }
                    val structured = result.structuredContent.shouldNotBeNull()

                    // The invariant every fixture asserts: never a guessed binding.
                    bindingsOf(structured).shouldBeEmpty()

                    when (expect["outcome"]!!.jsonPrimitive.content) {
                        "clarification" -> {
                            result.isError shouldBe false
                            structured.containsKey("resolution") shouldBe false
                            val options = structured["awaiting"]!!.jsonObject["options"]!!.jsonArray
                            val minOptions = expect["min_options"]?.jsonPrimitive?.int ?: 1
                            options.size shouldBeGreaterThanOrEqual minOptions
                        }
                        "empty" -> {
                            result.isError shouldBe false
                            structured.containsKey("awaiting") shouldBe false
                            structured.containsKey("resolution") shouldBe true
                        }
                        else -> error("unexpected outcome in $name")
                    }
                }
            }
        }
    })
