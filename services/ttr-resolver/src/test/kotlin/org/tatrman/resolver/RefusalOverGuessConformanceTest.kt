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

/**
 * RG-P6.S1.T3 (hardened in review) — the door's signature assertion: refusal-over-guess.
 * Replays the `calls:` conformance seed fixtures through `resolve.bind:v1` and asserts
 * each turn's declared outcome plus the invariant that NO turn ever surfaces a guessed
 * binding — an ambiguous span clarifies, a below-threshold span binds nothing (RS-27).
 *
 * These now drive the REAL [org.tatrman.resolver.pipeline.ResolverPipeline] via
 * [ConformancePipeline] (SpanProposal → GateSpans → real HMAC codec, hermetic fakes for
 * nlp/fuzzy). A resolver that guessed a below-threshold bind, or bound an ambiguous span,
 * would turn these RED — the assertion runs against the actual gate, not a canned response
 * (RG-P6 review G).
 */
class RefusalOverGuessConformanceTest :
    StringSpec({

        val fixtures = listOf("refusal-ambiguous-member.json", "refusal-below-threshold.json")
        val json = Json { ignoreUnknownKeys = true }

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

                    // The REAL pipeline for this scenario — the gate actually runs.
                    val handler = ConformancePipeline.doorHandler(scenario)
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
