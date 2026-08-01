// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.cache

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.tatrman.llmgateway.wire.ChatRequest

/**
 * LG-P5·S1·T1 — the cache-key projection is PINNED here (contracts §4, as 1.x `ChatResponseCache.keyFor`
 * was). Volatile fields (`stream`/`stream_options`/`user`/`metadata`/`model_tags`) don't affect the key;
 * `messages`/`temperature`/unknown-delta fields do; and the LOGICAL (resolved) model keys it, so two
 * caller names that resolve to the same catalog entry — and a fallback-served response — share a key.
 */
class CacheKeySpec :
    StringSpec({

        val prefix = "llm-gateway:chat:"

        fun key(
            body: String,
            model: String = "m",
        ) = CacheKey.of(prefix, model, ChatRequest.parse(body))

        "same request modulo volatile fields ⇒ same key" {
            val withVolatile =
                key(
                    """{"model":"haiku","messages":[{"role":"user","content":"hi"}],"stream":true,"stream_options":{"include_usage":true},"user":"u1","metadata":{"x":1},"model_tags":["fast"]}""",
                )
            val without = key("""{"model":"haiku","messages":[{"role":"user","content":"hi"}],"stream":false}""")
            withVolatile shouldBe without
        }

        "different messages ⇒ different key" {
            key("""{"messages":[{"role":"user","content":"hi"}]}""") shouldNotBe
                key("""{"messages":[{"role":"user","content":"bye"}]}""")
        }

        "different temperature ⇒ different key" {
            key("""{"messages":[],"temperature":0.2}""") shouldNotBe key("""{"messages":[],"temperature":0.9}""")
        }

        "an unknown-delta field is part of the key (byte-faithful semantics)" {
            key("""{"messages":[],"seed":1}""") shouldNotBe key("""{"messages":[],"seed":2}""")
        }

        "logical (resolved) model keys it — different `model` field, same resolved entry ⇒ same key" {
            val viaAlias =
                CacheKey.of(
                    prefix,
                    "claude-haiku-4-5",
                    ChatRequest.parse("""{"model":"haiku","messages":[{"role":"user","content":"hi"}]}"""),
                )
            val viaName =
                CacheKey.of(
                    prefix,
                    "claude-haiku-4-5",
                    ChatRequest.parse("""{"model":"claude-haiku-4-5","messages":[{"role":"user","content":"hi"}]}"""),
                )
            viaAlias shouldBe viaName
        }

        "object key order is non-semantic (canonicalized)" {
            key("""{"messages":[{"role":"user","content":"hi"}],"temperature":0.5}""") shouldBe
                key("""{"temperature":0.5,"messages":[{"role":"user","content":"hi"}]}""")
        }

        "the key is the prefix + 64 hex chars" {
            key("""{"messages":[]}""") shouldMatch Regex("^llm-gateway:chat:[0-9a-f]{64}$")
        }
    })
