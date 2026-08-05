// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.v1.EvidenceClass

/**
 * RV-P2.2.T3 — **the binder**. The one place in this service where a candidate becomes a binding.
 *
 * Every producer's output passes here — the broad pass's `BatchMatch` (T5), the P2.3 lookup rounds,
 * the P2.4 re-gate's hypotheses — and each of them is a *proposer* (RV-7). None of them binds. That
 * is not a convention: `GateSpans` no longer contains a selection rule, and there is deliberately
 * no second function anywhere that takes a list of candidates and returns a winner.
 *
 * The decision is RV-14's lexicographic order:
 *
 *   `EXACT > DECLARED_ALIAS > LEARNED_ALIAS > ANCHORED_FUZZY_STRONG > UNANCHORED_FUZZY_STRONG > WEAK`
 *
 * and the whole of it reduces to one sentence — **a score compares only within a class**. Three
 * consequences the tests pin separately, because each removes a different failure the pre-RV
 * resolver had:
 *
 *  - **WEAK never binds**, whatever it scored, and is never offered as a clarification option
 *    either. issues.md's *středisko* rows at 0.667 do not become a binding and do not become a
 *    question; they become a typed gap, which is the honest thing the era before this had no way
 *    to say. A whole field of WEAK is a G1/G3, not a menu of guesses.
 *  - **the top class wins outright.** A 0.99 in a lower class loses to a 0.62 above it. This is
 *    what the old exact-dominance special case was reaching for — it filtered the sub-exact field
 *    away when an exact hit was present — generalized to every rung of the order, so it no longer
 *    needs to be a special case at all.
 *  - **a same-class tie is a refusal.** Two distinct identities inside [ResolverThresholds.ambiguityGap]
 *    of the top score bind nothing (RS-26); the door renders that as a clarification and the
 *    lattice as a mention with several bindings plus a G2.
 *
 * The verdict keeps [Verdict.rejected] as well as [Verdict.admitted] because they go to different
 * places: admitted candidates are the lattice's bindings, rejected ones are the round's log
 * (P2.3.T5 — "rejected candidates appear in the round's log, not in the lattice"). Nothing is
 * silently dropped; it is dropped *somewhere legible*.
 */
object Binder {
    /** A matcher row and the class the core derived for it — the gate's unit of comparison. */
    data class ClassedMatch(
        val match: FuzzyMatch,
        val evidenceClass: EvidenceClass,
    )

    /** What the gate decided for ONE span. */
    sealed interface Verdict {
        /** The candidates that survived — what the lattice records. Empty ⇒ nothing was admitted. */
        val admitted: List<ClassedMatch>

        /** Everything the gate refused, with the class that refused it. For the rung log. */
        val rejected: List<ClassedMatch>
    }

    /** One identity in the top class: it binds. */
    data class Bind(
        val winner: ClassedMatch,
        override val admitted: List<ClassedMatch>,
        override val rejected: List<ClassedMatch>,
    ) : Verdict

    /** Several distinct identities, same class, inside the tie band: ask, don't guess (RS-26). */
    data class Ambiguous(
        override val admitted: List<ClassedMatch>,
        override val rejected: List<ClassedMatch>,
    ) : Verdict

    /** Nothing reached the class floor — a first-class unknown, typed by [Gaps] as G1/G3/G4. */
    data class NoBind(
        override val rejected: List<ClassedMatch>,
    ) : Verdict {
        override val admitted: List<ClassedMatch> get() = emptyList()
    }

    /**
     * Classify then decide — the entry point every producer uses.
     *
     * [candidate] supplies the anchoring context (and nothing else): whether span proposal scoped
     * this lookup to an entity the user named, which is the only difference between the two
     * `*_FUZZY_STRONG` classes.
     */
    fun gate(
        matches: List<FuzzyMatch>,
        candidate: DomainSpanCandidate,
        thresholds: ResolverThresholds,
    ): Verdict = decide(classify(matches, candidate, thresholds), thresholds)

    fun classify(
        matches: List<FuzzyMatch>,
        candidate: DomainSpanCandidate,
        thresholds: ResolverThresholds,
    ): List<ClassedMatch> = matches.map { ClassedMatch(it, EvidenceClasses.of(it, candidate.anchored, thresholds)) }

    /** The pure ordering decision — see the class doc. */
    fun decide(
        classed: List<ClassedMatch>,
        thresholds: ResolverThresholds,
    ): Verdict {
        val rejected = mutableListOf<ClassedMatch>()
        val eligible = mutableListOf<ClassedMatch>()
        // RV-14, and it is the first thing that happens rather than a filter somewhere downstream:
        // a WEAK candidate is not a weaker binding, it is not a binding.
        for (c in classed) {
            if (c.evidenceClass == EvidenceClass.EVIDENCE_CLASS_WEAK) rejected += c else eligible += c
        }
        if (eligible.isEmpty()) return NoBind(rejected)

        val topRank = eligible.minOf { EvidenceClasses.rank(it.evidenceClass) }
        val (inTopClass, belowTopClass) = eligible.partition { EvidenceClasses.rank(it.evidenceClass) == topRank }
        rejected += belowTopClass

        // Scores are comparable HERE and only here — one class, so one scale.
        val ranked = inTopClass.sortedByDescending { it.match.score }
        val top = ranked.first()
        val (contenders, outOfBand) = ranked.partition { top.match.score - it.match.score <= thresholds.ambiguityGap }
        rejected += outOfBand

        // The same identity reached twice (two spans, two categories, one target) is one candidate,
        // and the stronger reading of it speaks — never an ambiguity with itself.
        val admitted = contenders.distinctBy { identityKey(it.match) }
        return if (admitted.size > 1) {
            Ambiguous(admitted, rejected)
        } else {
            Bind(admitted.single(), admitted, rejected)
        }
    }

    /**
     * What makes two candidates the same THING: a member is its data PK, anything else is its
     * declared target ref. Two rows that agree here are one answer reached twice, not a tie.
     */
    fun identityKey(m: FuzzyMatch): String =
        if (m.source == SourceTag.MEMBER) "M:${m.candidateId}" else "V:${m.targetRef}"
}
