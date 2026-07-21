// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * FZ-P3 — the read-side seam the engine needs from a candidate store: per-category candidates,
 * the legacy token index, the (legacy-path) distance cache, the interned vocabulary, and the version
 * stamp. The service's `StringRepository` (refresh loop, loaders, config — all service concerns)
 * implements this, so the engine ([FuzzyMatcher]) stays free of that machinery. Category keys are
 * resolved case-insensitively by the implementation; an explicit-but-unknown category returns an
 * empty index/vocabulary (never the global one) — the per-column leak guard.
 */
interface MatchRepository {
    fun getCandidates(category: String?): List<Candidate>

    fun getTokenIndex(category: String? = null): TokenIndex

    fun getDistanceCache(category: String? = null): DistanceCache

    fun getVocabulary(category: String? = null): TokenVocabulary

    fun vocabularyVersion(): String
}
