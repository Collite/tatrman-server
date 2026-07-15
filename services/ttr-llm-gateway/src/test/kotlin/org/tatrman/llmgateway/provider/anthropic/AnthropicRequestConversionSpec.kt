// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider.anthropic

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.llmgateway.wire.ChatRequest

/**
 * LG-P2·S3·T1 — the OpenAI → Anthropic Messages request conversion table (design B-1). Each case pins one
 * mapping rule; the last proves C-4 strip-and-log (the dropped-params LIST, not just their absence).
 */
class AnthropicRequestConversionSpec :
    StringSpec({

        fun convert(
            body: String,
            model: String = "claude-x",
            defaultMax: Int = 4096,
        ) = AnthropicConverter.toMessages(ChatRequest.parse(body), model, defaultMax)

        fun JsonObject.arr(key: String) = this[key] as JsonArray

        fun JsonObject.msg(i: Int) = arr("messages")[i].jsonObject

        "system message → top-level system; model is the resolved upstream; user string content stays a string" {
            val c =
                convert(
                    """{"model":"sonnet","messages":[{"role":"system","content":"be brief"},{"role":"user","content":"hi"}],"max_tokens":100}""",
                )
            c.body["model"]!!.jsonPrimitive.content shouldBe "claude-x"
            c.body["system"]!!.jsonPrimitive.content shouldBe "be brief"
            c.body["max_tokens"]!!.jsonPrimitive.int shouldBe 100
            c.body
                .msg(0)["role"]!!
                .jsonPrimitive.content shouldBe "user"
            c.body
                .msg(0)["content"]!!
                .jsonPrimitive.content shouldBe "hi"
        }

        "max_tokens is required: default from config when absent; max_completion_tokens maps to max_tokens" {
            convert("""{"model":"m","messages":[{"role":"user","content":"x"}]}""", defaultMax = 2048)
                .body["max_tokens"]!!
                .jsonPrimitive.int shouldBe 2048
            convert("""{"model":"m","messages":[{"role":"user","content":"x"}],"max_completion_tokens":50}""")
                .body["max_tokens"]!!
                .jsonPrimitive.int shouldBe 50
        }

        "user content parts (text) → Anthropic text blocks" {
            val c = convert("""{"model":"m","messages":[{"role":"user","content":[{"type":"text","text":"hello"}]}]}""")
            val block =
                c.body
                    .msg(0)["content"]!!
                    .jsonArray[0]
                    .jsonObject
            block["type"]!!.jsonPrimitive.content shouldBe "text"
            block["text"]!!.jsonPrimitive.content shouldBe "hello"
        }

        "OpenAI tools[].function → Anthropic tools[] with input_schema" {
            val c =
                convert(
                    """{"model":"m","messages":[{"role":"user","content":"x"}],"tools":[{"type":"function","function":{"name":"get_weather","description":"d","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}]}""",
                )
            val tool = c.body.arr("tools")[0].jsonObject
            tool["name"]!!.jsonPrimitive.content shouldBe "get_weather"
            tool["description"]!!.jsonPrimitive.content shouldBe "d"
            tool["input_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "object"
        }

        "tool_choice: auto→{auto}, required→{any}, none→{none}, function→{tool,name}" {
            fun tc(v: String) =
                convert("""{"model":"m","messages":[{"role":"user","content":"x"}],"tool_choice":$v}""")
                    .body["tool_choice"]!!
                    .jsonObject

            tc("\"auto\"")["type"]!!.jsonPrimitive.content shouldBe "auto"
            tc("\"required\"")["type"]!!.jsonPrimitive.content shouldBe "any"
            tc("\"none\"")["type"]!!.jsonPrimitive.content shouldBe "none"
            tc("""{"type":"function","function":{"name":"f"}}""").let {
                it["type"]!!.jsonPrimitive.content shouldBe "tool"
                it["name"]!!.jsonPrimitive.content shouldBe "f"
            }
        }

        "assistant tool_calls → tool_use blocks (arguments string parsed to input object)" {
            val c =
                convert(
                    """{"model":"m","messages":[{"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{\"a\":1}"}}]}]}""",
                )
            val block =
                c.body
                    .msg(0)["content"]!!
                    .jsonArray[0]
                    .jsonObject
            block["type"]!!.jsonPrimitive.content shouldBe "tool_use"
            block["id"]!!.jsonPrimitive.content shouldBe "c1"
            block["name"]!!.jsonPrimitive.content shouldBe "f"
            block["input"]!!.jsonObject["a"]!!.jsonPrimitive.int shouldBe 1
        }

        "role=tool + tool_call_id → tool_result block folded into a following user turn" {
            val c =
                convert(
                    """{"model":"m","messages":[{"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{}"}}]},{"role":"tool","tool_call_id":"c1","content":"42"}]}""",
                )
            c.body
                .msg(0)["role"]!!
                .jsonPrimitive.content shouldBe "assistant"
            val userTurn = c.body.msg(1)
            userTurn["role"]!!.jsonPrimitive.content shouldBe "user"
            val result = userTurn["content"]!!.jsonArray[0].jsonObject
            result["type"]!!.jsonPrimitive.content shouldBe "tool_result"
            result["tool_use_id"]!!.jsonPrimitive.content shouldBe "c1"
            result["content"]!!.jsonPrimitive.content shouldBe "42"
        }

        "temperature/top_p pass; stop → stop_sequences (string coerced to array)" {
            val c =
                convert(
                    """{"model":"m","messages":[{"role":"user","content":"x"}],"temperature":0.5,"top_p":0.9,"stop":"END"}""",
                )
            c.body["temperature"]!!.jsonPrimitive.content shouldBe "0.5"
            c.body["top_p"]!!.jsonPrimitive.content shouldBe "0.9"
            c.body["stop_sequences"]!!
                .jsonArray[0]
                .jsonPrimitive.content shouldBe "END"
        }

        "C-4 strip-and-log: unmappable + unknown params are dropped AND returned in the stripped list" {
            val c =
                convert(
                    """{"model":"m","messages":[{"role":"user","content":"x"}],"response_format":{"type":"json_object"},"n":2,"seed":7,"logprobs":true,"frobnicate":"yes"}""",
                )
            c.stripped shouldContainAll listOf("frobnicate", "logprobs", "n", "response_format", "seed")
            c.body["response_format"].shouldBeNull()
            c.body["n"].shouldBeNull()
            c.body["seed"].shouldBeNull()
        }
    })
