// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * RV-P1.4 T6 — the third layer's seam: the estate **overlay** (RV-4/18/20/23), built at RV-P6.
 *
 * The other two layers are *loaded* — the compiled artifact and the member index become candidates
 * at refresh time and then sit in the index. The overlay is *consulted*: it is a per-estate,
 * append-only record of what users confirmed and denied, it changes between refreshes, and its
 * entries carry polarity — some add a binding, some withdraw one. Loading it into the same index
 * would make a NEGATIVE entry inexpressible.
 *
 * So the seam is (version, consult), and it is shaped now, empty, for one reason: the RV-P6 store
 * must plug in **without touching resolution code**. Everything the contract's `OverlayEntry` can
 * say — POSITIVE, NEGATIVE, estate-scoped, snapshot-versioned — has a place to be said here.
 */
interface OverlayStore {
    /**
     * The overlay's version for the RV-39 tuple, or **null when no overlay exists**.
     *
     * Null, never `""` — "no overlay store" and "an overlay at version ''" are different facts, and
     * `overlay_version` is `optional` on the wire precisely so the first one is expressible (T2).
     */
    fun version(): String?

    /**
     * Consulted once per query, **after** method dispatch and with whatever the other layers
     * produced.
     *
     * After dispatch on purpose: [MethodDispatcher] honours the *authored* method (RV-32), and a
     * learned entry has no author. Gating the overlay on a rule written for the declared layer
     * would silently discard exactly the aliases users taught the estate because the estate's
     * authors never wrote them down.
     */
    suspend fun consult(request: OverlayRequest): OverlayVerdict
}

/**
 * What the overlay is being asked about: the query, the scope it was asked in, and what the other
 * layers already found.
 *
 * [candidates] is passed in because a NEGATIVE entry is a statement *about a candidate* — the store
 * cannot express "not that one" without seeing which ones are on the table.
 */
data class OverlayRequest(
    val term: String,
    /** The categories searched; empty for the deliberate cross-category lookup. */
    val categories: List<String> = emptyList(),
    val candidates: List<FuzzyMatchResult> = emptyList(),
)

/**
 * The overlay's answer. Both halves are additive to the lattice, never a rewrite of it.
 */
data class OverlayVerdict(
    /**
     * Learned aliases to add (POSITIVE entries), already shaped as results — the store owns their
     * score and provenance, since only it knows how many users confirmed them and when.
     */
    val additions: List<FuzzyMatchResult> = emptyList(),
    /**
     * Target refs the estate has learned this term does **not** mean (NEGATIVE entries).
     *
     * Suppressed candidates are **flagged, not removed**. lex-matcher does not pick a winner across
     * layers (T2) and does not destroy the ambiguity the lattice exists to represent (RV-2): the
     * candidate is still returned and still ranked, marked never-auto-bindable, and the resolver's
     * evidence-class gate decides. Dropping it would also make a wrong negative unrecoverable and
     * invisible.
     */
    val suppressedTargets: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = additions.isEmpty() && suppressedTargets.isEmpty()

    companion object {
        val EMPTY = OverlayVerdict()
    }
}

/**
 * The overlay slot, empty — what every deployment runs until RV-P6.
 *
 * Reports no version (so the tuple omits `overlay_version`, which is the contract) and consults to
 * nothing, so [FuzzyMatcher] returns its results untouched. An estate with no learning history is
 * the normal case, not a degraded one.
 */
object NoopOverlayStore : OverlayStore {
    override fun version(): String? = null

    override suspend fun consult(request: OverlayRequest): OverlayVerdict = OverlayVerdict.EMPTY
}
