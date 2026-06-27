package org.tatrman.kantheon.ariadne.search.keyword

import java.text.Normalizer

/**
 * Lowercase + Unicode NFD diacritic-fold + whitespace split + stop-word drop.
 * Used by the [KeywordAlgorithm] for both indexing and query tokenisation.
 *
 * The diacritic-fold is intentional — Czech keyword authoring routinely
 * mixes accented and unaccented forms ("zákazníci" vs "zakaznici"). Folding
 * lets a single index entry serve both spellings without duplicate tokens.
 */
class Tokenizer(
    private val stopWords: StopWords,
) {
    fun tokenize(
        input: String,
        language: String,
    ): List<String> {
        if (input.isBlank()) return emptyList()
        val folded = fold(input)
        return folded
            .split(WHITESPACE)
            .asSequence()
            .filter { it.isNotEmpty() }
            .filter { !stopWords.isStop(language, it) }
            .toList()
    }

    private fun fold(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        return COMBINING_MARKS.replace(nfd, "").lowercase()
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val COMBINING_MARKS = Regex("\\p{M}+")
    }
}
