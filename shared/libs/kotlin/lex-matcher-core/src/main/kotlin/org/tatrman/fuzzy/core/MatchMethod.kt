// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * RV-32 — the **authored** match method, parsed from the compiled lexicon's per-entry `method`.
 *
 * A different axis from [Provenance.method] (the algorithm that produced the score): this is the
 * author's statement of how much slack a term is allowed, and T4 makes the matcher honour it.
 *
 * Absent for member values — nobody authored a method for a data value — which is why [parse]
 * returns null rather than a default. Choosing a default here would silently apply an authoring
 * rule to rows no author ever touched.
 */
sealed interface MatchMethod {
    /** Equality on the authored form ([TextNormalizer.canonical]) — no slack at all. */
    data object Exact : MatchMethod

    /** The existing TATRMAN token algorithm, plus the RV-32 uniqueness margin. */
    data object Tokens : MatchMethod

    /** Levenshtein on the authored form, capped at [maxDistance] edits. */
    data class Typos(
        val maxDistance: Int,
    ) : MatchMethod

    companion object {
        private val TYPOS = Regex("""TYPOS\((\d+)\)""", RegexOption.IGNORE_CASE)

        /**
         * Parses `EXACT` · `TOKENS` · `TYPOS(n)`; **null for anything else**, including null itself.
         *
         * Unrecognised text degrades to "unauthored" rather than throwing, matching T3's posture:
         * a lexicon the matcher cannot fully understand must be loud in the log and invisible in
         * the response, not a 500. The compiler validates the vocabulary (RG-LEX rules) — this is
         * the last line, for an archive built by an older or newer toolchain.
         *
         * **Silent by design.** This used to warn on unrecognised text, but it is called per
         * candidate per request: an archive from a newer toolchain carrying one `SEMANTIC(0.8)` row
         * would emit a WARN on every query that surfaced it, forever. "Loud in the log" has to mean
         * *once per load*, so the observation moved to where loading happens — see
         * `LexiconArchiveSource.fetch`, which reports the distinct unrecognised methods per archive.
         * Keeping this a pure function is also what lets [Candidate] parse once at load time.
         */
        fun parse(raw: String?): MatchMethod? {
            val text = raw?.trim() ?: return null
            if (text.isEmpty()) return null
            TYPOS.matchEntire(text)?.let { return Typos(it.groupValues[1].toInt()) }
            return when (text.uppercase()) {
                "EXACT" -> Exact
                "TOKENS" -> Tokens
                else -> null
            }
        }
    }
}
