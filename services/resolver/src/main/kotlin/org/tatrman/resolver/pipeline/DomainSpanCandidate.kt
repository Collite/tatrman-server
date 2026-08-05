// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

/**
 * A proposed domain span (RG-P5, Q-20 anchored proposal) — a stretch of the
 * surface text the resolver will gate against declared vocabulary. Produced by
 * [SpanProposal.proposeDomainSpans], consumed by gateSpans as one `BatchMatch`
 * slot each.
 *
 * @property text the surface phrase (token-joined) that becomes the `SpanQuery.query`
 * @property start 0-indexed char offset of the phrase in the source text
 * @property end exclusive char offset
 * @property gatedEntityRefs the entity type(s) this span is matched against — for
 *   an anchored candidate, ONLY the anchor's entity (the precision mechanism, Q-20
 *   config C); for a proper-noun/floor candidate, every declared type
 * @property categories the union of fuzzy categories from [gatedEntityRefs]
 * @property anchored true = dep-parse-anchored to a declared entity anchor word;
 *   false = a proper-noun argument or the parse-less n-gram floor (R4-γ)
 * @property origin which deterministic source proposed it (RV-P2.1). It decides which
 *   LAYER of the lattice the span lands in — an anchor phrase is a mention of a model
 *   object; everything else is a literal the model may attribute (RV-2's two layers).
 *   The gate does not read it; [LatticeAssembler] does.
 * @property headToken 0-indexed token heading the phrase, or -1 when there is no parse
 * @property lemma the head token's lemma (a mention's dictionary form)
 * @property anchorHeadToken RV-P2.1, [Origin.LITERAL] only: the head token of the mention
 *   whose categories scoped this literal's lookup — "the user said *account*, so check
 *   501001 against account.code first" (RV-33). -1 when nothing scoped it.
 */
data class DomainSpanCandidate(
    val text: String,
    val start: Int,
    val end: Int,
    val gatedEntityRefs: List<String>,
    val categories: List<String>,
    val anchored: Boolean,
    val origin: Origin = Origin.ANCHOR_PHRASE,
    val headToken: Int = -1,
    val lemma: String = "",
    val anchorHeadToken: Int = -1,
) {
    /** Where a candidate came from — see [DomainSpanCandidate.origin]. */
    enum class Origin {
        /** A declared anchor word's own nominal phrase: a MENTION of that model object. */
        ANCHOR_PHRASE,

        /** A nominal argument governed by an anchor word (`středisko DF ADNAK`): a value. */
        GOVERNED_VALUE,

        /** A proper-noun run not already anchored: a value. */
        PROPER_NOUN,

        /** A NER span the classifier does not type as universal (`op` products): a value. */
        NER_ENTITY,

        /** A code/number run scoped by an adjacent mention (RV-P2.1): a value. */
        LITERAL,

        /** The parse-less n-gram floor (R4-γ): a guess, and the lattice says so. */
        NGRAM_FLOOR,
    }
}
