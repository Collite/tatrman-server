// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider.anthropic

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.llmgateway.wire.AnthropicErrorConverter
import org.tatrman.llmgateway.wire.GatewayError

/**
 * LG-P2·S3·T2 — Anthropic Messages response → OpenAI `chat.completion` (non-stream) + the error taxonomy.
 * The §1.3 dual-usage names + cost are added downstream by `ResponseEnrichment`; here we pin the base
 * shape (content, tool_calls, stop_reason→finish_reason, usage in OpenAI names).
 */
class AnthropicResponseConversionSpec :
    StringSpec({

        fun convert(
            body: String,
            model: String = "claude-x",
        ) = AnthropicConverter.toChatCompletion(Json.parseToJsonElement(body).jsonObject, model, 1_700_000_000L)

        "text response → chat.completion: content, finish_reason=stop, usage in OpenAI names" {
            val c =
                convert(
                    """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"Hello"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""",
                )
            c["object"]!!.jsonPrimitive.content shouldBe "chat.completion"
            c["id"]!!.jsonPrimitive.content shouldBe "msg_1"
            c["model"]!!.jsonPrimitive.content shouldBe "claude-x"
            val choice = c["choices"]!!.jsonArray[0].jsonObject
            choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content shouldBe "Hello"
            choice["finish_reason"]!!.jsonPrimitive.content shouldBe "stop"
            val usage = c["usage"]!!.jsonObject
            usage["prompt_tokens"]!!.jsonPrimitive.int shouldBe 10
            usage["completion_tokens"]!!.jsonPrimitive.int shouldBe 5
            usage["total_tokens"]!!.jsonPrimitive.int shouldBe 15
        }

        "tool_use response → message.tool_calls (input serialized to an arguments string); finish=tool_calls" {
            val c =
                convert(
                    """{"id":"m","type":"message","role":"assistant","content":[{"type":"tool_use","id":"tu1","name":"get_weather","input":{"city":"BA"}}],"stop_reason":"tool_use","usage":{"input_tokens":3,"output_tokens":8}}""",
                )
            val message = c["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
            message["content"] shouldBe JsonNull // no text blocks → null content
            val call = message["tool_calls"]!!.jsonArray[0].jsonObject
            call["id"]!!.jsonPrimitive.content shouldBe "tu1"
            call["type"]!!.jsonPrimitive.content shouldBe "function"
            val fn = call["function"]!!.jsonObject
            fn["name"]!!.jsonPrimitive.content shouldBe "get_weather"
            Json
                .parseToJsonElement(
                    fn["arguments"]!!.jsonPrimitive.content,
                ).jsonObject["city"]!!
                .jsonPrimitive.content shouldBe
                "BA"
            c["choices"]!!
                .jsonArray[0]
                .jsonObject["finish_reason"]!!
                .jsonPrimitive.content shouldBe "tool_calls"
        }

        "stop_reason mapping: end_turn→stop, max_tokens→length, tool_use→tool_calls, refusal→content_filter" {
            AnthropicConverter.mapStopReason("end_turn", false) shouldBe "stop"
            AnthropicConverter.mapStopReason("max_tokens", false) shouldBe "length"
            AnthropicConverter.mapStopReason("tool_use", true) shouldBe "tool_calls"
            AnthropicConverter.mapStopReason("refusal", false) shouldBe "content_filter"
            AnthropicConverter.mapStopReason("stop_sequence", false) shouldBe "stop"
        }

        "error taxonomy: 429→RateLimit, 529/overloaded→Provider5xx(529) retryable (C-3), 401→Auth, 400→Validation" {
            fun conv(
                status: Int,
                type: String,
                message: String = "m",
            ) = AnthropicErrorConverter.convert(
                status,
                Json
                    .parseToJsonElement(
                        """{"type":"error","error":{"type":"$type","message":"$message"}}""",
                    ).jsonObject,
            )

            conv(429, "rate_limit_error").shouldBeInstanceOf<GatewayError.RateLimit>()

            val overloaded = conv(529, "overloaded_error")
            overloaded.shouldBeInstanceOf<GatewayError.Provider5xx>()
            (overloaded as GatewayError.Provider5xx).status shouldBe 529
            overloaded.retryable shouldBe true
            overloaded.chainEligible shouldBe true

            // overloaded_error type maps to Provider5xx(529) even if the status line is a generic 500
            conv(500, "overloaded_error").shouldBeInstanceOf<GatewayError.Provider5xx>()

            conv(401, "authentication_error").shouldBeInstanceOf<GatewayError.Auth>()
            conv(400, "invalid_request_error").shouldBeInstanceOf<GatewayError.Validation>()
            conv(400, "invalid_request_error", "prompt is too long").shouldBeInstanceOf<GatewayError.ContextLength>()
        }
    })
