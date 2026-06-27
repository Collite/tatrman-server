package org.tatrman.kantheon.ariadne.search.substring

import org.tatrman.ariadne.v1.SearchRequest
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.ariadne.registry.RegistrySnapshot
import org.tatrman.kantheon.ariadne.search.RebuildOutcome
import org.tatrman.kantheon.ariadne.search.SearchAlgorithm
import org.tatrman.kantheon.ariadne.search.SearchHit
import org.tatrman.kantheon.ariadne.search.SearchIndex
import org.tatrman.kantheon.ariadne.search.searchableObjects

/**
 * Case-insensitive substring matcher. Builds a flat list of
 * `(owner, source-field, content)` tuples at rebuild and scans them per
 * search call. Per-language: `display_label.<lang>` and
 * `search.descriptions.<lang>` indexed only when the requested language
 * matches; other source fields (name, description, aliases, examples) are
 * language-agnostic and reused across all per-language indexes.
 *
 * Score rubric (spec §6.1, baseline values — tune via OTel `top_score`
 * + `top_matched_field` once real traffic exists):
 *
 *  | Source field                       | Base score |
 *  |------------------------------------|-----------:|
 *  | name (qname.name)                  |        0.3 |
 *  | display_label.<lang>               |        0.4 |
 *  | description                        |        0.5 |
 *  | search.descriptions.<lang>         |        0.5 |
 *  | search.aliases                     |        0.6 |
 *  | search.examples                    |        0.7 |
 *
 * One hit per owner per call — when multiple fields on the same object
 * match, the highest-scoring one wins.
 */
class SubstringAlgorithm : SearchAlgorithm {
    override val name: String = "substring"

    override fun rebuild(
        snapshot: RegistrySnapshot,
        language: String,
    ): RebuildOutcome {
        val entries = mutableListOf<Entry>()
        for (obj in snapshot.searchableObjects()) {
            // Language-agnostic fields (indexed once per language but always
            // hold the same content — keeps the search loop simple).
            entries +=
                Entry(
                    qname = obj.qname,
                    kind = obj.kind,
                    matchedField = "name",
                    matchedValue = obj.qname.name,
                    content = obj.qname.name.lowercase(),
                    baseScore = NAME_SCORE,
                )
            if (obj.description.isNotEmpty()) {
                entries +=
                    Entry(
                        qname = obj.qname,
                        kind = obj.kind,
                        matchedField = "description",
                        matchedValue = obj.description,
                        content = obj.description.lowercase(),
                        baseScore = DESCRIPTION_SCORE,
                    )
            }
            // Localised display label — only the requested language for this index.
            obj.displayLabel.byLanguage[language]?.let { label ->
                if (label.isNotEmpty()) {
                    entries +=
                        Entry(
                            qname = obj.qname,
                            kind = obj.kind,
                            matchedField = "display_label",
                            matchedValue = label,
                            content = label.lowercase(),
                            baseScore = DISPLAY_LABEL_SCORE,
                        )
                }
            }
            obj.search.aliases.forEach { alias ->
                entries +=
                    Entry(
                        qname = obj.qname,
                        kind = obj.kind,
                        matchedField = "alias",
                        matchedValue = alias,
                        content = alias.lowercase(),
                        baseScore = ALIAS_SCORE,
                    )
            }
            obj.search.examples.forEach { example ->
                entries +=
                    Entry(
                        qname = obj.qname,
                        kind = obj.kind,
                        matchedField = "example",
                        matchedValue = example,
                        content = example.lowercase(),
                        baseScore = EXAMPLE_SCORE,
                    )
            }
            obj.search.descriptions.byLanguage[language]?.forEach { altDesc ->
                entries +=
                    Entry(
                        qname = obj.qname,
                        kind = obj.kind,
                        matchedField = "alt_description",
                        matchedValue = altDesc,
                        content = altDesc.lowercase(),
                        baseScore = ALT_DESCRIPTION_SCORE,
                    )
            }
        }
        return RebuildOutcome(SubstringIndex(entries.toList()))
    }

    override fun search(
        request: SearchRequest,
        index: SearchIndex,
    ): List<SearchHit> {
        if (index !is SubstringIndex) return emptyList()
        val needle = request.query.lowercase().trim()
        if (needle.isEmpty()) return emptyList()

        val bestPerOwner = HashMap<QualifiedName, SearchHit>()
        for (entry in index.entries) {
            if (!entry.content.contains(needle)) continue
            val hit =
                SearchHit(
                    ownerQname = entry.qname,
                    ownerKind = entry.kind,
                    score = entry.baseScore,
                    matchedField = entry.matchedField,
                    matchedValue = entry.matchedValue,
                    snippet = makeSnippet(entry.matchedValue, entry.content, needle),
                    algorithm = name,
                )
            val current = bestPerOwner[entry.qname]
            if (current == null || hit.score > current.score) {
                bestPerOwner[entry.qname] = hit
            }
        }
        return bestPerOwner.values.toList()
    }

    private fun makeSnippet(
        original: String,
        loweredContent: String,
        needle: String,
    ): String {
        val idx = loweredContent.indexOf(needle)
        if (idx < 0) return original
        val start = (idx - SNIPPET_CONTEXT).coerceAtLeast(0)
        val end = (idx + needle.length + SNIPPET_CONTEXT).coerceAtMost(original.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < original.length) "…" else ""
        return prefix + original.substring(start, end) + suffix
    }

    companion object {
        private const val NAME_SCORE = 0.3f
        private const val DISPLAY_LABEL_SCORE = 0.4f
        private const val DESCRIPTION_SCORE = 0.5f
        private const val ALT_DESCRIPTION_SCORE = 0.5f
        private const val ALIAS_SCORE = 0.6f
        private const val EXAMPLE_SCORE = 0.7f
        private const val SNIPPET_CONTEXT = 20
    }
}

internal data class Entry(
    val qname: QualifiedName,
    val kind: String,
    val matchedField: String,
    val matchedValue: String,
    val content: String,
    val baseScore: Float,
)

class SubstringIndex internal constructor(
    internal val entries: List<Entry>,
) : SearchIndex
