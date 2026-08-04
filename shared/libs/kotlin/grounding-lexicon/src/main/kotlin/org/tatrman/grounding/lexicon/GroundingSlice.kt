// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.lexicon

import org.tatrman.text.Normalization

/**
 * RV-P1.6 T4 (RV-42) — one grounding kernel's slice of the compiled lexicon: the words that say
 * "this span is about time / money / geography".
 *
 * **A trigger vocabulary, not an interpretation grammar.** This is the boundary RV-42 draws and it
 * is the whole reason a flat `ground:<kind>` entry table is enough: a slice term ANCHORS the
 * kernel, it does not tell it what the span means. Which interval "minulý měsíc" denotes, how
 * "1 000 Kč" parses, which calendar a fiscal year follows — all of that stays generative in the
 * kernel, so grounding still works with zero entries for a pure-pattern span like "12.5.2024".
 *
 * The distinction matters concretely: the kernels' rule words are *stems* matched inside ordered
 * rules (`hasAny(n, "mesic")` catches "měsíce" and "měsících" alike). Those stay. What moves into
 * the lexicon is the question the estate must be able to extend and localize — "is this span
 * mine?" — which is exactly what an estate authoring "fiskál" wants to change.
 */
data class GroundingSlice(
    /** `chrono` · `money` · `geo` — the ref suffix, not the `ground:` ref. */
    val kind: String,
    val terms: List<GroundingTerm>,
    /**
     * RV-39/S-1: the artifact hash the slice was read from, echoed in the kernel's status so a
     * caller can tell which vocabulary answered. Empty when no artifact is serving.
     */
    val version: String,
) {
    val isEmpty: Boolean get() = terms.isEmpty()

    /**
     * Does [span] carry a trigger from this slice?
     *
     * Folded on both sides (the S-2 shared normalization), because a trigger must fire on
     * "fiskální" and "fiskalni" alike. Matching honours the authored RV-32 method:
     *
     * - `EXACT` — the folded term appears as a whole word. Whole-word, not substring: "Kč" must
     *   not fire inside "kčokoliv", and a two-character code has no room for anything looser.
     * - `TYPOS(n)` — a whole word of the span is within [n] edits of the term. This is what makes
     *   one authored "měsíc" cover "měsíce"; deeper case forms are the kernel's stems, not ours.
     * - `TOKENS` — every token of a multi-word term appears somewhere in the span, in any order.
     *   "fiskální rok" fires on "v fiskálním roce 2026".
     */
    fun matches(span: String): Boolean = matched(span) != null

    /**
     * This slice narrowed to the terms an estate declared for [locale]'s language.
     *
     * A term carries the `lang` its author gave it (`cs`, `en`, `cs|en`), and until the request's
     * locale is applied that field is decoration: an English trigger would be live on a Czech
     * question. Callers pass the BCP-47 locale from the request context (`cs-CZ`); only the primary
     * subtag is compared.
     *
     * A blank or unknown locale narrows nothing — every term stays eligible, which is the pre-RV
     * reading and the right default for a request that did not say.
     */
    fun forLang(locale: String?): GroundingSlice {
        val lang = languageOf(locale) ?: return this
        val narrowed = terms.filter { it.appliesTo(lang) }
        return if (narrowed.size == terms.size) this else copy(terms = narrowed)
    }

    /** The first term that fires, or null — the same question as [matches], with the evidence. */
    fun matched(span: String): GroundingTerm? {
        if (terms.isEmpty()) return null
        val folded = Normalization.fold(span)
        val words = TOKEN_SPLIT.split(folded).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        return terms.firstOrNull { term -> term.fires(words) }
    }

    companion object {
        internal val TOKEN_SPLIT = Regex("""[^\p{L}\p{N}.]+""")

        /** An absent artifact is an empty slice, never a failure — see `GroundingSliceSource`. */
        fun empty(kind: String): GroundingSlice = GroundingSlice(kind, emptyList(), "")

        /** `cs-CZ` → `cs`; blank/null → null, meaning "the request did not say". */
        internal fun languageOf(locale: String?): String? =
            locale
                ?.trim()
                ?.substringBefore('-')
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
    }
}

/** One trigger word, with the match method its author declared (RV-32). */
data class GroundingTerm(
    /** Folded at construction — every comparison here is fold-to-fold. */
    val folded: String,
    /** The term exactly as authored, for diagnostics and for the lattice annotation (T6). */
    val text: String,
    val method: TriggerMethod,
    /** `cs` · `en` · `cs|en`, verbatim from the artifact. */
    val lang: String,
) {
    private val tokens: List<String> =
        GroundingSlice.TOKEN_SPLIT.split(folded).filter { it.isNotBlank() }

    /**
     * Is this term declared for [lang]? `cs|en` applies to both; an unparseable `lang` applies to
     * everything, because dropping a term over a field the estate got slightly wrong would be a
     * silent loss of vocabulary.
     */
    internal fun appliesTo(lang: String): Boolean {
        val declared = lang.lowercase()
        val own =
            this.lang
                .split('|')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
        return own.isEmpty() || own.contains(declared)
    }

    internal fun fires(spanWords: List<String>): Boolean =
        when (method) {
            is TriggerMethod.Exact -> spanWords.any { it == folded }
            is TriggerMethod.Typos -> spanWords.any { withinDistance(it, folded, method.maxDistance) }
            is TriggerMethod.Tokens -> tokens.isNotEmpty() && tokens.all { t -> spanWords.any { it == t } }
        }
}

/** The RV-32 methods, as the kernels need them. Parsed from the artifact's wire string. */
sealed interface TriggerMethod {
    data object Exact : TriggerMethod

    data object Tokens : TriggerMethod

    data class Typos(
        val maxDistance: Int,
    ) : TriggerMethod

    companion object {
        private val TYPOS = Regex("""^TYPOS\((\d+)\)$""")

        /**
         * `EXACT` · `TOKENS` · `TYPOS(n)`. An unrecognized string degrades to EXACT rather than
         * throwing: the artifact is the estate's to fix, and a kernel that refuses to start
         * because one row is odd is worse than one that matches that row narrowly.
         */
        fun parse(wire: String): TriggerMethod =
            when {
                wire == "TOKENS" -> Tokens
                TYPOS.matches(wire) -> Typos(TYPOS.find(wire)!!.groupValues[1].toInt())
                else -> Exact
            }
    }
}

/**
 * Bounded Levenshtein: true when [a] and [b] are within [max] edits. Bailing out on the length
 * difference first is what keeps this cheap on a long span — a trigger check runs per request.
 */
internal fun withinDistance(
    a: String,
    b: String,
    max: Int,
): Boolean {
    if (max <= 0) return a == b
    if (kotlin.math.abs(a.length - b.length) > max) return false
    if (a == b) return true

    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        var rowMin = current[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            rowMin = minOf(rowMin, current[j])
        }
        // Every remaining row can only grow the minimum, so an over-budget row ends it.
        if (rowMin > max) return false
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length] <= max
}
