// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * A matchable string. [tokens] are the folded-surface tokens (always present). [lemmaTokens] are
 * the folded *lemmas* of those tokens — populated by the repository when `infra/nlp`
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

        /** Builds a candidate with explicit (folded) lemma tokens — used by the repository's lemmatisation.
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
