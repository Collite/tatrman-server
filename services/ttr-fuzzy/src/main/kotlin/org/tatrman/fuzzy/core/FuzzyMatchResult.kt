package org.tatrman.fuzzy.core

data class FuzzyMatchResult(
    val candidateId: String,
    val candidate: String,
    val score: Double,
    val category: String,
)
