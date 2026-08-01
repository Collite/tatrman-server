// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * How a candidate's category was sourced (contracts §2, RS-15). MEMBER = data
 * values (the candidate `id` is a data PK → `resolved_id`); VOCABULARY = declared
 * lexicon / valueLabels (the candidate carries a `targetRef` → lexicon target).
 * Both flow through the same cascade with the same scoring.
 */
enum class SourceTag { MEMBER, VOCABULARY }

/** S-4 confidence provenance: which producer + method yielded the score. */
data class Provenance(
    val producer: String,
    val method: String,
    val rawScore: Double,
)

data class FuzzyMatchResult(
    val candidateId: String,
    val candidate: String,
    val score: Double,
    val category: String,
    // RG-P2 additive (response-side; the pinned MatchRequest is untouched):
    val source: SourceTag = SourceTag.MEMBER,
    val targetRef: String? = null,
    val provenance: Provenance = Provenance("fuzzy", "TATRMAN", score),
)
