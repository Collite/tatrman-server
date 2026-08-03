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

    /**
     * RV-39 — the layer-version tuple (S-1). Defaulted so the many existing fakes in the suites
     * keep compiling: a repository that knows about no layers reports none, which is the honest
     * answer for a member-only store.
     */
    fun layerVersions(): LayerVersions = LayerVersions()

    /**
     * RV-P1.4 T5 — every category key this store can answer for (lower-cased), or **null when the
     * store does not report them**.
     *
     * Null rather than an empty set on purpose: "I don't publish my categories" and "I have none"
     * are different facts, and a lookup that conflated them would report every requested category
     * as unknown. The many existing fakes take the default and simply say nothing.
     */
    fun knownCategories(): Set<String>? = null
}
