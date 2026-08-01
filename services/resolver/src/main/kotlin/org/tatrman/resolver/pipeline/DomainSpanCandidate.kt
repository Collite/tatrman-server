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
 */
data class DomainSpanCandidate(
    val text: String,
    val start: Int,
    val end: Int,
    val gatedEntityRefs: List<String>,
    val categories: List<String>,
    val anchored: Boolean,
)
