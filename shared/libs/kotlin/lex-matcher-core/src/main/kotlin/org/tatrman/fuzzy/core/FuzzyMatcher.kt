// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

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

/**
 * RV-P1.4 T5 — a category-scoped lookup: the request shape the resolver's **lookup rung** calls
 * (RV-33 — "deterministic, anchored, category-scoped; always eligible first"), and the contract
 * P2.3's lookup rounds are written against.
 *
 * Distinct from [SpanQuery], which is a *span* of a parsed question inside a batch. This is one
 * term, deliberately scoped, asked on its own — the rung already knows what it is looking for and
 * wants a bounded deterministic answer, not a cascade over the estate.
 */
data class LookupQuery(
    val term: String,
    /**
     * Category keys to search — the same keys [MatchRepository.getCandidates] uses, which for the
     * compiled lexicon means target refs (`md.measure.net`, `op:trend`). **Empty = every category**:
     * a deliberate cross-category lookup, the shape a rung uses when it does not yet know the
     * target. An explicit-but-unknown category contributes nothing (the per-slot leak guard) and is
     * reported back in [LookupResult.unknownCategories] rather than silently yielding zero hits.
     */
    val categories: List<String> = emptyList(),
    /**
     * Restrict to these target classes (RV-38). Empty = no class filter. Scoping by class rather
     * than by ref is what lets a rung ask "which operator is this?" without first enumerating the
     * estate's operators.
     */
    val targetClasses: Set<TargetClass> = emptySet(),
    /**
     * Replaces the **authored** method for this call — the rung's strict-first, then widen pass.
     * Applies only to rows that carry an authored method; see [MethodDispatcher.dispatch].
     */
    val methodOverride: MatchMethod? = null,
    val maxCandidates: Int = 10,
)

