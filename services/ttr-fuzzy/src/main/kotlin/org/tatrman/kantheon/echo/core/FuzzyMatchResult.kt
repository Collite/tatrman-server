package org.tatrman.kantheon.echo.core

data class FuzzyMatchResult(
    val candidateId: String,
    val candidate: String,
    val score: Double,
    val category: String,
)
