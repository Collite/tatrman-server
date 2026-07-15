// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.wire

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.exhaustive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The B-T2 round-trip regression suite — **graduated from the LG-P0·S1 spike** to guard the production
 * [ChatRequest] (LG-D1). The 9-fixture OpenAI-request corpus must forward byte-faithfully: typed +
 * unmodeled + nested unknowns, every number literal, explicit-`null`-vs-absent, unicode. Plus the two
 * production behaviours the spike didn't cover: `model_tags` stripping and copy-with-patch model rewrite.
 */
class UnknownFieldRoundTripSpec :
    StringSpec({

        val fixtures =
            listOf(
                "01-minimal",
                "02-typed-scalars",
                "03-tool-calling",
                "04-response-format-json-schema",
                "05-future-params",
                "06-number-formats",
                "07-nulls-vs-absent",
                "08-mixed-content-parts",
                "09-unicode",
            )

        fun load(name: String): String =
            UnknownFieldRoundTripSpec::class.java
                .getResource("/wire/roundtrip/$name.json")
                ?.readText()
                ?: error("missing fixture /wire/roundtrip/$name.json")

        fun canonical(name: String): JsonObject = Json.parseToJsonElement(load(name)).jsonObject

        "ChatRequest forwards every corpus request byte-faithfully (no field lost or corrupted)" {
            checkAll(fixtures.exhaustive()) { name ->
                withClue(name) {
                    ChatRequest.parse(load(name)).toUpstreamJson() shouldBe canonical(name)
                }
            }
        }

        "number literals survive verbatim (1≠1.0, trailing zeros, sci, >Long.MAX)" {
            val out = ChatRequest.parse(load("06-number-formats")).toUpstreamJson()
            out["temperature"]!!.jsonPrimitive.content shouldBe "1"
            out["top_p"]!!.jsonPrimitive.content shouldBe "0.10"
            out["seed"]!!.jsonPrimitive.content shouldBe "9007199254740993"
            out["sampling"]!!.jsonObject["big"]!!.jsonPrimitive.content shouldBe "12345678901234567890"
        }

        "model rewrite is a surgical copy-with-patch — only `model` changes" {
            val original = ChatRequest.parse(load("03-tool-calling"))
            val before = original.toUpstreamJson()
            val after = original.withModel("claude-3-5-sonnet-20241022").toUpstreamJson()

            after["model"]!!.jsonPrimitive.content shouldBe "claude-3-5-sonnet-20241022"
            after.keys shouldBe before.keys
            (before.keys - "model").forEach { k -> withClue(k) { after[k] shouldBe before[k] } }
        }

        "model_tags is a gateway-only routing hint — read on the model, stripped before upstream" {
            val req =
                ChatRequest.parse(
                    """{"model":"smart","model_tags":["smart","coding"],"messages":[{"role":"user","content":"hi"}]}""",
                )
            req.modelTags shouldBe listOf("smart", "coding")
            req.toUpstreamJson().containsKey("model_tags") shouldBe false // never forwarded
            req.toUpstreamJson().containsKey("model") shouldBe true // everything else stays
        }
    })
