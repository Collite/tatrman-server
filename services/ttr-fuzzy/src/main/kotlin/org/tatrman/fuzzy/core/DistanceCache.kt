// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import java.util.concurrent.ConcurrentHashMap

class DistanceCache(
    // FZ-P1 T3 — sized to the real distinct-pair population (tens of thousands even for a large
    // corpus) so the hot path never evicts. The legacy 10k default + wholesale clear() past 15k made
    // the cache thrash permanently at product-name scale; here a full cache simply stops storing
    // (compute-without-store), which is allocation-free and cannot thrash. Token interning replaces
    // the string-pair key in FZ-P2.
    private val maxSize: Int = 200_000,
) {
    private val cache = ConcurrentHashMap<Pair<String, String>, Double>()

    /**
     * Memoised distance for an unordered token pair. Callers MUST pass already-folded tokens
     * ([Candidate.tokenize] / [TextNormalizer.fold] output) — the cache keys on them verbatim, so
     * an unfolded token would key a distinct (and wrong) entry.
     */
    fun getOrCompute(
        token1: String,
        token2: String,
        compute: () -> Double,
    ): Double {
        val key = if (token1 <= token2) token1 to token2 else token2 to token1

        // Bounded without ever wiping: once full, keep serving computed values without storing them.
        if (cache.size >= maxSize && !cache.containsKey(key)) {
            return compute()
        }

        return cache.computeIfAbsent(key) { compute() }
    }

    fun clear() {
        cache.clear()
    }

    fun size(): Int = cache.size
}
