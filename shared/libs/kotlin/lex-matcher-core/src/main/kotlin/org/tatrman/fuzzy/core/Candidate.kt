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
    /**
     * RV-32 — the authored match method (`EXACT` · `TOKENS` · `TYPOS(n)`) from the compiled
     * lexicon. Null for member candidates: nobody authored a method for a data value.
     */
    val matchMethod: String? = null,
    /**
     * RV-38 — the target's class, for T5's target-class-scoped lookup. Null for member values and
     * for any source that does not carry one.
     */
    val targetClass: TargetClass? = null,
    /**
     * RV-44 — the resolved matching profile from the compiled lexicon, when the row has one.
     *
     * Null for every member value and every harvested model label (⚑M-2), which is exactly what
     * keeps their scoring untouched: [ProfileScorer] only ever sees a row that carries one.
     */
    val matchProfile: MatchProfile? = null,
) {
    /** Surface tokens ∪ lemma tokens — used to seed the candidate set for a query. */
    val allTokenSet: Set<String> get() = tokenSet + lemmaTokenSet

    /**
     * FZ-P1 T5 — the folded value, computed once at construction (the standard-algorithm cascade
     * folded [value] on every request before this). A body `val` ⇒ it is NOT part of the data
     * class's generated equals/hashCode/copy/componentN, so equality is unchanged.
     */
    val foldedValue: String = TextNormalizer.fold(value)

    /**
     * RV-P1.4 T4 — [matchMethod] parsed **once at load**, not once per candidate per request.
     *
     * [MethodDispatcher] runs on every query that surfaces this candidate, and used to re-parse the
     * string each time (twice, on the paths that also re-margin). Parsing here moves the regex to
     * the refresh that built the index. Free for member rows: `parse(null)` is a null check.
     *
     * A body `val`, so it stays out of the generated equals/hashCode/copy like [foldedValue].
     */
    val authoredMethod: MatchMethod? = MatchMethod.parse(matchMethod)

    /**
     * RV-P1.4 T4 — the authored form (diacritics intact) that `EXACT`/`TYPOS(n)` dispatch compares
     * against, precomputed for the same reason as [foldedValue].
     *
     * **Null when no method was authored**, which is every member value — deliberately, so the
     * member path pays no NFC normalisation at load. Nothing reads it for those rows: dispatch
     * admits an unauthored candidate without ever looking at its canonical form.
     */
    val canonicalValue: String? =
        if (authoredMethod == null && matchProfile == null) null else TextNormalizer.canonical(value)

    /**
     * RV-44 — the candidate's lemma form, for a profile's `lemma` norm. Joined from [lemmaTokens],
     * so it is whatever the repository's lemmatiser produced (folded) and collapses onto the folded
     * surface when none is installed.
     *
     * A body `val`, so it stays out of the generated equals/hashCode/copy like [foldedValue].
     */
    val lemmaValue: String = lemmaTokens.joinToString(" ")

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

        /**
         * A declared-vocabulary candidate (contracts §2): carries the lexicon [targetRef].
         *
         * [source] and [matchMethod] default to the pre-RV behaviour so every existing call site
         * is unchanged; the compiled-artifact loader (RV-P1.4 T3) passes the artifact's own values.
         */
        fun vocabulary(
            id: String,
            value: String,
            targetRef: String,
            source: SourceTag = SourceTag.VOCABULARY,
            matchMethod: String? = null,
            targetClass: TargetClass? = null,
            matchProfile: MatchProfile? = null,
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
                source = source,
                targetRef = targetRef,
                matchMethod = matchMethod,
                targetClass = targetClass,
                matchProfile = matchProfile,
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
            matchMethod: String? = null,
            targetClass: TargetClass? = null,
            matchProfile: MatchProfile? = null,
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
                matchMethod = matchMethod,
                targetClass = targetClass,
                matchProfile = matchProfile,
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
