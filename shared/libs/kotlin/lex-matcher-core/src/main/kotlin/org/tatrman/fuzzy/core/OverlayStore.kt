// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * The estate **overlay** (RV-4/18/20/23) — the third layer: what this estate's users taught it.
 *
 * Shaped empty at RV-P1.4 T6 and **filled at RV-P7.3**, where the seam had to be widened. P1.4
 * made the whole layer *consulted* rather than *loaded*, reasoning that loading it into the index
 * "would make a NEGATIVE entry inexpressible". That is right about negatives and wrong about
 * positives, and RV-P7.3 T2(a) is where it shows: a learned row must carry the **engine's**
 * `in_class_score`, and a consulted store is handed no engine, no query tokens and no index — so
 * it can only invent a number. Worse, a consulted positive is exact-match-only: the estate learns
 * `tržba` and the matcher then fails to serve it for `trzba`, which is the whole purpose of the
 * engine being bypassed.
 *
 * So the layer splits by polarity, and each half sits where it can actually be expressed:
 *
 *  * **POSITIVE entries LOAD** — [learned] hands the repository candidates tagged
 *    [SourceTag.LEARNED], which are then retrieved, tokenised, IDF-weighted and scored exactly
 *    like every other row. Read at refresh, when [hash] moves; never per query.
 *  * **NEGATIVE entries CONSULT** — [consult], per query, because a suppression is a statement
 *    *about the candidate set*, and that set does not exist until retrieval has run.
 *
 * One store for both, so one loaded overlay backs the candidates, the suppressions and the
 * [version] the RV-39 tuple echoes. Two objects could disagree about which overlay is serving.
 */
interface OverlayStore {
    /**
     * The overlay's version for the RV-39 tuple, or **null when no overlay exists**.
     *
     * Null, never `""` — "no overlay store" and "an overlay at version ''" are different facts, and
     * `overlay_version` is `optional` on the wire precisely so the first one is expressible (T2).
     *
     * This is the STORE's own version (RV-P7.2: a read is a snapshot that names its version), not
     * a content hash — that is [hash]'s job. Same split, for the same reason, as
     * `LexiconArchiveSource.hash()` vs `artifactHash()`: one drives the refresh clock, the other
     * says which overlay an answer came from, traceable to a row in `rv_overlay_versions`.
     */
    fun version(): String?

    /**
     * Content id of the overlay as it stands **at the source** — the refresh clock.
     *
     * The repository reloads [learned] only when this moves, which is the same two-clock
     * discipline the declared layer has had since RV-P1.4 T5. Stable (not random) when there is
     * no overlay, so an estate without one does not reload on every tick. Defaulted so an
     * implementation that has nothing to reload — [NoopOverlayStore], a test fake — says so by
     * saying nothing.
     */
    fun hash(): String = ""

    /**
     * POSITIVE entries as candidates, keyed by category, ready to merge into the index.
     *
     * The category convention is the declared layer's: **the target ref is the category key**.
     * Each candidate must carry its [TargetClass], because a class-scoped lookup (T5) excludes
     * rows that have none — a learned alias for a measure must not be able to answer "which
     * operator is this?". The class is **carried, never derived here**: the exporter states it,
     * for the same reason `LexiconArchiveSource` refuses to re-derive it from the ref's prefix.
     *
     * INVALIDATED and PROPOSED entries must not appear. The loader re-checks status rather than
     * trusting presence (T2(e)) — a transport that lags must not serve an entry the estate's last
     * snapshot build already retired.
     */
    suspend fun learned(): Map<String, List<Candidate>> = emptyMap()

    /**
     * An **immutable view of this overlay as it stands now** — the unit a request is pinned to.
     *
     * RV-P7.3 T4. The candidates, the suppressions and the version are three faces of one overlay,
     * and a request that mixed two of them is a request whose answer cannot be reproduced from its
     * RV-39 tuple — which is the one promise the tuple makes. A live store swaps underneath
     * readers; a pin does not, so `StringRepository` publishes a pin at the same instant it
     * publishes the index built from it, and [FuzzyMatcher] reads it once per call rather than once
     * per span.
     *
     * Defaulted to `this` for stores that cannot change beneath a reader — [NoopOverlayStore] and
     * every test fake — so a pin costs them nothing.
     */
    fun pinned(): OverlayStore = this

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
 * The overlay's answer: which targets this estate has learned the term does **not** mean.
 *
 * **RV-P7.3 removed `additions` from here.** P1.4 let a store hand back ready-made
 * [FuzzyMatchResult]s, which is how a consulted layer had to add a positive — and it is exactly
 * the defect: a row nobody scored, arriving with a number the store made up. Positives now load
 * (see [OverlayStore.learned]) and are scored by the engine like everything else. Keeping both
 * paths would have left two ways to add a learned alias, one of them unscored.
 */
data class OverlayVerdict(
    /**
     * Target refs the estate has learned this term does **not** mean (NEGATIVE entries).
     *
     * Suppressed candidates are **flagged, not removed**. lex-matcher does not pick a winner across
     * layers (P1.4 T2) and does not destroy the ambiguity the lattice exists to represent (RV-2):
     * the candidate is still returned and still ranked, marked never-auto-bindable, and the
     * resolver's evidence-class gate decides. Dropping it would also make a wrong negative
     * unrecoverable and invisible.
     */
    val suppressedTargets: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = suppressedTargets.isEmpty()

    companion object {
        val EMPTY = OverlayVerdict()
    }
}

/**
 * The overlay slot, empty — what every deployment without a learning store runs.
 *
 * Reports no version (so the tuple omits `overlay_version`, which is the contract), loads nothing
 * and consults to nothing, so [FuzzyMatcher] returns its results untouched. An estate with no
 * learning history is the normal case, not a degraded one.
 */
object NoopOverlayStore : OverlayStore {
    override fun version(): String? = null

    override suspend fun consult(request: OverlayRequest): OverlayVerdict = OverlayVerdict.EMPTY
}
