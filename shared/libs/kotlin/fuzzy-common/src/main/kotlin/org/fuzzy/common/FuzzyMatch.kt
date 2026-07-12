// SPDX-License-Identifier: Apache-2.0
package org.fuzzy.common

import kotlinx.serialization.Serializable

/** S-4 confidence provenance (RG-P2). */
@Serializable
data class Provenance(
    val producer: String = "fuzzy",
    val method: String = "TATRMAN",
    val rawScore: Double = 0.0,
)

@Serializable
data class FuzzyMatch(
    val candidateId: String,
    val candidate: String,
    val score: Double,
    val category: String,
    // RG-P2 additive (contracts §2, RS-15); defaulted for backward compatibility.
    val source: String = "MEMBER", // MEMBER | VOCABULARY
    val targetRef: String? = null, // set iff source = VOCABULARY
    val provenance: Provenance? = null,
)

@Serializable
data class FuzzyMatchResponse(
    val matches: List<FuzzyMatch> = emptyList(),
    val isError: Boolean = false,
    val error: String = "",
    // Which algorithm produced `matches` (cascade winner / last tried).
    // Defaulted so existing callers and payloads stay backward-compatible.
    val matchedAlgorithm: String = "",
    // RG-P2 (S-1): snapshot hash + per-category load timestamp (populated in S2).
    val vocabularyVersion: String = "",
)
