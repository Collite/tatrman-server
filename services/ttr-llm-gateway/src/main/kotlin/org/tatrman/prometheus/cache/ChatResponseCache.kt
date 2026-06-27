package org.tatrman.prometheus.cache

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.tatrman.prometheus.web.ChatCompletionRequestApi
import org.tatrman.prometheus.web.ChatCompletionResponseApi
import java.security.MessageDigest
import java.time.Duration

/**
 * Phase 09 A3 / DF-A3-CACHE — Redis-backed response cache for the Prometheus chat endpoint.
 *
 * Pythia and other consumers see the same response twice (same model + messages + options) → no
 * point re-paying the provider. The cache key is a SHA-256 of a deterministic JSON projection
 * of the request fields that influence the model's output. Volatile / per-request fields —
 * conversation id, `model_tags` (these are resolution hints, not content), `background` —
 * are excluded. TTL is configurable; default one hour.
 *
 * Bean is conditional on `llm.cache.enabled=true` so test profiles that don't have a Redis
 * broker on the network skip the bean entirely. `ModelService` injects it as an optional
 * dependency (`?`) and treats the cache as best-effort: cache lookup failures degrade to a
 * live call rather than fail the request.
 *
 * Threading: `StringRedisTemplate` is thread-safe; this class is stateless beyond config.
 *
 * Honest scope: the cache stores **complete responses**, not partial / streaming chunks; the
 * existing chat endpoint is non-streaming so this is fine for v1. Stream caching is tracked as
 * a follow-up (`DF-A3-CACHE-STREAMING`) for the day streaming lands.
 */
@Component
@ConditionalOnProperty(name = ["llm.cache.enabled"], havingValue = "true")
class ChatResponseCache(
    private val redis: StringRedisTemplate,
    @Value("\${llm.cache.ttl-seconds:3600}") private val ttlSeconds: Long,
    @Value("\${llm.cache.key-prefix:prometheus:chat:}") private val keyPrefix: String,
) {
    private val logger = LoggerFactory.getLogger(ChatResponseCache::class.java)
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    /** Returns the cached response, or `null` on miss / Redis error / cache-deserialise failure. */
    fun lookup(request: ChatCompletionRequestApi): ChatCompletionResponseApi? {
        val key = keyFor(request)
        return try {
            val raw = redis.opsForValue().get(key) ?: return null
            json.decodeFromString<ChatCompletionResponseApi>(raw)
        } catch (e: Exception) {
            logger.warn("Cache lookup failed (key={}); falling back to live call: {}", key, e.message)
            null
        }
    }

    /** Stores [response] under the request's cache key with the configured TTL. Best-effort. */
    fun store(
        request: ChatCompletionRequestApi,
        response: ChatCompletionResponseApi,
    ) {
        val key = keyFor(request)
        try {
            // Strip the volatile `cached` flag before storing so a later lookup doesn't observe
            // a stale value. The lookup site re-applies `cached = true` on hit.
            val toStore = response.copy(cached = null)
            redis.opsForValue().set(key, json.encodeToString(toStore), Duration.ofSeconds(ttlSeconds))
        } catch (e: Exception) {
            logger.warn("Cache store failed (key={}); response served live: {}", key, e.message)
        }
    }

    /**
     * Public for tests — pin the key shape so a future refactor doesn't silently invalidate
     * every existing cache entry. The key projects only fields that influence the response.
     */
    fun keyFor(request: ChatCompletionRequestApi): String {
        val projected = projectForKey(request)
        val canonical = json.encodeToString(projected)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) { for (b in digest) append("%02x".format(b)) }
        return "$keyPrefix$hex"
    }

    private fun projectForKey(req: ChatCompletionRequestApi): ChatCompletionRequestApi =
        // Wipe per-request volatile fields so the key keys on content/parameters only.
        req.copy(
            conversation = null,
            background = null,
            modelTags = null,
        )
}
