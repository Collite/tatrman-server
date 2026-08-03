// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.SourceTag

/**
 * One declared value: a lexicon `term`/`example` or a `valueLabels` entry.
 *
 * [source] and [matchMethod] were added at RV-P1.4: the compiled artifact distinguishes an
 * authored term (DECLARED) from a harvested model label (METADATA) and carries the authored match
 * method per row. Both default to the pre-RV behaviour so the fixture stub — which genuinely
 * cannot tell the two apart — is unchanged.
 */
data class DeclaredValue(
    val id: String,
    val value: String,
    val source: SourceTag = SourceTag.VOCABULARY,
    /** `EXACT` · `TOKENS` · `TYPOS(n)`, or null when the source has no authored method. */
    val matchMethod: String? = null,
)

/**
 * A declared-vocabulary entry: the searchable [values] for one [targetRef]
 * (contracts §7 — `term`/`pattern`/`example` for md/er/db, or `valueLabels`).
 * [category] is the query key (the target kind), keyed on which the resolver
 * gates domain spans.
 */
data class DeclaredVocabularyEntry(
    val category: String,
    val targetRef: String,
    val values: List<DeclaredValue>,
)

data class DeclaredVocabulary(
    val entries: List<DeclaredVocabularyEntry> = emptyList(),
)

/**
 * The seam for RG-P4's declared vocabulary (lexicon terms + valueLabels).
 *
 * **RO-13 pending (rule 6):** this interface IS the seam — the fixture stub
 * here, a live-metadata *step-one* adapter, and the real snapshot-archive reader
 * all implement it later. [hash] is the snapshot identity: declared vocabulary
 * reloads only when it changes (the two-clock refresh, S2.T5).
 */
interface SnapshotVocabularySource {
    suspend fun fetch(): DeclaredVocabulary

    fun hash(): String

    /**
     * RV-39 — the compiled artifact's own content hash for the layer tuple's
     * `lexicon_artifact_hash`. Defaults to [hash] because for the artifact reader they ARE the
     * same value; the fixture stub overrides nothing and reports its stub hash, which is honest —
     * it says which vocabulary is loaded, and there is no artifact behind it to name.
     */
    fun artifactHash(): String = hash()
}

/**
 * Converts declared vocabulary into candidates keyed by target kind, preserving each value's own
 * source tag and authored method (RV-P1.4). Categories are lowercased to match the query side.
 */
object DeclaredVocabularyLoader {
    fun toCategories(vocab: DeclaredVocabulary): Map<String, List<Candidate>> =
        vocab.entries.associate { entry ->
            entry.category.lowercase() to
                entry.values.map {
                    Candidate.vocabulary(it.id, it.value, entry.targetRef, it.source, it.matchMethod)
                }
        }
}
