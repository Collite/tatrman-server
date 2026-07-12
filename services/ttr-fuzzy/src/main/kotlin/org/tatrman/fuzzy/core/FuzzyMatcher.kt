// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import org.tatrman.fuzzy.telemetry.FuzzyTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One step of an algorithm cascade: run [algorithm], treat it as the winner if its top-1 score ≥ [minScore]. */
data class CascadeStep(
    val algorithm: AlgorithmType,
    val minScore: Double,
)

/** A BatchMatch span (contracts §2): match [query] across [categories]; explicit-unknown ⇒ EMPTY slot. */
data class SpanQuery(
    val query: String,
    val categories: List<String>,
    val limit: Int = 10,
)

/** One span's result within a BatchMatch. */
data class BatchSpanResult(
    val matches: List<FuzzyMatchResult>,
    val matchedAlgorithm: AlgorithmType?,
)

/** BatchMatch outcome: positional [results] + the one [vocabularyVersion] read for the whole call. */
data class BatchMatchResult(
    val results: List<BatchSpanResult>,
    val vocabularyVersion: String,
)

/** Outcome of a cascade run: the matches returned and which algorithm produced them (null if no steps). */
data class CascadeOutcome(
    val matches: List<FuzzyMatchResult>,
    val matchedAlgorithm: AlgorithmType?,
)

/**
 * Builds the cascade for a request: the explicit [algorithms] when non-empty,
 * otherwise a single legacy-[algorithm] step that always "wins" (min-score
 * −∞) so the pre-cascade single-algorithm contract is preserved.
 */
fun cascadeFrom(
    algorithms: List<CascadeStep>,
    algorithm: String?,
): List<CascadeStep> =
    algorithms.ifEmpty {
        listOf(CascadeStep(AlgorithmType.fromString(algorithm), Double.NEGATIVE_INFINITY))
    }

