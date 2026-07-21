// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.loader.DeclaredVocabularyLoader
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.loader.SnapshotVocabularySource
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
    // RG-P2 (contracts §2, RS-15): how the owning category was sourced. MEMBER
    // (default) → `id` is a data PK; VOCABULARY → `targetRef` is the lexicon target.
    val source: SourceTag = SourceTag.MEMBER,
    val targetRef: String? = null,
) {
    /** Surface tokens ∪ lemma tokens — used to seed the candidate set for a query. */
    val allTokenSet: Set<String> get() = tokenSet + lemmaTokenSet

    /**
     * FZ-P1 T5 — the folded value, computed once at construction (the standard-algorithm cascade
     * folded [value] on every request before this). A body `val` ⇒ it is NOT part of the data
     * class's generated equals/hashCode/copy/componentN, so equality is unchanged.
     */
    val foldedValue: String = TextNormalizer.fold(value)

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

        /** A declared-vocabulary candidate (contracts §2): carries the lexicon [targetRef]. */
        fun vocabulary(
            id: String,
            value: String,
            targetRef: String,
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
                source = SourceTag.VOCABULARY,
                targetRef = targetRef,
            )
        }

        /** Builds a candidate with explicit (folded) lemma tokens — used by [StringRepository.lemmatiseCandidates].
         *  Preserves the source dimension (MEMBER/VOCABULARY + targetRef) of the original. */
        fun withLemmas(
            id: String,
            value: String,
            surfaceTokens: List<String>,
            lemmaTokens: List<String>,
            source: SourceTag = SourceTag.MEMBER,
            targetRef: String? = null,
        ): Candidate =
            Candidate(
                id = id,
                value = value,
                tokens = surfaceTokens,
                tokenSet = surfaceTokens.toSet(),
                lemmaTokens = lemmaTokens,
                lemmaTokenSet = lemmaTokens.toSet(),
                source = source,
                targetRef = targetRef,
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

/** Per-category discovery + staleness for `GetStatus` (contracts §2). */
data class CategoryStatusInfo(
    val category: String,
    val source: SourceTag,
    val size: Int,
    val loadedAtEpochMs: Long,
)

/** B-T4 loader-report entry (e.g. `RG-FUZ-001` PK-skipped declared column). */
data class LoaderWarningInfo(
    val code: String,
    val category: String,
    val message: String,
)

class StringRepository(
    private val config: AppConfig,
    private val loaderSource: LoaderSource,
    private val telemetry: FuzzyTelemetry? = null,
    private val lemmatizer: Lemmatizer = NoopLemmatizer,
    // RG-P2.S2: declared vocabulary (lexicon terms + valueLabels) — VOCABULARY
    // categories merged alongside the member data. Null = member data only.
    private val snapshotSource: SnapshotVocabularySource? = null,
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

    // FZ-P1 T4 — the flattened cross-category candidate list, precomputed at refresh instead of
    // re-flattening `cache.values` on every `getCandidates(null)` call. Same contents/order as the
    // per-request flatten (both iterate `cache.values`), so cross-category results are unchanged.
    @Volatile
    private var allCandidates: List<Candidate> = emptyList()

    // RG-P2.S2: the vocabulary version echoed on every response + in GetStatus
    // (S-1). Content hash of {category → size} + the member load stamp; the
    // declared-vocabulary snapshot hash folds into this in T5.
    @Volatile
    private var version: String = ""

    @Volatile
    private var loadedAtMs: Long = 0L

    // Declared vocabulary is the SECOND clock: reloaded (+ lemmatised) only when
    // its snapshot hash changes, then merged into the cache on every member
    // refresh. Stored pre-lemmatised so member refreshes don't re-lemmatise it.
    @Volatile
    private var declaredCache: Map<String, List<Candidate>> = emptyMap()

    @Volatile
    private var declaredHash: String = ""

    init {
        startRefreshLoop()
    }

    private fun startRefreshLoop() {
        // refreshIntervalSeconds <= 0 ⇒ manual mode: no background loop, refresh
        // only via forceRefresh (deterministic for tests; also a valid "reload
        // on /refresh only" deployment posture — Q-8 open-vs-harvest line).
        if (config.refreshIntervalSeconds <= 0) return
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
        val memberCache =
            loaded.entries.associate { (category, raw) ->
                category.lowercase() to lemmatiseCandidates(raw)
            }

        // Second clock: reload + lemmatise declared vocabulary ONLY when its
        // snapshot hash changes (T5), then merge it into the member cache.
        refreshDeclaredIfChanged()
        val nextCache = LinkedHashMap<String, List<Candidate>>(memberCache)
        declaredCache.forEach { (key, vocab) ->
            nextCache.merge(key, vocab) { member, declared -> member + declared }
        }

        cache.clear()
        cache.putAll(nextCache)
        loadedAtMs = System.currentTimeMillis()
        version = computeVersion(nextCache, declaredHash, loadedAtMs)
        isCatalogReady.set(true)
        rebuildIndices()
    }

    private suspend fun refreshDeclaredIfChanged() {
        val source = snapshotSource ?: return
        val hash = source.hash()
        if (hash == declaredHash && declaredCache.isNotEmpty()) return
        val raw = DeclaredVocabularyLoader.toCategories(source.fetch())
        declaredCache = raw.mapValues { lemmatiseCandidates(it.value) }
        declaredHash = hash
        logger.info("Declared vocabulary loaded (hash={}, categories={})", hash, declaredCache.size)
    }

    /** The vocabulary version (S-1): content signature + load stamp. */
    fun vocabularyVersion(): String = version

    /** Per-category discovery + staleness for `GetStatus` (contracts §2). */
    fun categoryStatuses(): List<CategoryStatusInfo> =
        cache
            .map { (category, candidates) ->
                CategoryStatusInfo(
                    category = category,
                    source = candidates.firstOrNull()?.source ?: SourceTag.MEMBER,
                    size = candidates.size,
                    loadedAtEpochMs = loadedAtMs,
                )
            }.sortedBy { it.category }

    /** B-T4 loader report: PK-skipped declared columns etc. (`RG-FUZ-001`). Populated in S2.T7. */
    fun loaderWarnings(): List<LoaderWarningInfo> = loaderSource.warnings()

    private fun computeVersion(
        content: Map<String, List<Candidate>>,
        vocabHash: String,
        stamp: Long,
    ): String {
        // Order-independent content signature: sorted (category → size) pairs +
        // the declared-vocabulary snapshot hash + the member load stamp (S-1).
        val sig =
            content.entries
                .sortedBy { it.key }
                .joinToString("|") { "${it.key}:${it.value.size}" }
                .hashCode()
        val vocab = if (vocabHash.isBlank()) "-" else vocabHash
        return "member:%08x/vocab:%s@%d".format(sig, vocab, stamp)
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
            Candidate.withLemmas(
                c.id,
                c.value,
                surfaceTokens = c.tokens,
                lemmaTokens = lemmaTokens,
                source = c.source,
                targetRef = c.targetRef,
            )
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

        val flattened = cache.values.flatten()
        allCandidates = flattened
        logger.info("Rebuilding global token index for ${flattened.size} candidates...")
        globalTokenIndex = TokenIndex(flattened)
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
            // FZ-P1 T4 — precomputed at refresh (see [allCandidates]); no per-request flatten.
            allCandidates
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
