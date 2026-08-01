// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider.anthropic

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.llmgateway.stream.SseByteParser
import org.tatrman.llmgateway.stream.SseFrame
import org.tatrman.llmgateway.stream.StreamObservation
import org.tatrman.llmgateway.stream.TapParser

/**
 * LG-P2·S3·T2/T4 — Anthropic SSE event family → OpenAI `chat.completion.chunk` conversion, plus the
 * **tap-parity** invariant (B-3): running the passthrough tap over the CONVERTED frames yields the same
 * observation sequence as over an equivalent passthrough OpenAI stream — "all consumers depend only on the
 * tap" made executable.
 */
class AnthropicStreamConversionSpec :
    StringSpec({

        fun anthropicSse(vararg events: Pair<String, String>): String =
            events.joinToString("") { (ev, data) -> "event: $ev\ndata: $data\n\n" }

        fun frame(sse: String): List<SseFrame> {
            val p = SseByteParser()
            return p.feed(sse.encodeToByteArray()) + p.close()
        }

        fun convert(sse: String): List<SseFrame> {
            val conv = AnthropicStreamConverter("claude-x", 1_700_000_000L)
            return frame(sse).flatMap { conv.onFrame(it) }
        }

        fun tapTags(frames: List<SseFrame>): List<String> {
            val tap = TapParser("anthropic", "claude-x", nowMs = { 1_000 })
            return buildList {
                add("Opened")
                frames.forEach { f ->
                    tap.onFrame(f).forEach {
                        add(
                            when (it) {
                                is StreamObservation.FirstToken -> "FirstToken"
                                is StreamObservation.ContentDelta -> "ContentDelta"
                                is StreamObservation.UsageChunk ->
                                    it.usage.run { "UsageChunk($promptTokens,$completionTokens)" }
                                is StreamObservation.Finish -> "Finish(${it.finishReason})"
                                is StreamObservation.ErrorFrame -> "ErrorFrame"
                                StreamObservation.Done -> "Done"
                                is StreamObservation.Opened -> "Opened"
                            },
                        )
                    }
                }
            }
        }

        fun deltaField(
            f: SseFrame,
            field: String,
        ): String? =
            runCatching {
                (Json.parseToJsonElement(f.data!!) as JsonObject)["choices"]!!
                    .jsonArray[0]
                    .jsonObject["delta"]!!
                    .jsonObject[field]
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()

        val textStream =
            anthropicSse(
                "message_start" to
                    """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude","usage":{"input_tokens":10,"output_tokens":0}}}""",
                "content_block_start" to
                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                "content_block_delta" to
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
                "content_block_delta" to
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "message_delta" to
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}""",
                "message_stop" to """{"type":"message_stop"}""",
            )

        "text stream → OpenAI chunks: role open, content deltas, finish, usage, [DONE]" {
            val out = convert(textStream)
            // reassembled text content
            out.mapNotNull { deltaField(it, "content") }.joinToString("") shouldBe "Hello world"
            // opens with a role delta, terminates with [DONE]
            deltaField(out.first(), "role") shouldBe "assistant"
            out.last().isDone shouldBe true
            // a usage chunk carries the Anthropic token counts in OpenAI names
            val usageChunk = out.first { (Json.parseToJsonElement(it.data!!) as JsonObject)["usage"] != null }
            val usage = (Json.parseToJsonElement(usageChunk.data!!) as JsonObject)["usage"]!!.jsonObject
            usage["prompt_tokens"]!!.jsonPrimitive.content shouldBe "10"
            usage["completion_tokens"]!!.jsonPrimitive.content shouldBe "5"
        }

        "tap parity: the tap over converted frames == the tap over an equivalent passthrough OpenAI stream" {
            val passthrough =
                listOf(
                    """{"choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}""",
                    """{"choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                    """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}""",
                    "[DONE]",
                ).joinToString("") { "data: $it\n\n" }

            tapTags(convert(textStream)) shouldContainExactly tapTags(frame(passthrough))
            tapTags(convert(textStream)) shouldContainExactly
                listOf(
                    "Opened",
                    "FirstToken",
                    "ContentDelta",
                    "ContentDelta",
                    "Finish(stop)",
                    "UsageChunk(10,5)",
                    "Done",
                )
        }

        "tool_use stream: input_json_delta fragments reassemble with index integrity; finish=tool_calls" {
            val toolStream =
                anthropicSse(
                    "message_start" to
                        """{"type":"message_start","message":{"id":"m","role":"assistant","usage":{"input_tokens":3,"output_tokens":0}}}""",
                    "content_block_start" to
                        """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu1","name":"get_weather","input":{}}}""",
                    "content_block_delta" to
                        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""",
                    "content_block_delta" to
                        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"BA\"}"}}""",
                    "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                    "message_delta" to
                        """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":8}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                )
            val out = convert(toolStream)

            // every tool_calls delta is index 0; the fragments reassemble to the exact JSON (FI-4)
            val toolDeltas =
                out.mapNotNull { f ->
                    runCatching {
                        (Json.parseToJsonElement(f.data!!) as JsonObject)["choices"]!!
                            .jsonArray[0]
                            .jsonObject["delta"]!!
                            .jsonObject["tool_calls"]!!
                            .jsonArray[0]
                            .jsonObject
                    }.getOrNull()
                }
            toolDeltas.forEach { it["index"]!!.jsonPrimitive.content shouldBe "0" }
            toolDeltas
                .first()["function"]!!
                .jsonObject["name"]!!
                .jsonPrimitive.content shouldBe "get_weather"
            val args =
                toolDeltas
                    .mapNotNull {
                        it["function"]
                            ?.jsonObject
                            ?.get(
                                "arguments",
                            )?.jsonPrimitive
                            ?.contentOrNull
                    }.joinToString("")
            args shouldBe """{"city":"BA"}"""

            out.any { deltaFinish(it) == "tool_calls" } shouldBe true
            out.last().isDone shouldBe true
        }
    })

private fun deltaFinish(f: SseFrame): String? =
    runCatching {
        (Json.parseToJsonElement(f.data!!) as JsonObject)["choices"]!!
            .jsonArray[0]
            .jsonObject["finish_reason"]
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()
