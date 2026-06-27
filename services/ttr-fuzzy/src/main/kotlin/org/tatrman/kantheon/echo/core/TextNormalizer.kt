package org.tatrman.kantheon.echo.core

import java.text.Normalizer

/**
 * Locale-independent text folding for matching.
 *
 * [fold] lower-cases, NFD-decomposes, and strips combining marks so that
 * diacritic-only differences ("Zákazník" / "zakaznik" / "ZAKAZNIKU") collapse to
 * the same form. Czech-first but harmless for other Latin scripts.
 *
 * This is the single normalization point for both candidate-side token building
 * (`Candidate.tokenize`) and query-side matching, so the token index, the
 * Levenshtein distances, and the standard algorithms all operate on folded text.
 */
object TextNormalizer {
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    fun fold(input: String): String =
        Normalizer
            .normalize(input.lowercase(), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
}
