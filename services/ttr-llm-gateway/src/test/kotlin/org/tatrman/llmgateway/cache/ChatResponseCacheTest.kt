// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.cache

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.tatrman.llmgateway.web.ChatCompletionRequestApi
import org.tatrman.llmgateway.web.ChatCompletionResponseApi
import org.tatrman.llmgateway.web.ChatMessageApi
import org.tatrman.llmgateway.web.UsageApi
import java.time.Duration

/**
 * Phase 09 A3 / DF-A3-CACHE — verifies the cache contract: key derivation is stable across
 * volatile-field changes (conversation id, model_tags, background), changes when content
 * changes, stored values round-trip through Redis, and the `cached` flag is wiped on store +
 * re-applied on hit by the caller (ModelService — exercised in its own integration path).
 *
 * Uses mockk to substitute the Spring `StringRedisTemplate` — no real Redis broker needed.
 */
class ChatResponseCacheTest :
    StringSpec({

        fun newCache(): Pair<ChatResponseCache, ValueOperations<String, String>> {
            val template = mockk<StringRedisTemplate>(relaxed = true)
            val ops = mockk<ValueOperations<String, String>>(relaxed = true)
            every { template.opsForValue() } returns ops
            return ChatResponseCache(template, ttlSeconds = 60, keyPrefix = "test:") to ops
        }

        "keyFor — same content → same key (idempotent)" {
            val (cache, _) = newCache()
            val req =
                ChatCompletionRequestApi(
                    model = "gpt-4o",
                    messages = listOf(ChatMessageApi("user", "hello")),
                    temperature = 0.7f,
                )
            cache.keyFor(req) shouldBe cache.keyFor(req)
        }

        "keyFor — different content → different key" {
            val (cache, _) = newCache()
            val a =
                ChatCompletionRequestApi(
                    model = "gpt-4o",
                    messages = listOf(ChatMessageApi("user", "hello")),
                )
            val b =
                ChatCompletionRequestApi(
                    model = "gpt-4o",
                    messages = listOf(ChatMessageApi("user", "hi")),
                )
            cache.keyFor(a) shouldNotBe cache.keyFor(b)
        }

        "keyFor — volatile fields (conversation, modelTags, background) don't influence the key" {
            // Pins the contract: two requests with the same content but different volatile
            // fields hit the same cache entry, so per-conversation noise doesn't fragment the
            // cache.
            val (cache, _) = newCache()
            val base =
                ChatCompletionRequestApi(
                    model = "gpt-4o",
                    messages = listOf(ChatMessageApi("user", "hello")),
                )
            val noisy =
                base.copy(
                    conversation = "conv-abc-xyz",
                    modelTags = listOf("preferred", "cheap"),
                    background = "ignore me",
                )
            cache.keyFor(base) shouldBe cache.keyFor(noisy)
        }

        "keyFor — different model → different key" {
            val (cache, _) = newCache()
            val a =
                ChatCompletionRequestApi(
                    model = "gpt-4o",
                    messages = listOf(ChatMessageApi("user", "hello")),
                )
            val b = a.copy(model = "claude-3.5-sonnet")
            cache.keyFor(a) shouldNotBe cache.keyFor(b)
        }

        "keyFor — different temperature → different key (parameters influence output)" {
            val (cache, _) = newCache()
            val a =
                ChatCompletionRequestApi(
                    model = "gpt-4o",
                    messages = listOf(ChatMessageApi("user", "hi")),
                    temperature = 0.0f,
                )
            val b = a.copy(temperature = 0.7f)
            cache.keyFor(a) shouldNotBe cache.keyFor(b)
        }

        "keyFor — key carries the configured prefix" {
            val (cache, _) = newCache()
            val req = ChatCompletionRequestApi(model = "gpt-4o", messages = listOf(ChatMessageApi("user", "hi")))
            cache.keyFor(req).shouldStartWith("test:")
        }

        "lookup — Redis miss returns null (no exception)" {
            val (cache, ops) = newCache()
            every { ops.get(any<String>()) } returns null
            val req = ChatCompletionRequestApi(model = "gpt-4o", messages = listOf(ChatMessageApi("user", "hi")))
            cache.lookup(req).shouldBeNull()
        }

        "lookup — Redis error is swallowed (returns null, logs, doesn't propagate)" {
            val (cache, ops) = newCache()
            every { ops.get(any<String>()) } throws RuntimeException("redis went down")
            val req = ChatCompletionRequestApi(model = "gpt-4o", messages = listOf(ChatMessageApi("user", "hi")))
            cache.lookup(req).shouldBeNull()
        }

        "lookup — valid stored response round-trips" {
            val (cache, ops) = newCache()
            val req = ChatCompletionRequestApi(model = "gpt-4o", messages = listOf(ChatMessageApi("user", "hi")))
            val stored =
                ChatCompletionResponseApi(
                    id = "abc",
                    model = "gpt-4o",
                    content = "hi there",
                    usage = UsageApi(totalTokens = 5, inputTokens = 2, outputTokens = 3, cost = 0.0001),
                    cached = null,
                )
            // The cache serialises via kotlinx.serialization — store-then-lookup uses real JSON.
            val storedJson =
                kotlinx.serialization.json.Json
                    .encodeToString(stored)
            every { ops.get(cache.keyFor(req)) } returns storedJson

            val hit = cache.lookup(req)!!
            hit.id shouldBe "abc"
            hit.content shouldBe "hi there"
            hit.usage?.cost shouldBe 0.0001
        }

        "store — writes the key with the configured TTL" {
            val (cache, ops) = newCache()
            val ttlCapture = slot<Duration>()
            every { ops.set(any<String>(), any<String>(), capture(ttlCapture)) } returns Unit

            val req = ChatCompletionRequestApi(model = "gpt-4o", messages = listOf(ChatMessageApi("user", "hi")))
            val resp = ChatCompletionResponseApi(id = "x", model = "gpt-4o", content = "hi", cached = false)
            cache.store(req, resp)

            ttlCapture.captured.seconds shouldBe 60L
            verify(exactly = 1) { ops.set(cache.keyFor(req), any<String>(), any<Duration>()) }
        }

        "store — strips the `cached` flag before writing (so a hit re-applies cached=true cleanly)" {
            val (cache, ops) = newCache()
            val payloadCapture = slot<String>()
            every { ops.set(any<String>(), capture(payloadCapture), any<Duration>()) } returns Unit

            val req = ChatCompletionRequestApi(model = "gpt-4o", messages = listOf(ChatMessageApi("user", "hi")))
            val resp = ChatCompletionResponseApi(id = "x", model = "gpt-4o", content = "hi", cached = false)
            cache.store(req, resp)

            // The serialised payload should NOT carry `"cached":false`; lookup adds `cached=true`.
            payloadCapture.captured.contains("\"cached\":false") shouldBe false
        }

        "store — Redis error is swallowed (logs, doesn't propagate)" {
            val (cache, ops) = newCache()
            every { ops.set(any<String>(), any<String>(), any<Duration>()) } throws RuntimeException("redis exploded")
            val req = ChatCompletionRequestApi(model = "gpt-4o", messages = listOf(ChatMessageApi("user", "hi")))
            val resp = ChatCompletionResponseApi(id = "x", model = "gpt-4o", content = "hi", cached = false)
            // No exception escapes.
            cache.store(req, resp)
        }
    })
