package org.tatrman.kantheon.ariadne.search

import org.tatrman.kantheon.ariadne.registry.RegistrySnapshot
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the live per-algorithm, per-language [SearchIndex] map under an
 * `AtomicReference` for lock-free reads. [rebuild] is invoked from the
 * [org.tatrman.kantheon.ariadne.registry.MetadataRegistry] listener whenever a new
 * snapshot is published.
 *
 * Failure semantics: if a single algorithm-language rebuild throws, the
 * holder logs the failure and **retains the previous index** for that
 * algorithm-language pair — last-known-good. Other algorithm-language
 * pairs proceed independently. Compile-time issues that don't throw
 * (e.g. a single bad pattern) flow through [RebuildOutcome.compileErrors]
 * and are aggregated in [stats].
 */
class SearchIndexHolder(
    private val registry: SearchAlgorithmRegistry,
    private val supportedLanguages: List<String>,
) {
    private val log = LoggerFactory.getLogger(SearchIndexHolder::class.java)

    /** algorithmName → languageCode → index. */
    private val indexes = AtomicReference<Map<String, Map<String, SearchIndex>>>(emptyMap())

    /** Aggregated CompileError list from the latest rebuild — exposed via [stats]. */
    private val lastErrors = AtomicReference<List<CompileError>>(emptyList())

    /** Count of objects that contribute at least one search hint, from the latest rebuild. */
    private val lastObjectCount = AtomicReference(0)

    fun rebuild(snapshot: RegistrySnapshot) {
        val nextIndexes = mutableMapOf<String, MutableMap<String, SearchIndex>>()
        val nextErrors = mutableListOf<CompileError>()
        val previous = indexes.get()
        for (algo in registry.all()) {
            val perLang = mutableMapOf<String, SearchIndex>()
            for (lang in supportedLanguages) {
                try {
                    val outcome = algo.rebuild(snapshot, lang)
                    perLang[lang] = outcome.index
                    nextErrors += outcome.compileErrors
                } catch (ex: Exception) {
                    val carried = previous[algo.name]?.get(lang)
                    log.warn(
                        "Search index rebuild failed for algorithm={} language={}; keeping previous index (last-known-good={})",
                        algo.name,
                        lang,
                        carried != null,
                        ex,
                    )
                    if (carried != null) perLang[lang] = carried
                }
            }
            nextIndexes[algo.name] = perLang
        }
        indexes.set(nextIndexes.mapValues { (_, m) -> m.toMap() })
        lastErrors.set(
            nextErrors.distinctBy {
                "${it.objectQname.schemaCode}.${it.objectQname.namespace}.${it.objectQname.name}|${it.field}"
            },
        )
        lastObjectCount.set(snapshot.searchableObjects().count { !it.search.isEmpty })
    }

    fun get(
        algorithmName: String,
        language: String,
    ): SearchIndex? = indexes.get()[algorithmName]?.get(language)

    fun stats(): IndexStats =
        IndexStats(
            objectCount = lastObjectCount.get(),
            compileErrors = lastErrors.get(),
        )
}

data class IndexStats(
    val objectCount: Int,
    val compileErrors: List<CompileError>,
)
