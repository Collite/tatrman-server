// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.model

/**
 * The resolver-side registry (RG-P5, RS-24) — the plain-Kotlin view of the
 * declared vocabulary the pipeline gates against. Built from either the snapshot
 * (default, S2) or a caller-supplied `Registry` proto override (which wins for
 * that request). Kept as an internal model so span proposal / gateSpans never
 * touch the wire types directly.
 */
data class ResolverRegistry(
    val entityTypes: List<ResolverEntityType>,
    val locales: List<String>,
    val thresholds: ResolverThresholds,
    val snapshotHash: String,
)

/**
 * One declared entity type. [anchors] are the declared anchor words (the lexicon
 * `term`/`entityAliases` for er/db/md kinds) that Q-20's anchored span proposal
 * ties content subtrees to; [categories] are the fuzzy categories a span gated to
 * this type is matched against (one BatchMatch slot per proposed span).
 */
data class ResolverEntityType(
    val ref: String,
    val categories: List<String>,
    val anchors: List<String>,
)

/**
 * Gating thresholds — ported from the live ENTITIES_ONLY config
 * (`ResolverGraph.kt:38-48`). Provenance for the numbers is that file.
 */
data class ResolverThresholds(
    val bind: Double,
    val ambiguityGap: Double,
    val exact: Double,
    val maxOptions: Int,
) {
    companion object {
        /** The live ENTITIES_ONLY defaults (also mirrored in `application.conf`). */
        val LIVE = ResolverThresholds(bind = 0.5, ambiguityGap = 0.05, exact = 0.9999, maxOptions = 20)
    }
}
