// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.telemetry.FuzzyTelemetry
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A matchable string. [tokens] are the folded-surface tokens (always present). [lemmaTokens] are
 * the folded *lemmas* of those tokens — populated by [StringRepository] when `infra/nlp`
 * lemmatisation is enabled, otherwise equal to [tokens] (so the lemma axis is a harmless no-op).
 * The matcher scores a query against both axes and keeps the better, so lemmatisation never
 * regresses a surface match (e.g. a diacritic-stripped exact phrase) while still letting inflected
 * queries land an exact lemma match.
 */
data class Candidate(
    val id: String,
    val value: String,
    val tokens: List<String> = emptyList(),
    val tokenSet: Set<String> = emptySet(),
    val lemmaTokens: List<String> = tokens,
    val lemmaTokenSet: Set<String> = tokenSet,
) {
    /** Surface tokens ∪ lemma tokens — used to seed the candidate set for a query. */
    val allTokenSet: Set<String> get() = tokenSet + lemmaTokenSet

    companion object {
        val WHITESPACE_REGEX = Regex("\\s+")

        fun fromValues(
            id: String,
            value: String,
        ): Candidate {
            val tokens = tokenize(value)
            val set = tokens.toSet()
            return Candidate(
                id = id,
                value = value,
                tokens = tokens,
                tokenSet = set,
                lemmaTokens = tokens,
                lemmaTokenSet = set,
            )
        }

        /** Builds a candidate with explicit (folded) lemma tokens — used by [StringRepository.lemmatiseCandidates]. */
        fun withLemmas(
            id: String,
            value: String,
            surfaceTokens: List<String>,
            lemmaTokens: List<String>,
        ): Candidate =
            Candidate(
                id = id,
                value = value,
                tokens = surfaceTokens,
                tokenSet = surfaceTokens.toSet(),
                lemmaTokens = lemmaTokens,
                lemmaTokenSet = lemmaTokens.toSet(),
            )

        /** Tokens used for matching: lower-cased, NFD-folded, whitespace-split. */
        fun tokenize(input: String): List<String> = tokenizeRaw(input).map { TextNormalizer.fold(it) }

        /** Lower-cased, whitespace-split — but **not** NFD-folded. Used as the input to lemmatisation
         *  so the lemmatiser (MorphoDiTa via `infra/nlp`) sees properly-accented Czech; the lemmas it
         *  returns are folded afterwards. With no lemmatiser this collapses back to [tokenize]. */
        fun tokenizeRaw(input: String): List<String> =
            input
                .lowercase()
                .split(WHITESPACE_REGEX)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
    }
}

