package org.fuzzy.common

import kotlinx.serialization.Serializable

@Serializable
data class FuzzyMatch(
    val candidateId: String,
    val candidate: String,
    val score: Double,
    val category: String,
)

@Serializable
data class FuzzyMatchResponse(
    val matches: List<FuzzyMatch> = emptyList(),
    val isError: Boolean = false,
    val error: String = "",
    // Which algorithm produced `matches` (cascade winner / last tried).
    // Defaulted so existing callers and payloads stay backward-compatible.
    val matchedAlgorithm: String = "",
)
