// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import kotlinx.serialization.Serializable

/**
 * On-disk parity golden: the score is stored as a fixed `"%.9f"` string (not a raw double) so the
 * committed JSON round-trips byte-exactly and the parity gate compares against a stable decimal.
 */
@Serializable
data class GoldenResult(
    val candidateId: String,
    val score: String,
)

@Serializable
data class GoldenEntry(
    val queryId: String,
    val query: String,
    val category: String?,
    val results: List<GoldenResult>,
)

/** Formats a score the single canonical way (used by both capture and any ad-hoc comparison). */
fun formatGoldenScore(score: Double): String = "%.9f".format(score)
