package org.tatrman.kantheon.echo.core

import java.util.concurrent.ConcurrentHashMap

class DistanceCache(
    private val maxSize: Int = 10000,
) {
    private val cache = ConcurrentHashMap<Pair<String, String>, Double>()

    fun getOrCompute(
        token1: String,
        token2: String,
        compute: () -> Double,
    ): Double {
        val t1 = token1.lowercase()
        val t2 = token2.lowercase()
        val key = if (t1 < t2) t1 to t2 else t2 to t1

        if (cache.size > maxSize * 1.5) {
            cache.clear()
        }

        return cache.computeIfAbsent(key) { compute() }
    }

    fun clear() {
        cache.clear()
    }

    fun size(): Int = cache.size
}
