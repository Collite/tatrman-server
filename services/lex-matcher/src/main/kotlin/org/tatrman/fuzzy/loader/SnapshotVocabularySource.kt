// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.tatrman.fuzzy.core.Candidate

/** One declared value: a lexicon `term`/`example` or a `valueLabels` entry. */
data class DeclaredValue(
    val id: String,
    val value: String,
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
}

/** Converts declared vocabulary into VOCABULARY-tagged categories keyed by target kind. */
object DeclaredVocabularyLoader {
    fun toCategories(vocab: DeclaredVocabulary): Map<String, List<Candidate>> =
        vocab.entries.associate { entry ->
            entry.category.lowercase() to
                entry.values.map { Candidate.vocabulary(it.id, it.value, entry.targetRef) }
        }
}
