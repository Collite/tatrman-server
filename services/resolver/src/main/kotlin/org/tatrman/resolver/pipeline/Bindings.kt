// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.BindingProvenance
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.MatchMethod
import org.tatrman.resolver.v1.SourceTag
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.fuzzy.v1.SourceTag as FuzzySourceTag
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * RV-P2.1.T4 — one matcher candidate → one lattice [Binding]. This is the whole
 * lex-matcher → resolver translation, in one place, so the two vocabularies can be
 * compared side by side instead of being reconciled ad hoc at three call sites.
 *
 * Three things are DERIVED here rather than copied, each for a reason:
 *
 *  - **the uniform ref.** A declared row carries `target_ref`; a member row carries a data
 *    PK and no ref at all, because "a data value points at nothing but itself". The lattice
 *    needs one addressable ref per binding, so a member becomes `<category>#<candidate_id>`
 *    — the same `#` form the vocabulary side already uses (`er.branch#term-pobocka`).
 *  - **the target class of a member.** lex-matcher leaves it UNSPECIFIED on member rows for
 *    its own good reason; the resolver knows the row came out of the member index, so it
 *    says MEMBER. Nothing is invented — the layer IS the evidence.
 *  - **the evidence class** (RV-14). A first mapping, and deliberately a coarse one: it reads
 *    the layer and the score against the estate's exact threshold. ⚑ **RV-P2.2 replaces it**
 *    — "EXACT/TYPOS/TOKENS dispatch + evidence-class gate" is that list's title, and only
 *    once the core dispatches methods itself can it tell "matched the object's own name"
 *    from "matched a declared alias". Until then the two share DECLARED_ALIAS, which is the
 *    safe direction: it under-claims trust, never over-claims it.
 */
object Bindings {
    /** `TYPOS(2)` → distance 2. The matcher carries the method as this string form (RV-32). */
    private val TYPOS = Regex("""^TYPOS\((\d+)\)$""", RegexOption.IGNORE_CASE)

    fun of(
        match: FuzzyMatch,
        candidate: DomainSpanCandidate,
        thresholds: ResolverThresholds,
        snapshotHash: String,
    ): Binding {
        val isMember = match.source == FuzzySourceTag.MEMBER
        val builder =
            Binding
                .newBuilder()
                .setRef(refOf(match, isMember))
                .setTargetClass(targetClassOf(match, isMember))
                .setEvidenceClass(evidenceClassOf(match, candidate, thresholds, isMember))
                .setSource(sourceOf(match.source))
                .setInClassScore(match.score)
                .setProducer(
                    BindingProvenance
                        .newBuilder()
                        .setVocabularySource(match.source.name)
                        .setAlgorithm(match.provenance.method.ifBlank { "TATRMAN" })
                        .setScore(match.score)
                        .setSnapshotHash(snapshotHash),
                )
        if (match.hasMatchMethod()) {
            val method = match.matchMethod.trim()
            val typos = TYPOS.matchEntire(method)
            when {
                typos != null -> {
                    builder.method = MatchMethod.MATCH_METHOD_TYPOS
                    builder.maxDistance = typos.groupValues[1].toInt()
                }
                method.equals("TOKENS", ignoreCase = true) -> builder.method = MatchMethod.MATCH_METHOD_TOKENS
                method.equals("EXACT", ignoreCase = true) -> builder.method = MatchMethod.MATCH_METHOD_EXACT
                // An unknown method is left UNSPECIFIED rather than guessed: the estate authored
                // something this build does not understand, and saying so is the honest answer.
            }
        }
        if (match.hasUniquenessMargin()) builder.uniquenessMargin = match.uniquenessMargin
        if (match.hasAutoBindable()) builder.autoBindable = match.autoBindable
        return builder.build()
    }

    /** The attribute a value is attributed to: its category — the member index is keyed by it. */
    fun attributeRefOf(match: FuzzyMatch): String =
        if (match.source == FuzzySourceTag.MEMBER) match.category else match.targetRef.ifBlank { match.category }

    private fun refOf(
        match: FuzzyMatch,
        isMember: Boolean,
    ): String =
        when {
            isMember && match.candidateId.isNotBlank() -> "${match.category}#${match.candidateId}"
            match.targetRef.isNotBlank() -> match.targetRef
            else -> match.category
        }

    private fun targetClassOf(
        match: FuzzyMatch,
        isMember: Boolean,
    ): TargetClass =
        when {
            match.targetClass != FuzzyTargetClass.TARGET_CLASS_UNSPECIFIED ->
                TargetClass.forNumber(match.targetClass.number) ?: TargetClass.TARGET_CLASS_UNSPECIFIED
            isMember -> TargetClass.TARGET_CLASS_MEMBER
            else -> TargetClass.TARGET_CLASS_UNSPECIFIED
        }

    private fun sourceOf(source: FuzzySourceTag): SourceTag =
        when (source) {
            FuzzySourceTag.MEMBER -> SourceTag.SOURCE_TAG_DATA
            FuzzySourceTag.DECLARED -> SourceTag.SOURCE_TAG_DECLARED
            FuzzySourceTag.METADATA -> SourceTag.SOURCE_TAG_METADATA
            FuzzySourceTag.LEARNED -> SourceTag.SOURCE_TAG_LEARNED
            FuzzySourceTag.VOCABULARY -> SourceTag.SOURCE_TAG_VOCABULARY
            else -> SourceTag.SOURCE_TAG_UNSPECIFIED
        }

    private fun evidenceClassOf(
        match: FuzzyMatch,
        candidate: DomainSpanCandidate,
        thresholds: ResolverThresholds,
        isMember: Boolean,
    ): EvidenceClass {
        val exact = match.score >= thresholds.exact
        return when {
            match.score < thresholds.bind -> EvidenceClass.EVIDENCE_CLASS_WEAK
            exact && isMember -> EvidenceClass.EVIDENCE_CLASS_EXACT
            exact && match.source == FuzzySourceTag.LEARNED -> EvidenceClass.EVIDENCE_CLASS_LEARNED_ALIAS
            exact -> EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
            candidate.anchored -> EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG
            else -> EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG
        }
    }

    /**
     * The RV-14 rank: 1 is strongest, and UNSPECIFIED sorts LAST rather than first. Proto3
     * gives the zero value to UNSPECIFIED, so a naive comparison on `number` would rank
     * "we don't know" above EXACT — the one ordering that must never happen.
     */
    fun rank(evidenceClass: EvidenceClass): Int =
        if (evidenceClass == EvidenceClass.EVIDENCE_CLASS_UNSPECIFIED) Int.MAX_VALUE else evidenceClass.number
}
