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
        owners: Map<String, String> = emptyMap(),
    ): Verdict = decide(classify(matches, candidate, thresholds), thresholds, owners)

    fun classify(
        matches: List<FuzzyMatch>,
        candidate: DomainSpanCandidate,
        thresholds: ResolverThresholds,
    ): List<ClassedMatch> = matches.map { ClassedMatch(it, EvidenceClasses.of(it, candidate.anchored, thresholds)) }

    /** The pure ordering decision — see the class doc. */
    fun decide(
        classed: List<ClassedMatch>,
        thresholds: ResolverThresholds,
        owners: Map<String, String> = emptyMap(),
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
        val distinct = contenders.distinctBy { identityKey(it.match) }

        // MS-P3.S2 — the declared-containment collapse (contracts §8.3, design.md §10.2 amendment 2).
        //
        // An entity tied with its OWN attribute is one answer reached at two granularities, not a
        // tie: `tržby` declared for both `er.entity.sales` and its measure `amount_czk` used to
        // reach here as two `V:` identities inside the band and leave as a G2 — asking the user to
        // choose between an entity and its own measure, a question no user can parse. The attribute
        // speaks: it is the more specific object, and the entity reading stays recoverable through
        // the owner ref it declared.
        //
        // This is the same kind of rule as the `distinctBy` above it — "one answer reached twice" —
        // and deliberately no more than that. It fires ONLY on a declared containment relation
        // between two `V:` identities that are already in the top class and already inside the tie
        // band: no class is compared across, no score is weighed, WEAK is long gone, and a genuine
        // ambiguity (two siblings, or two unrelated objects) still refuses. `owners` empty — the
        // pre-v3 estate, and every estate that declared no mention facet — is byte-identical.
        //
        // ⛔ The containment is DECLARED data, threaded in from the registry. It is never parsed
        // out of a ref string here: `er.entity.sales.amount_czk` looking like a child of
        // `er.entity.sales` is a spelling coincidence this service is not permitted to read.
        val ownedRefs =
            distinct
                .filter { isModelObject(it) }
                .mapNotNull { owners[it.match.targetRef]?.takeIf { ref -> ref.isNotBlank() } }
                .toSet()
        val (collapsed, survived) =
            distinct.partition { isModelObject(it) && it.match.targetRef in ownedRefs }
        // A containment CYCLE would collapse everything and leave nothing to bind — `{a owns b,
        // b owns a}`, or a ref declared as its own owner. Neither can arise from a model (an
        // attribute's owner is an entity, and entities own nothing), but `owners` is data this
        // service did not produce, so the collapse declines rather than throwing on `single()`.
        //
        // ⚠ It declines by yielding the UN-collapsed set to the ordinary size check below, not by
        // returning a verdict of its own (review-084 F3). Returning `Ambiguous` here made
        // `Ambiguous` reachable with ONE admitted candidate — an invariant this class had held
        // since RV-14 — and `GateSpans.outcomeOf` renders any ambiguous span by offering its
        // contenders, so a single self-owning row came back as a clarification with one option.
        // Asking the user to choose between one thing is the failure the containment collapse
        // exists to remove; producing it from malformed data is no better than producing it from
        // good data.
        val admitted =
            if (survived.isEmpty()) {
                distinct
            } else {
                // Nothing is silently dropped — the owner rides the rung log like every other refusal.
                rejected += collapsed
                survived
            }

        return if (admitted.size > 1) {
            Ambiguous(admitted, rejected)
        } else {
            Bind(admitted.single(), admitted, rejected)
        }
    }

    /**
     * A `V:` identity — a declared model object rather than a data row.
     *
     * The containment collapse touches these and only these: `M:` rows are data values, and one
     * value being stored in a table the other names is not the same relation at all.
     */
    private fun isModelObject(c: ClassedMatch): Boolean = c.match.source != SourceTag.MEMBER

    /**
     * What makes two candidates the same THING: a member is its data PK, anything else is its
     * declared target ref. Two rows that agree here are one answer reached twice, not a tie.
     */
    fun identityKey(m: FuzzyMatch): String =
        if (m.source == SourceTag.MEMBER) "M:${m.candidateId}" else "V:${m.targetRef}"
}
