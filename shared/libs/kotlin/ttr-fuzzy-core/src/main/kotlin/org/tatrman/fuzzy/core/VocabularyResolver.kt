// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import info.debatty.java.stringsimilarity.Levenshtein

/**
 * A query token resolved to a vocabulary token: its [tokenId], the edit [distance], the per-token
 * match [quality] (`1 − d/maxLen`, the same formula the scorer uses), and the IDF [weight] of the
 * matched vocabulary token.
 */
data class ResolvedToken(
    val tokenId: Int,
    val distance: Int,
    val quality: Double,
    val weight: Double,
)

/**
 * FZ-P2 — resolves a query token ONCE against the distinct token [vocabulary] (100–1000× smaller
 * than the candidate list), replacing the per-candidate Levenshtein loop. An exact hit short-circuits;
 * otherwise it scans only the length-adjacent buckets (a token within ED 2 differs in length by ≤ 2)
 * with a cutoff-bounded Levenshtein.
 *
 * Memoisation is per-instance only (construct one per query): a query rarely repeats a token, and
 * there is deliberately NO cross-request cache — this is what retires [DistanceCache] on the
 * retrieval path.
 */
class VocabularyResolver(
    private val vocabulary: TokenVocabulary,
) {
    private val levenshtein = Levenshtein()
    private val memo = HashMap<String, List<ResolvedToken>>()

    /**
     * Vocabulary tokens within edit distance [MAX_EDIT_DISTANCE] of [queryToken] (must be folded),
     * sorted by (distance, tokenId) for determinism. An exact hit returns a single distance-0 entry.
     * Empty when nothing is close enough.
     */
    fun resolve(queryToken: String): List<ResolvedToken> = memo.getOrPut(queryToken) { resolveUncached(queryToken) }

    private fun resolveUncached(queryToken: String): List<ResolvedToken> {
        val exact = vocabulary.idOf(queryToken)
        if (exact >= 0) {
            return listOf(ResolvedToken(exact, distance = 0, quality = 1.0, weight = vocabulary.idf(exact)))
        }

        val qLen = queryToken.length
        val out = ArrayList<ResolvedToken>()
        for (len in (qLen - MAX_EDIT_DISTANCE)..(qLen + MAX_EDIT_DISTANCE)) {
            val bucket = vocabulary.lengthBuckets[len] ?: continue
            for (id in bucket) {
                val vocabToken = vocabulary.tokens[id]
                // Cutoff at MAX_EDIT_DISTANCE + 1: debatty early-exits returning the limit once a row
                // minimum reaches it, so a return value of MAX+1 means "distance ≥ MAX+1" (a miss),
                // while 0..MAX are exact. This is the only reliable way to tell an ED-2 hit from ED-3.
                val d = levenshtein.distance(queryToken, vocabToken, MAX_EDIT_DISTANCE + 1).toInt()
                if (d > MAX_EDIT_DISTANCE) continue
                val maxLen = maxOf(qLen, vocabToken.length).coerceAtLeast(1)
                val quality = (1.0 - d.toDouble() / maxLen).coerceIn(0.0, 1.0)
                out.add(ResolvedToken(id, d, quality, vocabulary.idf(id)))
            }
        }
        out.sortWith(compareBy({ it.distance }, { it.tokenId }))
        return out
    }

    companion object {
        const val MAX_EDIT_DISTANCE = 2
    }
}
