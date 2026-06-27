package org.tatrman.kantheon.echo.core

import info.debatty.java.stringsimilarity.Levenshtein
import org.slf4j.LoggerFactory

class TokenBasedMatcher(
    val candidates: List<Candidate> = emptyList(),
    val tokenIndex: TokenIndex? = null,
    val distanceCache: DistanceCache = DistanceCache(),
    private val distanceThreshold: Double = 0.20,
    private val orderBonusMultiplier: Double = 1.05,
    private val maxOrderBonus: Double = 1.5,
    // GH #69 — weight each token match by the rarity (IDF) of the matched
    // candidate token. Requires a corpus (tokenIndex); the corpus-less
    // similarity(s1,s2) path always uses the legacy char-overlap score. Flag
    // off ⇒ exact legacy behaviour, for rollback / A-B.
    private val idfEnabled: Boolean = true,
) {
    private val logger = LoggerFactory.getLogger(TokenBasedMatcher::class.java)
    private val levenshtein = Levenshtein()

    fun match(
        query: String,
        limit: Int,
    ): List<Pair<Candidate, Double>> {
        val tokens = Candidate.tokenize(query)
        return match(tokens, tokens, limit)
    }

    /** Convenience overload — query surface tokens only (lemma axis == surface axis). */
    fun match(
        queryTokens: List<String>,
        limit: Int,
    ): List<Pair<Candidate, Double>> = match(queryTokens, queryTokens, limit)

    /**
     * Match a query against the candidate set on two axes — folded-surface tokens and folded
     * lemma tokens — keeping the better score per candidate. Callers that lemmatise the query
     * (Phase 02 Stage B) pass the lemma tokens as [queryLemmaTokens]; when lemmatisation is off,
     * both lists are the same and behaviour is identical to the surface-only matcher.
     */
    fun match(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        limit: Int,
    ): List<Pair<Candidate, Double>> {
        if (querySurfaceTokens.isEmpty() && queryLemmaTokens.isEmpty()) {
            return emptyList()
        }

        logger.debug("Matching query tokens: surface=$querySurfaceTokens lemma=$queryLemmaTokens")

        val index = tokenIndex
        val seedTokens = (querySurfaceTokens + queryLemmaTokens).distinct()
        val seedIds = index?.findCandidatesWithAnyToken(seedTokens) ?: emptySet()

        if (seedIds.isEmpty()) {
            // No candidate shares an exact token with the query (e.g. every token is a typo) — fuzzy-score all.
            return scoreAll(querySurfaceTokens, queryLemmaTokens, candidates, limit)
        }

        val seedCandidates = seedIds.mapNotNull { index?.getCandidateById(it) }
        val seedResults = scoreAndSort(querySurfaceTokens, queryLemmaTokens, seedCandidates)
        // Defensively also fuzzy-score a few candidates with no token overlap, in case one is a closer fuzzy match.
        val extraResults =
            scoreAndSort(querySurfaceTokens, queryLemmaTokens, candidates.filter { it.id !in seedIds }.take(limit))

        return (seedResults + extraResults).sortedByDescending { it.second }.take(limit)
    }

    /** Score a candidate on both axes (surface and lemma) and keep the better. */
    private fun scoreCandidate(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidate: Candidate,
    ): Double {
        val surfaceScore = axisScore(querySurfaceTokens, candidate.tokens)
        // Skip the lemma axis when it's identical to the surface axis (no lemmatiser) — cheap shortcut.
        val lemmaIdentical = queryLemmaTokens == querySurfaceTokens && candidate.lemmaTokens == candidate.tokens
        val lemmaScore =
            if (lemmaIdentical) 0.0 else axisScore(queryLemmaTokens, candidate.lemmaTokens)
        return maxOf(surfaceScore, lemmaScore)
    }

    /**
     * Score one axis (surface OR lemma) of a query against a candidate. Uses the
     * IDF-weighted aggregation when enabled and a corpus is available; otherwise
     * the legacy char-overlap score. Empty token lists score 0.
     */
    private fun axisScore(
        queryTokens: List<String>,
        candidateTokens: List<String>,
    ): Double {
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return 0.0
        return if (idfEnabled && tokenIndex != null) {
            idfWeightedScore(queryTokens, candidateTokens)
        } else {
            calculateScore(queryTokens, candidateTokens, calculateSetDistance(queryTokens, candidateTokens))
        }
    }

    /**
     * GH #69 — IDF-weighted token score. Each query token is matched to its
     * nearest candidate token (Levenshtein); the per-token match quality
     * (1 − dist/maxLen, ∈ [0,1]) is weighted by the IDF of the *matched
     * candidate token* and averaged. Matching a token shared by many candidates
     * (low IDF) barely moves the score; matching a near-unique one (high IDF)
     * dominates it. The order bonus stays a multiplier; result is capped at 1.0.
     */
    private fun idfWeightedScore(
        queryTokens: List<String>,
        candidateTokens: List<String>,
    ): Double {
        var weightedSum = 0.0
        var weightTotal = 0.0
        for (queryToken in queryTokens) {
            var bestDistance = Double.MAX_VALUE
            var bestToken: String? = null
            for (candidateToken in candidateTokens) {
                val distance =
                    distanceCache.getOrCompute(queryToken, candidateToken) {
                        levenshtein.distance(queryToken, candidateToken)
                    }
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestToken = candidateToken
                }
                if (distance == 0.0) break
            }
            val matched = bestToken ?: continue
            val maxLen = maxOf(queryToken.length, matched.length).coerceAtLeast(1).toDouble()
            val matchQuality = (1.0 - bestDistance / maxLen).coerceIn(0.0, 1.0)
            val weight = tokenIndex?.idf(matched) ?: 1.0
            weightedSum += weight * matchQuality
            weightTotal += weight
        }
        if (weightTotal == 0.0) return 0.0
        val baseScore = weightedSum / weightTotal
        // Order bonus stays a multiplier, matching the legacy score's range
        // (an ordered exact match reaches baseScore·orderBonus > 1.0; callers
        // already treat ≥1.0 as exact). Not capped here for that parity.
        val orderBonus = calculateOrderBonus(queryTokens, candidateTokens)
        return baseScore * orderBonus
    }

    private fun scoreAndSort(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidates: List<Candidate>,
    ): List<Pair<Candidate, Double>> =
        candidates
            .map { it to scoreCandidate(querySurfaceTokens, queryLemmaTokens, it) }
            .sortedByDescending { it.second }

    private fun scoreAll(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidates: List<Candidate>,
        limit: Int,
    ): List<Pair<Candidate, Double>> = scoreAndSort(querySurfaceTokens, queryLemmaTokens, candidates).take(limit)

    fun calculateSimilarity(
        s1: String,
        s2: String,
    ): Double {
        val tokens1 = Candidate.tokenize(s1)
        val tokens2 = Candidate.tokenize(s2)
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0

        val distance = calculateSetDistance(tokens1, tokens2)
        return calculateScore(tokens1, tokens2, distance)
    }

    private fun calculateSetDistance(
        queryTokens: List<String>,
        candidateTokens: List<String>,
    ): Double {
        var totalDistance = 0.0

        for (queryToken in queryTokens) {
            var bestDistance = Double.MAX_VALUE

            for (candidateToken in candidateTokens) {
                val distance =
                    distanceCache.getOrCompute(queryToken, candidateToken) {
                        levenshtein.distance(queryToken, candidateToken)
                    }

                if (distance == 0.0) {
                    bestDistance = 0.0
                    break
                }

                if (distance < bestDistance) {
                    bestDistance = distance
                }
            }

            if (bestDistance == Double.MAX_VALUE) {
                bestDistance = queryToken.length.toDouble()
            }

            totalDistance += bestDistance
        }

        return totalDistance
    }

    private fun calculateScore(
        queryTokens: List<String>,
        candidateTokens: List<String>,
        distance: Double,
    ): Double {
        val totalChars = queryTokens.sumOf { it.length } + candidateTokens.sumOf { it.length }
        if (totalChars == 0) return 1.0

        val maxPossibleDistance = totalChars.toDouble()
        val baseScore = 1.0 - (distance / maxPossibleDistance)

        val orderBonus = calculateOrderBonus(queryTokens, candidateTokens)

        return baseScore * orderBonus
    }

    private fun calculateOrderBonus(
        queryTokens: List<String>,
        candidateTokens: List<String>,
    ): Double {
        var correctPairs = 0

        val queryLower = queryTokens.map { it.lowercase() }
        val candidateLower = candidateTokens.map { it.lowercase() }

        // Optimization: Map tokens to their FIRST position in candidate
        val candidatePositions = mutableMapOf<String, Int>()
        candidateLower.forEachIndexed { index, token ->
            candidatePositions.putIfAbsent(token, index)
        }

        for (i in queryLower.indices) {
            for (j in queryLower.indices) {
                if (i < j) {
                    val qi = queryLower[i]
                    val qj = queryLower[j]

                    val ci = candidatePositions[qi]
                    val cj = candidatePositions[qj]

                    if (ci != null && cj != null && ci < cj) {
                        correctPairs++
                    }
                }
            }
        }

        val bonus = Math.pow(orderBonusMultiplier, correctPairs.toDouble())
        return bonus.coerceAtMost(maxOrderBonus)
    }
}

class TokenBasedAlgorithm : MatchingAlgorithm {
    private var matcher: TokenBasedMatcher = TokenBasedMatcher()

    fun setCandidates(
        candidates: List<Candidate>,
        tokenIndex: TokenIndex,
        distanceCache: DistanceCache,
    ) {
        matcher = TokenBasedMatcher(candidates, tokenIndex, distanceCache)
    }

    fun updateConfig(
        distanceThreshold: Double,
        orderBonusMultiplier: Double,
        maxOrderBonus: Double,
    ) {
        matcher =
            TokenBasedMatcher(
                matcher.candidates,
                matcher.tokenIndex,
                matcher.distanceCache,
                distanceThreshold,
                orderBonusMultiplier,
                maxOrderBonus,
            )
    }

    override fun distance(
        s1: String,
        s2: String,
    ): Double {
        val score = similarity(s1, s2)
        val maxLen = maxOf(s1.length, s2.length).toDouble()
        return (1.0 - score) * maxLen
    }

    override fun similarity(
        s1: String,
        s2: String,
    ): Double = matcher.calculateSimilarity(s1, s2)

    companion object {
        private val levenshtein = Levenshtein()
    }
}
