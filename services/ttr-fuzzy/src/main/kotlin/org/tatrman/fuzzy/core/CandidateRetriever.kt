// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * Which retrieval path the TATRMAN matcher uses. [LEGACY] scores every exact-token seed; [INDEX_FIRST]
 * resolves each query token once against the vocabulary and exact-rescores the top candidates (same
 * scores, far faster). Config key `fuzzy.token-based.retrieval` — unknown/blank ⇒ [LEGACY] (mirrors
 * `AlgorithmType.fromString`'s silent defaulting).
 */
enum class RetrievalMode {
    LEGACY,
    INDEX_FIRST,
    ;

    companion object {
        fun fromString(value: String?): RetrievalMode =
            if (value.isNullOrBlank()) {
                LEGACY
            } else {
                try {
                    valueOf(value.trim().uppercase().replace('-', '_'))
                } catch (e: IllegalArgumentException) {
                    LEGACY
                }
            }
    }
}

/**
 * FZ-P2 — the retrieval seam. Given a query, return the candidate ordinals (indices into the
 * category's candidate list, as returned by `StringRepository.getCandidates(category)`) most worth
 * scoring, best-first. This is *retrieval only*: the caller exact-rescores the returned candidates
 * with the unchanged scorer, so scores and the `fuzzy.match:v1` contract are untouched.
 *
 * FZ-P2 ships [IndexFirstRetriever] (in-memory, term-at-a-time over [TokenVocabulary]); the FZO
 * effort plugs an OpenSearch-backed implementation into this same interface without touching scoring.
 */
interface CandidateRetriever {
    /**
     * Candidate ordinals worth scoring for the query, best-first (approximate order; the caller
     * rescores exactly). At most [topN] are returned. Ties are broken by ascending ordinal for
     * determinism. An explicit-but-unknown [category] yields an empty result.
     */
    fun retrieve(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        category: String?,
        topN: Int,
    ): IntArray
}
