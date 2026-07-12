// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

data class FuzzyMatchResult(
    val candidateId: String,
    val candidate: String,
    val score: Double,
    val category: String,
)