class StringRepository(
    private val config: AppConfig,
    private val loaderSource: LoaderSource,
    private val telemetry: FuzzyTelemetry? = null,
    private val lemmatizer: Lemmatizer = NoopLemmatizer,
) {
    private val logger = LoggerFactory.getLogger(StringRepository::class.java)

    private companion object {
        /** Returned for an explicit category that has no index, so the matcher yields no candidates. */
        val EMPTY_TOKEN_INDEX = TokenIndex(emptyList())
    }

    private val cache = ConcurrentHashMap<String, List<Candidate>>()
    private val categoryTokenIndices = ConcurrentHashMap<String, TokenIndex>()
    private val categoryDistanceCaches = ConcurrentHashMap<String, DistanceCache>()
    private val isRunning = AtomicBoolean(false)
    private val isCatalogReady = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var globalTokenIndex: TokenIndex = TokenIndex(emptyList())

    @Volatile
    private var globalDistanceCache: DistanceCache = DistanceCache()

    init {
        startRefreshLoop()
    }

    private fun startRefreshLoop() {
        if (isRunning.getAndSet(true)) return

        scope.launch {
            while (isActive) {
                try {
                    refreshCache()
                } catch (e: Exception) {
                    logger.error("Failed to refresh cache", e)
                }
                delay(config.refreshIntervalSeconds * 1000)
            }
        }
    }

    private suspend fun refreshCache() {
        logger.info("Starting cache refresh...")
        val loaded = loaderSource.loadNextCache()
        if (loaded == null) {
            logger.warn("Loader signalled failure; preserving previous cache")
            return
        }
        // Category keys are matched case-insensitively. The query side
        // (Routes./match, FuzzyMatcher.match, getTokenIndex) lowercases the
        // requested category, so the stored key MUST be lowercase too. DB
        // identifiers arrive upper-cased from the loader (e.g.
        // "db.dbo.QSTRED_DF.KOD_STR"); without this the per-column index was
        // never hit and lookups silently fell back to the global index,
        // returning *other columns'* values (a KOD_STR query served NAZEV_STR).
        val nextCache =
            loaded.entries.associate { (category, raw) ->
                category.lowercase() to lemmatiseCandidates(raw)
            }
        cache.clear()
        cache.putAll(nextCache)
        isCatalogReady.set(true)
        rebuildIndices()
    }

    /**
     * Operator-triggered immediate reload (via `POST /refresh`), bypassing the background interval
     * wait. Reuses [refreshCache]; on loader failure it preserves the previous cache (and throws
     * nothing) just like the scheduled path.
     */
    suspend fun forceRefresh() = refreshCache()

    /**
     * Populates each candidate's [Candidate.lemmaTokens] (the folded lemmas of its surface tokens)
     * so inflected query forms can land an exact lemma match — without disturbing the surface
     * tokens, so a diacritic-stripped exact phrase still scores as a surface match. With
     * [NoopLemmatizer] the candidates already have `lemmaTokens == tokens`, so this is a no-op.
     * Czech lemmatisation is context-sensitive; we batch unrelated tokens per category, which is
     * good enough for short entity-name tokens — a known v1 limitation. We feed the lemmatiser the
     * raw (lower-cased, accented) tokens so MorphoDiTa gets proper Czech, then fold its lemmas.
     */
    private suspend fun lemmatiseCandidates(candidates: List<Candidate>): List<Candidate> {
        if (lemmatizer is NoopLemmatizer || candidates.isEmpty()) return candidates
        val rawByCandidate = candidates.associateWith { Candidate.tokenizeRaw(it.value) }
        val uniqueRaw = rawByCandidate.values.flatMapTo(HashSet()) { it }
        if (uniqueRaw.isEmpty()) return candidates
        val lemmaMap = lemmatizer.lemmatize(uniqueRaw)
        return candidates.map { c ->
            val lemmaTokens = (rawByCandidate[c] ?: emptyList()).map { lemmaMap[it] ?: TextNormalizer.fold(it) }
            Candidate.withLemmas(c.id, c.value, surfaceTokens = c.tokens, lemmaTokens = lemmaTokens)
        }
    }

    private fun rebuildIndices() {
        logger.info("Rebuilding indices for all categories...")

        cache.forEach { (category, candidates) ->
            logger.debug("Building token index for category '$category' with ${candidates.size} candidates...")
            categoryTokenIndices[category] = TokenIndex(candidates)
            // We can optionally reuse distance cache if we want to keep it across refreshes,
            // but the original behavior was to reset it.
            categoryDistanceCaches[category] = DistanceCache()
        }

        // Cleanup categories no longer in cache
        categoryTokenIndices.keys.removeIf { !cache.containsKey(it) }
        categoryDistanceCaches.keys.removeIf { !cache.containsKey(it) }

        val allCandidates = cache.values.flatten()
        logger.info("Rebuilding global token index for ${allCandidates.size} candidates...")
        globalTokenIndex = TokenIndex(allCandidates)
        globalDistanceCache = DistanceCache()
        logger.info(
            "Indices rebuilt for ${cache.size} categories and global index with ${globalTokenIndex.getAllCandidateIds().size} candidates",
        )
    }

    fun isCatalogReady(): Boolean = isCatalogReady.get()

    // Category keys are normalised to lowercase on both write (refreshCache)
    // and read (here) so lookups are case-insensitive. DB-identifier categories
    // arrive upper-cased (e.g. "db.dbo.QSTRED_DF.KOD_STR"); without this the
    // per-column index was missed and lookups leaked other columns' values.
    fun getCandidates(category: String?): List<Candidate> =
        if (category != null) {
            cache[category.lowercase()] ?: emptyList()
        } else {
            cache.values.flatten()
        }

    fun getTokenIndex(category: String? = null): TokenIndex =
        if (category != null) {
            // An explicit-but-unknown category must NOT silently fall back to
            // the global index — that returns every other column's candidates
            // and is exactly how a case-mismatched key served the wrong column.
            // Mirror getCandidates' empty-on-miss contract. Global is only for
            // the deliberate null (cross-category) lookup.
            categoryTokenIndices[category.lowercase()] ?: EMPTY_TOKEN_INDEX
        } else {
            globalTokenIndex
        }

    fun getDistanceCache(category: String? = null): DistanceCache =
        if (category != null) {
            categoryDistanceCaches[category.lowercase()] ?: globalDistanceCache
        } else {
            globalDistanceCache
        }

    fun close() {
        scope.cancel()
    }
}
