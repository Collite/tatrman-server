// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.cache

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * LG-P5·S1·T4 — the cache-hit replay shapes. Non-stream flips `cached` to true; the stream replay is a
 * content chunk + a usage chunk (`cached:true` + the saved cost echo) + `[DONE]`, whose deltas reassemble
 * to the original text (SDK-legal — the full openai-java round-trip is proven in the component tier).
 */
class CacheReplaySpec :
    StringSpec({

        fun env(content: String) =
            CacheEnvelope(
                body =
                    Json
                        .parseToJsonElement(
                            """{"id":"cmpl-1","object":"chat.completion","created":1720000000,"choices":[{"index":0,"message":{"role":"assistant","content":"$content"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":3,"cost":0.0012},"cached":false}""",
                        ).jsonObject,
                servedProvider = "anthropic",
                servedModel = "claude-haiku-4-5",
                promptTokens = 5,
                completionTokens = 3,
                cachedTokens = 0,
                costUsd = 0.0012,
                storedAtMs = 1_720_000_000_000,
            )

        "non-stream hit flips the top-level cached flag to true" {
            CacheReplay.nonStreamBody(env("hello"))["cached"] shouldBe JsonPrimitive(true)
        }

        "stream replay = content chunk + usage chunk (cached + cost) + [DONE], reassembling the original text" {
            val sse = CacheReplay.streamEvents(env("hello world"))
            sse shouldContain "chat.completion.chunk"
            sse shouldContain "hello world" // the content delta
            sse shouldContain "\"cached\":true"
            sse shouldContain "\"cost\":0.0012" // the SAVED cost echoed (GI-3)
            sse.trimEnd().endsWith("data: [DONE]") shouldBe true
            sse shouldContain "claude-haiku-4-5" // served model surfaced on the chunks
        }
    })
