package org.tatrman.kantheon.ariadne.search.keyword

import org.tatrman.ariadne.v1.SearchRequest
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.ariadne.registry.RegistrySnapshot
import org.tatrman.kantheon.ariadne.search.RebuildOutcome
import org.tatrman.kantheon.ariadne.search.SearchAlgorithm
import org.tatrman.kantheon.ariadne.search.SearchHit
import org.tatrman.kantheon.ariadne.search.SearchIndex
import org.tatrman.kantheon.ariadne.search.searchableObjects

/**
 * Whole-word match against `search.keywords.<language>`, with `aliases` and
 * `examples` (language-agnostic) as secondary lower-weighted sources.
 *
 * Score per spec §6.2:
 *   score = sum_over_matched_tokens(1.0 / query_token_count × position_factor),
 *   capped at 1.0
 * where position_factor = 1.0 for `keyword`, 0.5 for `alias`, 0.4 for `example`.
 *
 * The matched_field on the returned hit reflects the highest-scoring source
 * field on that owner; ties are broken in keyword > alias > example order
 * (the precedence in which sources are scored).
 */
class KeywordAlgorithm(
    stopWords: StopWords,
) : SearchAlgorithm {
    override val name: String = "keyword"

    private val tokenizer = Tokenizer(stopWords)

    override fun rebuild(
        snapshot: RegistrySnapshot,
        language: String,
    ): RebuildOutcome {
        val postings = HashMap<String, MutableList<Posting>>()
        for (obj in snapshot.searchableObjects()) {
            obj.search.keywords.byLanguage[language]?.forEach { kw ->
                tokenizer.tokenize(kw, language).forEach { token ->
                    postings.getOrPut(token) { mutableListOf() } +=
                        Posting(obj.qname, obj.kind, "keyword", kw)
                }
            }
            obj.search.aliases.forEach { alias ->
                tokenizer.tokenize(alias, language).forEach { token ->
                    postings.getOrPut(token) { mutableListOf() } +=
                        Posting(obj.qname, obj.kind, "alias", alias)
                }
            }
            obj.search.examples.forEach { example ->
                tokenizer.tokenize(example, language).forEach { token ->
                    postings.getOrPut(token) { mutableListOf() } +=
                        Posting(obj.qname, obj.kind, "example", example)
                }
            }
        }
        return RebuildOutcome(KeywordIndex(postings))
    }

    override fun search(
        request: SearchRequest,
        index: SearchIndex,
    ): List<SearchHit> {
        if (index !is KeywordIndex) return emptyList()
        val language = request.language.ifEmpty { "cs" }
        val queryTokens = tokenizer.tokenize(request.query, language)
        if (queryTokens.isEmpty()) return emptyList()

        // owner → (sourceField → score). The score per (owner, source) accumulates
        // contributions from each matched token at the source's position-factor.
        data class Accum(
            val score: Float,
            val sample: Posting,
        )
        val perOwnerSource = HashMap<QualifiedName, MutableMap<String, Accum>>()

        val tokenWeight = 1.0f / queryTokens.size
        for (token in queryTokens.distinct()) {
            val postings = index.postings[token] ?: continue
            for (p in postings) {
                val factor = positionFactor(p.sourceField)
                val ownerMap = perOwnerSource.getOrPut(p.qname) { mutableMapOf() }
                val current = ownerMap[p.sourceField]
                val nextScore = ((current?.score ?: 0f) + tokenWeight * factor).coerceAtMost(1f)
                ownerMap[p.sourceField] = Accum(nextScore, current?.sample ?: p)
            }
        }

        return perOwnerSource.entries.map { (qname, sourceMap) ->
            val (sourceField, accum) =
                sourceMap.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Accum>> { it.value.score }
                            .thenBy { fieldOrder(it.key) },
                    ).first()
            SearchHit(
                ownerQname = qname,
                ownerKind = accum.sample.kind,
                score = accum.score,
                matchedField = sourceField,
                matchedValue = accum.sample.originalValue,
                snippet = accum.sample.originalValue,
                algorithm = name,
            )
        }
    }

    private fun positionFactor(sourceField: String): Float =
        when (sourceField) {
            "keyword" -> 1.0f
            "alias" -> 0.5f
            "example" -> 0.4f
            else -> 0.0f
        }

    private fun fieldOrder(sourceField: String): Int =
        when (sourceField) {
            "keyword" -> 0
            "alias" -> 1
            "example" -> 2
            else -> 3
        }
}

internal data class Posting(
    val qname: QualifiedName,
    val kind: String,
    val sourceField: String, // "keyword" | "alias" | "example"
    val originalValue: String,
)

class KeywordIndex internal constructor(
    internal val postings: Map<String, List<Posting>>,
) : SearchIndex
