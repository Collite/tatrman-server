// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.v1.EvidenceClass

/**
 * RV-P2.2.T4 — one matcher row → its RV-14 evidence class.
 *
 * P2.1 shipped a placeholder here and said so: it read the layer and the score against the exact
 * threshold, and left a ⚑ saying "RV-P2.2 replaces it — only once the core dispatches methods
 * itself can it tell an exact hit from a declared alias". This is that replacement. What changed
 * is not the arithmetic but the **inputs**: a P1.4 row carries the method the ESTATE authored for
 * it (RV-32), and the class now reads that first.
 *
 * Three facts decide a class, in this order of authority:
 *
 *  1. **The authored method.** `EXACT` means the estate said this term matches exactly and the
 *     matcher found that it did. Nothing about a score can improve on that or undermine it.
 *  2. **The layer.** DECLARED/METADATA is what the estate wrote down, LEARNED is what it inferred
 *     from one confirmation (RV-20 — promotion to the lexicon is what raises the class), MEMBER
 *     is data that nobody authored at all.
 *  3. **The anchor**, on the data layer only — whether span proposal scoped this lookup to an
 *     entity the user actually named. See [DomainSpanCandidate.anchored]: it is set by
 *     `SpanProposal` for anchored phrases, for values governed by an anchor, and for literal runs
 *     scoped by the nearest mention (`účtu` → `501001`); it is false for a bare proper-noun run,
 *     a NER span and the parse-less n-gram floor, all of which were gated against *every*
 *     declared type. So the ANCHORED/UNANCHORED distinction is not a new predicate — it is the
 *     Q-20 precision mechanism, read back out at classification time.
 *
 * **Where the class floor applies, and where it must not.** [ResolverThresholds.strong] separates
 * `*_FUZZY_STRONG` from WEAK, and it is applied to the data layer ONLY. A member row is raw
 * surface similarity with nothing behind it — that is where issues.md's `501001`→*5AU 5001* at
 * 0.667 lives, and the floor is what kills it. A declared `TYPOS(1)` row is the opposite case: the
 * estate authored the rule, the matcher enforced the edit bound, and the score is a similarity
 * readout, not a decision. Applying a similarity floor there would silently overrule the estate's
 * own rule — and it would bite hardest on short Czech anchor words, where a single edit
 * (`rok`~`roce`) costs proportionally far more score than the same edit on a long one.
 *
 * The one signal that outranks everything, including an authored method: a TOKENS row the matcher
 * itself reported **non-discriminative** (`auto_bindable = false`, contractual per RV-32). The
 * producer is saying "several targets match this equally well"; the honest class for that is WEAK.
 * Note the consequence, because it is deliberate — WEAK never binds AND is never offered as a
 * clarification option, so a non-discriminative TOKENS field becomes a gap rather than a menu.
 * `auto_bindable` ABSENT is a different statement ("no uniqueness decision applies") and demotes
 * nothing; the field is `optional` on the wire for precisely this distinction.
 */
object EvidenceClasses {
    fun of(
        match: FuzzyMatch,
        anchored: Boolean,
        thresholds: ResolverThresholds,
    ): EvidenceClass {
        // The matcher's own floor. Below it nothing is evidence, whoever authored it.
        if (match.score < thresholds.bind) return EvidenceClass.EVIDENCE_CLASS_WEAK
        // The producer's uniqueness verdict, and it outranks the estate's method: an estate can
        // author TOKENS on a term, but only the matcher can see how many targets it reaches.
        if (match.hasAutoBindable() && !match.autoBindable) return EvidenceClass.EVIDENCE_CLASS_WEAK

        val method = if (match.hasMatchMethod()) match.matchMethod.trim() else ""
        val isMember = match.source == SourceTag.MEMBER
        return when {
            // 1 — the estate's own rule, matched exactly (RV-32: "EXACT hit → EXACT class").
            method.equals("EXACT", ignoreCase = true) -> EvidenceClass.EVIDENCE_CLASS_EXACT
            // A data value that matched itself. No alias vouches for a PK and none needs to: the
            // value IS the thing. Restricted to the data layer on purpose — a declared row with no
            // authored method at all (a pre-0.12 snapshot) keeps the under-claiming P2.1 reading
            // below rather than being handed the strongest class in the order for a 1.0 score.
            isMember && match.score >= thresholds.exact -> EvidenceClass.EVIDENCE_CLASS_EXACT
            // 2 — the layer. Learned sits below declared (RV-20): one confirmation activates an
            // overlay entry, only promotion into the lexicon raises its class.
            match.source == SourceTag.LEARNED -> EvidenceClass.EVIDENCE_CLASS_LEARNED_ALIAS
            !isMember -> EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
            // 3 — the data layer, where the class floor is the only thing standing between a
            // similarity readout and a binding.
            match.score < thresholds.strong -> EvidenceClass.EVIDENCE_CLASS_WEAK
            anchored -> EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG
            else -> EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG
        }
    }

    /**
     * The RV-14 rank: 1 is strongest, and UNSPECIFIED sorts LAST rather than first. Proto3 gives
     * the zero value to UNSPECIFIED, so a naive comparison on `number` would rank "we don't know"
     * above EXACT — the one ordering that must never happen.
     */
    fun rank(evidenceClass: EvidenceClass): Int =
        if (evidenceClass == EvidenceClass.EVIDENCE_CLASS_UNSPECIFIED) Int.MAX_VALUE else evidenceClass.number
}
