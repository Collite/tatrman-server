package org.tatrman.kantheon.ariadne.search

import org.tatrman.ariadne.v1.SearchRequest
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.ariadne.registry.RegistrySnapshot

/**
 * Strategy-pattern interface for the metadata service's search algorithms.
 *
 * Each implementation produces its own `SearchIndex` type (the marker is the
 * sealed [SearchIndex] interface) at rebuild time and consumes it at search
 * time. The dispatch lives in the gRPC layer; algorithms don't know about
 * each other except [AllAlgorithm], which delegates to the registry.
 */
interface SearchAlgorithm {
    val name: String

    /**
     * Build a per-language index from the given snapshot. Per-language: the
     * keyword and substring algorithms hold a separate index per supported
     * language so the request's `language` parameter selects without
     * reshuffling content. Algorithms that are language-agnostic (regex)
     * return the same content for every language.
     */
    fun rebuild(
        snapshot: RegistrySnapshot,
        language: String,
    ): RebuildOutcome

    /**
     * Run a search against the index built for the request's language.
     * Pure function — no shared state mutation.
     */
    fun search(
        request: SearchRequest,
        index: SearchIndex,
    ): List<SearchHit>
}

/** Marker interface — each algorithm provides its own subtype in its own package. */
interface SearchIndex {
    /** Empty placeholder used while an algorithm is awaiting a snapshot. */
    data object Empty : SearchIndex
}

/** Per-rebuild diagnostics. The list is shipped onward to GetStatus. */
data class RebuildOutcome(
    val index: SearchIndex,
    val compileErrors: List<CompileError> = emptyList(),
)

/**
 * Authoring-time issue surfaced by an algorithm during rebuild — typically
 * a pattern that failed to compile. Translates 1:1 to the proto
 * [org.tatrman.ariadne.v1.CompileError] in [GetStatusResponse].
 */
data class CompileError(
    val objectQname: QualifiedName,
    val kind: String,
    val field: String,
    val message: String,
)

/**
 * Algorithm-internal hit. Uniform across algorithms. The gRPC layer
 * converts a list of hits into proto `SearchResult` messages, applies
 * threshold + paging, and sets `algorithm_used` on the response.
 *
 * `patternIndex` is only meaningful when `matchedField == "pattern"`;
 * default 0 collides with a valid index 0 on the wire, so consumers
 * must check `matchedField` before reading the field.
 */
data class SearchHit(
    val ownerQname: QualifiedName,
    val ownerKind: String,
    val score: Float,
    val matchedField: String,
    val matchedValue: String,
    val snippet: String,
    val algorithm: String,
    val patternIndex: Int = 0,
    val extractedParameters: Map<String, String> = emptyMap(),
)

class SearchAlgorithmRegistry(
    private val algorithms: Map<String, SearchAlgorithm>,
) {
    val supportedNames: List<String> get() = algorithms.keys.toList()

    fun get(name: String): SearchAlgorithm? = algorithms[name]

    fun all(): Collection<SearchAlgorithm> = algorithms.values
}
