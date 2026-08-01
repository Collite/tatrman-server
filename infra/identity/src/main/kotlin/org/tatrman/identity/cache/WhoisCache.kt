// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import java.time.Duration

class WhoisCache<K : Any, V : Any>(
    private val ttlSeconds: Long = 300,
    private val maxSize: Long = 1000,
) {
    private val logger = LoggerFactory.getLogger("WhoisCache")
    private val cache: Cache<K, V> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
            .maximumSize(maxSize)
            .recordStats()
            .build()

    fun get(
        key: K,
        loader: () -> V,
    ): V =
        cache
            .get(key) {
                logger.debug("Cache miss for key: {}", key)
                loader()
            }.also {
                logger.trace("Cache hit for key: {}", key)
            }

    fun getIfPresent(key: K): V? = cache.getIfPresent(key)

    fun put(
        key: K,
        value: V,
    ) {
        cache.put(key, value)
        logger.debug("Cached value for key: {}", key)
    }

    fun invalidate(key: K) {
        cache.invalidate(key)
        logger.debug("Invalidated cache for key: {}", key)
    }

    fun invalidateAll() {
        cache.invalidateAll()
        logger.debug("Invalidated all cache entries")
    }

    fun stats() = cache.stats()

    companion object {
        fun <K : Any, V : Any> create(
            ttlSeconds: Long = 300,
            maxSize: Long = 1000,
        ): WhoisCache<K, V> = WhoisCache(ttlSeconds, maxSize)
    }
}
