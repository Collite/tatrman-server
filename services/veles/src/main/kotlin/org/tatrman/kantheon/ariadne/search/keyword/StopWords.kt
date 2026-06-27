package org.tatrman.kantheon.ariadne.search.keyword

import org.slf4j.LoggerFactory

/**
 * Per-language stop-word resource loader.
 *
 * Resource layout: `/search/stop-words-<lang>.txt`. One token per line; lines
 * starting with `#` are comments; blank lines are ignored. Loaded at service
 * startup; reload via [reload] (wired into the admin endpoint).
 *
 * Missing files load to an empty set with a warn-log — the keyword algorithm
 * still works, it just doesn't filter noise tokens for that language.
 */
class StopWords(
    private val supportedLanguages: List<String>,
) {
    private val log = LoggerFactory.getLogger(StopWords::class.java)

    @Volatile private var byLanguage: Map<String, Set<String>> = emptyMap()

    init {
        reload()
    }

    fun reload() {
        val next = mutableMapOf<String, Set<String>>()
        for (lang in supportedLanguages) next[lang] = loadLanguage(lang)
        byLanguage = next
    }

    fun isStop(
        language: String,
        token: String,
    ): Boolean = byLanguage[language]?.contains(token) == true

    fun forLanguage(language: String): Set<String> = byLanguage[language] ?: emptySet()

    private fun loadLanguage(language: String): Set<String> {
        val path = "/search/stop-words-$language.txt"
        val stream = StopWords::class.java.getResourceAsStream(path)
        if (stream == null) {
            log.warn("No stop-word resource for language={} at path={}", language, path)
            return emptySet()
        }
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }
    }
}
