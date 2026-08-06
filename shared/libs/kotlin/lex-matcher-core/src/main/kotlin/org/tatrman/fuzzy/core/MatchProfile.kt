// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * RV-44 — the **declared matching profile** a compiled-lexicon row carries (contracts §2 addendum).
 *
 * A core-side mirror of `org.tatrman.ttr.lexicon.MatchProfile`, for the same reason [MatchMethod],
 * [SourceTag] and [TargetClass] are mirrors: this library is the matching engine and must not
 * resolve the toolchain to run. `LexiconArchiveSource` maps the artifact's shapes onto these,
 * which is also the one place a future artifact-side change has to be noticed.
 *
 * What a profile says, in one line: *which normalized forms count for this term, and how strong
 * each is.* Everything it does NOT say is as load-bearing — it does not pick a winner across rows,
 * it does not name an evidence class, and it does not decide what binds. Classes still decide;
 * a declared score only orders rows **within** one class (RV-14, untouched).
 */
data class MatchProfile(
    val rules: List<NormRule>,
)

/**
 * The normalization stratum a rule compares on. Closed (⚑M-1) — each name is a form this engine
 * already computes:
 *
 *  - [CANONICAL] — [TextNormalizer.canonical]: NFC + lowercase, **diacritics preserved**. The
 *    authored form, and the basis EXACT/TYPOS have compared on since P1.4 T4.
 *  - [FOLDED] — [TextNormalizer.fold]: canonical plus diacritics stripped, the recall index key.
 *    Declaring it is what makes *"zakaznik finds zákazník"* an authored fact with its own score
 *    rather than an accident of a dropped accent costing exactly one edit.
 *  - [LEMMA] — the repository's lemmatiser output. ⚠ **Folded**, because that is what the existing
 *    lemma path produces (`Candidate.lemmaTokens`); the design's "diacritics preserved" is a
 *    lemmatiser-side change, not a scoring one. With no lemmatiser installed the axis collapses
 *    onto [FOLDED] exactly as every other lemma-aware path in this engine already does.
 */
enum class Norm(
    val wire: String,
) {
    CANONICAL("canonical"),
    FOLDED("folded"),
    LEMMA("lemma"),
    ;

    companion object {
        fun ofWire(wire: String): Norm? = entries.firstOrNull { it.wire == wire }
    }
}

/** Bounded edit distance on one norm, costing [penalty] per edit off that norm's `exact` score. */
data class TyposRule(
    val distance: Int,
    val penalty: Double,
)

/**
 * One `(norm, algorithms, scores)` row of a profile. All three algorithm slots are optional and at
 * least one is present — the compiler's validator guarantees it, and a rule that somehow arrives
 * empty simply never fires.
 */
data class NormRule(
    val norm: Norm,
    val exact: Double? = null,
    val typos: TyposRule? = null,
    val tokens: Boolean = false,
)

/** Which algorithm produced a firing — the second half of the provenance triple. */
object MatchAlgorithm {
    const val EXACT: String = "exact"
    const val TYPOS: String = "typos"
    const val TOKENS: String = "tokens"
}