class FuzzyMatcher(
    private val repository: StringRepository,
    private val telemetry: FuzzyTelemetry? = null,
    private val lemmatizer: Lemmatizer = NoopLemmatizer,
    // GH #69 — IDF token weighting for the TATRMAN (token-based) path. Defaulted
    // so existing call sites / tests are unaffected; wired from config in Application.
    private val idfEnabled: Boolean = true,
) {
    suspend fun match(
        query: String,
        category: String?,
        algorithmType: AlgorithmType?,
        limit: Int,
    ): List<FuzzyMatchResult> = runSingle(query, category, algorithmType ?: AlgorithmType.TATRMAN, limit)

    /**
     * Runs an algorithm cascade. The steps are tried in order; the first whose
     * top-1 score is ≥ its [CascadeStep.minScore] wins and its full (sorted,
     * limited) result set is returned — precision-first, recall-fallback. This
     * avoids comparing scores across algorithms (different scales): one
     * algorithm's results are returned wholesale, never merged.
     *
     * `minScore` is the cascade *decision gate* on the top candidate, NOT an
     * output filter — the winner's runner-up candidates are returned intact so
     * callers can still detect ambiguity / offer clarification.
     *
     * If no step clears its bar, the last (most recall-oriented) step's
     * best-effort results are returned, with that algorithm reported as the
     * match. Empty [steps] yields an empty outcome.
     */
    suspend fun matchCascade(
        query: String,
        category: String?,
        steps: List<CascadeStep>,
        limit: Int,
    ): CascadeOutcome {
        if (steps.isEmpty()) return CascadeOutcome(emptyList(), null)
        var lastResults: List<FuzzyMatchResult> = emptyList()
        var lastAlgorithm: AlgorithmType? = null
        for (step in steps) {
            val results = runSingle(query, category, step.algorithm, limit)
            lastResults = results
            lastAlgorithm = step.algorithm
            val topScore = results.firstOrNull()?.score ?: Double.NEGATIVE_INFINITY
            if (topScore >= step.minScore) {
                return CascadeOutcome(results, step.algorithm)
            }
        }
        // Option (a): no step qualified — fall back to the last step's results.
        return CascadeOutcome(lastResults, lastAlgorithm)
    }

    /**
     * RG-P2.S2 — N spans × M categories in ONE call (the resolver's gate-spans
     * step). Each span matches its [SpanQuery.query] across its categories
     * (explicit-unknown ⇒ EMPTY slot, the per-slot leak guard) and the merged
     * top-[SpanQuery.limit] are returned, positional to [spans]. Spans fan out
     * with bounded parallelism; the `vocabularyVersion` is read ONCE up front so
     * the whole batch reflects a consistent snapshot.
     */
    suspend fun batchMatch(
        spans: List<SpanQuery>,
        maxParallelism: Int = 8,
    ): BatchMatchResult {
        val version = repository.vocabularyVersion()
        if (spans.isEmpty()) return BatchMatchResult(emptyList(), version)

        val gate = Semaphore(maxParallelism.coerceAtLeast(1))
        val results =
            coroutineScope {
                spans
                    .map { span -> async { gate.withPermit { matchSpan(span) } } }
                    .awaitAll()
            }
        return BatchMatchResult(results, version)
    }

    private suspend fun matchSpan(span: SpanQuery): BatchSpanResult {
        val limit = if (span.limit > 0) span.limit else 10
        val steps = cascadeFrom(emptyList(), AlgorithmType.TATRMAN.name)

        // Empty categories ⇒ deliberate global (cross-category) lookup; otherwise
        // match per explicit category (unknown ones contribute nothing — leak guard).
        val targets: List<String?> = span.categories.ifEmpty { listOf(null) }
        val perCategory = targets.map { cat -> matchCascade(span.query, cat, steps, limit) }

        val matchedAlgorithm = perCategory.firstOrNull { it.matches.isNotEmpty() }?.matchedAlgorithm
        val merged =
            perCategory
                .flatMap { it.matches }
                .sortedByDescending { it.score }
                .distinctBy { it.candidateId to it.category }
                .take(limit)
        return BatchSpanResult(merged, matchedAlgorithm)
    }

    private suspend fun runSingle(
        query: String,
        category: String?,
        algorithmType: AlgorithmType,
        limit: Int,
    ): List<FuzzyMatchResult> {
        val candidates = repository.getCandidates(category)
        return if (algorithmType == AlgorithmType.TATRMAN) {
            matchWithTokenBased(query, category, candidates, limit)
        } else {
            withContext(Dispatchers.Default) {
                matchWithStandardAlgorithm(query, category, algorithmType, candidates, limit)
            }
        }
    }

    private suspend fun matchWithTokenBased(
        query: String,
        category: String?,
        candidates: List<Candidate>,
        limit: Int,
    ): List<FuzzyMatchResult> {
        // Two query token axes: folded surface (handles diacritic-stripped input) and folded lemma
        // (handles inflection). Feed the lemmatiser raw (accented) tokens; fold its lemmas. With
        // NoopLemmatizer the lemma axis collapses onto the surface axis (harmless no-op).
        val rawQueryTokens = Candidate.tokenizeRaw(query)
        val querySurfaceTokens = rawQueryTokens.map { TextNormalizer.fold(it) }
        val lemmaMap = lemmatizer.lemmatize(rawQueryTokens)
        val queryLemmaTokens = rawQueryTokens.map { lemmaMap[it] ?: TextNormalizer.fold(it) }

        val matcher =
            TokenBasedMatcher(
                candidates = candidates,
                // Repository normalises the category key (case-insensitive lookup).
                tokenIndex = repository.getTokenIndex(category),
                distanceCache = repository.getDistanceCache(category),
                idfEnabled = idfEnabled,
            )
        val results =
            withContext(Dispatchers.Default) {
                matcher.match(querySurfaceTokens, queryLemmaTokens, limit)
            }

        return results.map { (candidate, score) ->
            candidate.toResult(score, category, method = "TATRMAN")
        }
    }

    private fun matchWithStandardAlgorithm(
        query: String,
        category: String?,
        algorithmType: AlgorithmType?,
        candidates: List<Candidate>,
        limit: Int,
    ): List<FuzzyMatchResult> {
        val algorithm = AlgorithmFactory.get(algorithmType ?: AlgorithmType.LEVENSHTEIN)
        val foldedQuery = TextNormalizer.fold(query)

        return candidates
            .map { candidate ->
                // Match on folded text so diacritic-only differences don't penalise the score,
                // but return the candidate's original value to the caller.
                val score = algorithm.similarity(foldedQuery, TextNormalizer.fold(candidate.value))
                candidate.toResult(score, category, method = (algorithmType ?: AlgorithmType.LEVENSHTEIN).name)
            }.sortedByDescending { it.score }
            .take(limit)
    }
}

/**
 * Maps a scored [Candidate] to a [FuzzyMatchResult], carrying its source tag +
 * lexicon target_ref (RS-15) and stamping S-4 provenance (producer=fuzzy,
 * method=the algorithm that scored it).
 */
private fun Candidate.toResult(
    score: Double,
    category: String?,
    method: String,
): FuzzyMatchResult =
    FuzzyMatchResult(
        candidateId = id,
        candidate = value,
        score = score,
        category = category ?: "unknown",
        source = source,
        targetRef = targetRef,
        provenance = Provenance(producer = "fuzzy", method = method, rawScore = score),
    )