/** One lookup's answer: the surviving candidates plus what was actually searched. */
data class LookupResult(
    val candidates: List<FuzzyMatchResult>,
    /**
     * Requested categories that do not exist in the loaded vocabulary. The leak guard already
     * returns nothing for these; naming them turns "no candidates" into a diagnosable answer
     * instead of an ambiguous one — a stale ref and a genuine miss look identical otherwise.
     */
    val unknownCategories: List<String> = emptyList(),
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
    private val repository: MatchRepository,
    private val lemmatizer: Lemmatizer = NoopLemmatizer,
    // GH #69 — IDF token weighting for the TATRMAN (token-based) path. Defaulted
    // so existing call sites / tests are unaffected; wired from config in Application.
    private val idfEnabled: Boolean = true,
    // FZ-P2 — retrieval path. Defaulted LEGACY so existing call sites / tests / goldens are
    // byte-identical; Application wires it from `fuzzy.token-based.retrieval`. The retriever is the
    // seam FZO plugs OpenSearch into; the in-memory default resolves against the interned vocabulary.
    private val retrievalMode: RetrievalMode = RetrievalMode.LEGACY,
    private val retriever: CandidateRetriever = IndexFirstRetriever(repository::getVocabulary),
    // RV-P1.4 T4 — honours the authored match method (RV-32) on whatever the cascade produced.
    // A no-op for candidates with no authored method, so the pre-RV service is unaffected.
    private val methodDispatcher: MethodDispatcher = MethodDispatcher(),
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

    /**
     * RV-P1.4 T5 — one term, deliberately scoped: the resolver's lookup rung (RV-33).
     *
     * Deterministic by construction — a single TATRMAN pass, no cascade. The rung's contract is
     * "anchored and category-scoped, always eligible first", so trying progressively looser
     * algorithms would be answering a question it did not ask; widening is the caller's job, via
     * [LookupQuery.methodOverride] and a second round it chose to run.
     */
    suspend fun lookup(query: LookupQuery): LookupResult {
        val limit = if (query.maxCandidates > 0) query.maxCandidates else 10
        val unknown =
            repository.knownCategories()?.let { known ->
                query.categories.filter { it.lowercase() !in known }
            } ?: emptyList()

        // Empty ⇒ the deliberate cross-category lookup. An explicit-but-unknown category is left in
        // the list: it contributes nothing (the leak guard) and is reported separately.
        val targets: List<String?> = query.categories.ifEmpty { listOf(null) }
        val scored =
            targets.flatMap { category ->
                runSingle(query.term, category, AlgorithmType.TATRMAN, limit, query.methodOverride)
            }

        val filtered =
            if (query.targetClasses.isEmpty()) {
                scored
            } else {
                // Member values carry no target class, so a class-scoped lookup excludes them —
                // which is the point: "which operator is this?" must not be answered with a row of
                // data that happens to read alike.
                scored.filter { it.targetClass in query.targetClasses }
            }

        val merged =
            filtered
                .sortedByDescending { it.score }
                .distinctBy { it.candidateId to it.category }
        // Re-asked over the union, BEFORE the limit: per-category margins cannot see a rival in a
        // sibling category, and dropping rivals first would manufacture uniqueness.
        return LookupResult(
            candidates = methodDispatcher.recomputeMargins(merged, query.methodOverride).take(limit),
            unknownCategories = unknown,
        )
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
        // Same reason as in [lookup]: a span asked about several categories, so the RV-32 margin has
        // to be re-asked over their union. Before `take`, so a rival cannot be dropped into silence.
        return BatchSpanResult(methodDispatcher.recomputeMargins(merged).take(limit), matchedAlgorithm)
    }

    private suspend fun runSingle(
        query: String,
        category: String?,
        algorithmType: AlgorithmType,
        limit: Int,
        methodOverride: MatchMethod? = null,
    ): List<FuzzyMatchResult> {
        val candidates = repository.getCandidates(category)
        val scored =
            if (algorithmType == AlgorithmType.TATRMAN) {
                matchWithTokenBased(query, category, candidates, limit)
            } else {
                withContext(Dispatchers.Default) {
                    matchWithStandardAlgorithm(query, category, algorithmType, candidates, limit)
                }
            }
        // RV-P1.4 T4 — the authored method gates here, at the single point every entry point funnels
        // through (match / matchCascade / batchMatch), so no caller can route around it. Inside the
        // cascade rather than after it on purpose: a candidate the author's method rejects must not
        // be allowed to satisfy a cascade step's min-score and stop the fallback.
        val dispatched = methodDispatcher.dispatch(query, scored, methodOverride)
        return consultOverlay(query, listOfNotNull(category), dispatched, limit)
    }

    /**
     * RV-P1.4 T6 — the third layer, consulted after the other two have spoken.
     *
     * Additions merge in and are ranked with everything else; suppressed targets are **flagged, not
     * removed** ([OverlayVerdict.suppressedTargets]). With the default [NoopOverlayStore] the
     * verdict is empty and the input list is returned as-is, so a deployment with no learning
     * history is byte-identical to the pre-overlay service.
     */
    private suspend fun consultOverlay(
        term: String,
        categories: List<String>,
        results: List<FuzzyMatchResult>,
        limit: Int,
    ): List<FuzzyMatchResult> {
        val verdict = repository.overlay().consult(OverlayRequest(term, categories, results))
        if (verdict.isEmpty) return results

        val flagged =
            results.map { if (it.targetRef in verdict.suppressedTargets) it.copy(autoBindable = false) else it }
        return (flagged + verdict.additions).sortedByDescending { it.score }.take(limit)
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

        // Repository normalises the category key (case-insensitive lookup).
        val tokenIndex = repository.getTokenIndex(category)
        val results =
            withContext(Dispatchers.Default) {
                when (retrievalMode) {
                    RetrievalMode.LEGACY -> {
                        val matcher =
                            TokenBasedMatcher(
                                candidates = candidates,
                                tokenIndex = tokenIndex,
                                distanceCache = repository.getDistanceCache(category),
                                idfEnabled = idfEnabled,
                            )
                        matcher.match(querySurfaceTokens, queryLemmaTokens, limit)
                    }
                    RetrievalMode.INDEX_FIRST -> {
                        // Retrieve the topN candidates worth scoring against the interned vocabulary,
                        // then exact-rescore them with the unchanged scorer. The retriever resolves
                        // each query token once against the vocabulary and returns the best-first
                        // candidates from a single snapshot (no ordinal escapes the retriever, so a
                        // concurrent refresh cannot mis-map one). The rescore uses a throwaway
                        // DistanceCache, so the shared category cache stays off this path.
                        //
                        // topN headroom: index-first legitimately reaches candidates legacy never
                        // seeds (a fuzzy-resolved token pulls in rows with no exact overlap), so the
                        // rescore set must be wide enough that legacy's true top-k are not crowded
                        // out before the exact re-rank. 500 keeps the rescore cheap (µs-per-candidate)
                        // while giving the parity-or-better gate ample margin.
                        val topN = maxOf(500, limit * 4)
                        val retrieved = retriever.retrieve(querySurfaceTokens, queryLemmaTokens, category, topN)
                        val matcher =
                            TokenBasedMatcher(
                                candidates = retrieved,
                                tokenIndex = tokenIndex,
                                distanceCache = DistanceCache(),
                                idfEnabled = idfEnabled,
                            )
                        matcher.rescore(querySurfaceTokens, queryLemmaTokens, retrieved, limit)
                    }
                }
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
                // but return the candidate's original value to the caller. FZ-P1 T5 — use the
                // candidate's precomputed fold instead of folding per request.
                val score = algorithm.similarity(foldedQuery, candidate.foldedValue)
                candidate.toResult(score, category, method = (algorithmType ?: AlgorithmType.LEVENSHTEIN).name)
            }.sortedByDescending { it.score }
            .take(limit)
    }
}

/**
 * Maps a scored [Candidate] to a [FuzzyMatchResult], carrying its source tag +
 * lexicon target_ref (RS-15) and stamping S-4 provenance (producer=fuzzy,
 * method=the algorithm that scored it).
 *
 * Note the two `method`s are different axes and both travel: [FuzzyMatchResult.matchMethod] is the
 * AUTHORED method from the lexicon (RV-32), while [Provenance.method] is the algorithm that
 * produced this score. Conflating them would make "the author asked for EXACT" indistinguishable
 * from "the TATRMAN cascade happened to win".
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
        matchMethod = matchMethod,
        targetClass = targetClass,
    )
