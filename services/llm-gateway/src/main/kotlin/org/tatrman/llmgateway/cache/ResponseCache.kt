// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.cache

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.tatrman.llmgateway.config.CacheConfig
import org.tatrman.llmgateway.store.RedisConn

/**
 * The exact-match response cache over Redis (E-1, contracts §4). **Best-effort** (1.x parity): a Redis
 * outage is a miss / a no-op store, never a request failure — the caller falls through to a live upstream
 * call. Stores only for a positive TTL (uncacheable catalog rows have `cacheTtlSeconds=0`) and only when the
 * serialized envelope fits `cache.maxBodyBytes` (oversize → skip + metric). Storeless boots don't cache.
 */
class ResponseCache(
    private val redis: RedisConn?,
    private val config: CacheConfig,
    private val metrics: MeterRegistry? = null,
) {
    private val json = Json { encodeDefaults = true }

    fun get(key: String): CacheEnvelope? {
        val r = redis ?: return null
        return try {
            r.sync().get(key)?.let { json.decodeFromString(CacheEnvelope.serializer(), it) }
        } catch (e: Exception) {
            log.warn("cache read failed (best-effort miss)", e)
            metrics?.counter("llm_gateway_cache_error_total", "op", "get")?.increment()
            null
        }
    }

    /** Store under [ttlSeconds]; no-op when disabled, storeless, ttl ≤ 0, over the size cap, or Redis errors. */
    fun put(
        key: String,
        envelope: CacheEnvelope,
        ttlSeconds: Long,
    ) {
        val r = redis ?: return
        if (!config.enabled || ttlSeconds <= 0) return
        val payload = json.encodeToString(CacheEnvelope.serializer(), envelope)
        if (payload.toByteArray().size > config.maxBodyBytes) {
            metrics?.counter("llm_gateway_cache_skip_oversize_total")?.increment()
            return
        }
        try {
            r.sync().setex(key, ttlSeconds, payload)
        } catch (e: Exception) {
            log.warn("cache write failed (best-effort)", e)
            metrics?.counter("llm_gateway_cache_error_total", "op", "put")?.increment()
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ResponseCache::class.java)
    }
}
