package org.tatrman.fuzzy.core

import info.debatty.java.stringsimilarity.Damerau
import info.debatty.java.stringsimilarity.JaroWinkler
import info.debatty.java.stringsimilarity.Levenshtein
// import info.debatty.java.stringsimilarity.Hamming

enum class AlgorithmType {
    LEVENSHTEIN,
    DAMERAU_LEVENSHTEIN,
    JARO_WINKLER,
    HAMMING,
    TATRMAN,
    ;

    companion object {
        fun fromString(value: String?): AlgorithmType =
            if (value.isNullOrBlank()) {
                TATRMAN // Default
            } else {
                try {
                    valueOf(value.uppercase())
                } catch (e: IllegalArgumentException) {
                    TATRMAN
                }
            }
    }
}

interface MatchingAlgorithm {
    fun distance(
        s1: String,
        s2: String,
    ): Double

    // Normalize distance to similarity score 0.0 - 1.0 (1.0 = exact match)
    fun similarity(
        s1: String,
        s2: String,
    ): Double
}

class LevenshteinAlgorithm : MatchingAlgorithm {
    private val impl = Levenshtein()

    override fun distance(
        s1: String,
        s2: String,
    ): Double = impl.distance(s1, s2)

    override fun similarity(
        s1: String,
        s2: String,
    ): Double {
        val dist = distance(s1, s2)
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        return 1.0 - (dist / maxLen.toDouble())
    }
}

class DamerauLevenshteinAlgorithm : MatchingAlgorithm {
    private val impl = Damerau()

    override fun distance(
        s1: String,
        s2: String,
    ): Double = impl.distance(s1, s2)

    override fun similarity(
        s1: String,
        s2: String,
    ): Double {
        val dist = distance(s1, s2)
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        return 1.0 - (dist / maxLen.toDouble())
    }
}

class JaroWinklerAlgorithm : MatchingAlgorithm {
    private val impl = JaroWinkler()

    override fun distance(
        s1: String,
        s2: String,
    ): Double = impl.distance(s1, s2)

    override fun similarity(
        s1: String,
        s2: String,
    ): Double = impl.similarity(s1, s2)
}

// class HammingAlgorithm : MatchingAlgorithm {
//    private val impl = Hamming()
//
//    override fun distance(
//        s1: String,
//        s2: String,
//    ): Double = impl.distance(s1, s2)
//
//    override fun similarity(
//        s1: String,
//        s2: String,
//    ): Double {
//        // Hamming requires same length. If not, we can treat difference in length as penalty or
//        // error.
//        // The library implementation throws exception if lengths differ.
//        if (s1.length != s2.length) return 0.0
//        val dist = distance(s1, s2)
//        if (s1.isEmpty()) return 1.0
//        return 1.0 - (dist / s1.length.toDouble())
//    }
// }

object AlgorithmFactory {
    fun get(type: AlgorithmType): MatchingAlgorithm =
        when (type) {
            AlgorithmType.LEVENSHTEIN -> LevenshteinAlgorithm()
            AlgorithmType.DAMERAU_LEVENSHTEIN -> DamerauLevenshteinAlgorithm()
            AlgorithmType.JARO_WINKLER -> JaroWinklerAlgorithm()
            AlgorithmType.HAMMING -> JaroWinklerAlgorithm()
            AlgorithmType.TATRMAN -> TokenBasedAlgorithm()
        }
}
