// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import info.debatty.java.stringsimilarity.Levenshtein

/**
 * RV-P1.4 T4 — honours the authored [MatchMethod] on the cascade's output.
 *
 * **A gate, not a third retrieval path, and not a rescorer.**
 *  - *Not a retrieval path*: the cascade is recall-oriented and already surfaced every candidate
 *    worth considering; the method is precision, so dispatch may only narrow. An `EXACT` term the
 *    cascade did not surface is a term the query did not match anyway (an exact hit scores at the
 *    top of the token algorithm by construction).
 *  - *Not a rescorer*: an admitted candidate keeps the engine's score. Rewriting scores per method
 *    would put three scales in one response — precisely the problem [FuzzyMatcher.matchCascade]
 *    avoids by returning one algorithm's results wholesale instead of merging.
 *
 * Candidates with no authored method (every member value, and every METADATA row) pass through
 * untouched. That is what keeps T7's "no behaviour change without the artifact present" true: with
 * no lexicon loaded, [dispatch] returns its input list, same instance.
 */
class MethodDispatcher(
    /**
     * RV-32's floor: below this, a TOKENS match is not discriminative enough to auto-bind.
     *
     * Deliberately small. The floor answers one narrow question — *is this effectively a tie?* —
     * and the resolver's evidence-class gate does the actual ranking. A high floor here would
     * suppress good matches on a decision the matcher is not the right layer to make.
     */
    private val uniquenessFloor: Double = DEFAULT_UNIQUENESS_FLOOR,
) {
    private val levenshtein = Levenshtein()

    fun dispatch(
        query: String,
        results: List<FuzzyMatchResult>,
        override: MatchMethod? = null,
    ): List<FuzzyMatchResult> {
        // The override replaces the authored method on rows that HAVE one, and only those. It must
        // not impose a method on rows nobody authored: `method_override = EXACT` on a lookup round
        // would otherwise gate the entire data layer on exact equality and silently drop every
        // member candidate — a caller widening its own declared layer would narrow the estate's.
        val parsed = results.map { it to it.effectiveMethod(override) }
        if (parsed.none { (_, method) -> method != null }) return results

        val canonicalQuery = TextNormalizer.canonical(query)
        val admitted = parsed.filter { (result, method) -> admits(canonicalQuery, result, method) }
        return withUniquenessMargin(admitted)
    }

    /**
     * Recomputes the margins over an already-admitted, **merged** result set.
     *
     * [dispatch] runs per category, so its margins are category-local — and since the compiled
     * artifact keys one category per target ref, cross-target competition is invisible there by
     * construction. Any path that merges several categories into one answer (a BatchMatch span, a
     * T5 lookup) has to ask the uniqueness question again over the union, or it would report a
     * clean margin for a term that is in fact ambiguous across the very categories the caller asked
     * about — the failure T4's `LexiconArchiveSource` category convention predicted.
     *
     * Admission is not re-run: it is per-candidate and already decided.
     *
     * **Must run BEFORE the overlay is consulted.** This recomputes `autoBindable` from the margin
     * alone, so it overwrites whatever was there — including an overlay NEGATIVE's `false`. Running
     * it after [FuzzyMatcher.consultOverlay] would silently re-enable auto-binding on a candidate
     * the estate explicitly denied. Every path in [FuzzyMatcher] orders it that way, and
     * `OverlayLayerTest` pins the ordering on the merged paths specifically.
     */
    fun recomputeMargins(
        results: List<FuzzyMatchResult>,
        override: MatchMethod? = null,
    ): List<FuzzyMatchResult> =
        withUniquenessMargin(
            // Same override rule as [dispatch] — and it has to be repeated here, because a row's
            // reported `matchMethod` is always the AUTHORED one. Reading it back without the
            // override would recompute the margin over the wrong set of rows.
            results.map { it to it.effectiveMethod(override) },
        )

    /**
     * The method this call actually dispatches by: the caller's [override] where one is given, the
     * authored method otherwise — and **null stays null**.
     *
     * The override replaces a method, it never grants one: a row nobody authored has nothing to
     * override, so `method_override = EXACT` cannot reach into the data layer and gate it on exact
     * equality. A caller widening its own declared layer must not narrow the estate's.
     */
    private fun FuzzyMatchResult.effectiveMethod(override: MatchMethod?): MatchMethod? =
        authoredMethod?.let { override ?: it }

    /**
     * `null` (unauthored) and [MatchMethod.Tokens] admit everything the engine scored — TOKENS *is*
     * the engine's own algorithm, so a second opinion here would only disagree with itself.
     * EXACT and TYPOS(n) are the author's narrowing, and both compare on the **authored** form
     * ([TextNormalizer.canonical], diacritics intact) rather than the engine's fold.
     */
    private fun admits(
        canonicalQuery: String,
        result: FuzzyMatchResult,
        method: MatchMethod?,
    ): Boolean =
        when (method) {
            null, MatchMethod.Tokens -> true
            MatchMethod.Exact -> canonicalQuery == result.canonical
            // Unbounded on purpose: debatty's bounded overload *returns the limit* when the true
            // distance exceeds it, so `distance(a, b, n) <= n` is vacuously true and would admit
            // everything. Terms are short; the cap is the author's `n`, applied here.
            is MatchMethod.Typos ->
                levenshtein.distance(canonicalQuery, result.canonical) <= method.maxDistance.toDouble()
        }

    /**
     * The candidate's authored form — precomputed at load ([Candidate.canonicalValue]) for every row
     * that came off the index, normalised on the spot for one that did not (an overlay addition, a
     * hand-built test row). Only [MatchMethod.Exact] and [MatchMethod.Typos] read it.
     */
    private val FuzzyMatchResult.canonical: String
        get() = canonicalCandidate ?: TextNormalizer.canonical(candidate)

    /**
     * RV-32 — annotates each TOKENS candidate with how far its target beat the best *other* target,
     * and with the floor decision that follows.
     *
     * Identity is the target ref (falling back to the candidate id when a row somehow lacks one):
     * two aliases of one measure are one binding, not a tie, so they must not depress each other's
     * margin. Only TOKENS rows compete — see [FuzzyMatchResult.uniquenessMargin] for why the margin
     * stays inside the declared layer instead of ranking across layers.
     */
    private fun withUniquenessMargin(admitted: List<Pair<FuzzyMatchResult, MatchMethod?>>): List<FuzzyMatchResult> {
        val bestByTarget =
            admitted
                .filter { (_, method) -> method == MatchMethod.Tokens }
                .groupBy { (result, _) -> result.identity }
                .mapValues { (_, rows) -> rows.maxOf { (result, _) -> result.score } }

        if (bestByTarget.isEmpty()) return admitted.map { (result, _) -> result }

        // "The best OTHER target" needs only the top two targets, found once — the previous
        // filterKeys-per-row rebuilt the whole map for every candidate.
        val ranked = bestByTarget.entries.sortedByDescending { it.value }
        val leader = ranked[0]
        val runnerUp = ranked.getOrNull(1)?.value

        return admitted.map { (result, method) ->
            if (method != MatchMethod.Tokens) {
                result
            } else {
                val mine = bestByTarget.getValue(result.identity)
                val rival = if (result.identity == leader.key) runnerUp else leader.value
                // Unopposed ⇒ the gap is measured from zero, which clears any sane floor. That is
                // the right reading: nothing else in the layer answers to this query.
                val margin = if (rival == null) mine else mine - rival
                result.copy(uniquenessMargin = margin, autoBindable = margin >= uniquenessFloor)
            }
        }
    }

    private val FuzzyMatchResult.identity: String get() = targetRef ?: candidateId

    companion object {
        const val DEFAULT_UNIQUENESS_FLOOR: Double = 0.05
    }
}
