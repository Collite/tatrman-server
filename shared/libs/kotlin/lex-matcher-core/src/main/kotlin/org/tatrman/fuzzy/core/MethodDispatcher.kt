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
    ): List<FuzzyMatchResult> {
        val parsed = results.map { it to MatchMethod.parse(it.matchMethod) }
        if (parsed.none { (_, method) -> method != null }) return results

        val canonicalQuery = TextNormalizer.canonical(query)
        val admitted = parsed.filter { (result, method) -> admits(canonicalQuery, result, method) }
        return withUniquenessMargin(admitted)
    }

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
            MatchMethod.Exact -> canonicalQuery == TextNormalizer.canonical(result.candidate)
            // Unbounded on purpose: debatty's bounded overload *returns the limit* when the true
            // distance exceeds it, so `distance(a, b, n) <= n` is vacuously true and would admit
            // everything. Terms are short; the cap is the author's `n`, applied here.
            is MatchMethod.Typos ->
                levenshtein.distance(canonicalQuery, TextNormalizer.canonical(result.candidate)) <=
                    method.maxDistance.toDouble()
        }

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

        return admitted.map { (result, method) ->
            if (method != MatchMethod.Tokens) {
                result
            } else {
                val mine = bestByTarget.getValue(result.identity)
                val rival = bestByTarget.filterKeys { it != result.identity }.values.maxOrNull()
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
