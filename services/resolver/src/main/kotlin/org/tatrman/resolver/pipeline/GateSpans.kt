// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.SpanQuery
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds

/**
 * gateSpans (RG-P5.S1.T4) — the heart. All proposed spans go out in ONE
 * `BatchMatch` (never per-span RPCs — B-T1's point); the response comes back
 * positional to the request; this turns it into bindings or a clarification.
 *
 * Determinism rules, ported from the live ENTITIES_ONLY config:
 *  - a match binds only at score ≥ [ResolverThresholds.bind] (0.5);
 *  - an exact match (≥ [ResolverThresholds.exact], 0.9999) dominates the
 *    sub-exact field — a near name below `exact` is dropped, which is how a short
 *    exact code (`FAP`) separates from a near name; but two DISTINCT exact matches
 *    are a genuine tie and still clarify (refuse over guess);
 *  - otherwise contenders within [ResolverThresholds.ambiguityGap] (0.05) of the
 *    top are compared by IDENTITY (MEMBER → resolved_id, VOCABULARY → target_ref):
 *    one identity ⇒ bind; multiple ⇒ instance ambiguity ⇒ clarify (refuse over
 *    guess, RS-26), capped at [ResolverThresholds.maxOptions] (20);
 *  - the same resolved id reached via two spans dedupes to one binding;
 *  - a MEMBER value on a KOD/NAZEV column also points at its sibling column
 *    (Q-20 sibling-column expansion — a catalog lookup).
 */
object GateSpans {
    /** Build the single `BatchMatch` request: one `SpanQuery` per candidate, positional. */
    fun buildBatchRequest(
        candidates: List<DomainSpanCandidate>,
        locale: String?,
        perSpanLimit: Int,
    ): BatchMatchRequest {
        val builder = BatchMatchRequest.newBuilder()
        for (c in candidates) {
            builder.addSpans(
                SpanQuery
                    .newBuilder()
                    .setQuery(c.text)
                    .addAllCategories(c.categories)
                    .setLimit(perSpanLimit)
                    .build(),
            )
        }
        if (!locale.isNullOrBlank()) builder.locale = locale
        return builder.build()
    }

    fun gate(
        candidates: List<DomainSpanCandidate>,
        response: BatchMatchResponse,
        entityTypes: List<ResolverEntityType>,
        thresholds: ResolverThresholds,
        siblings: SiblingCatalog,
        snapshotHash: String,
    ): GateOutcome {
        val bindings = mutableListOf<DomainBinding>()
        val options = mutableListOf<ClarificationOption>()

        candidates.forEachIndexed { i, cand ->
            val result = response.resultsList.getOrNull(i) ?: return@forEachIndexed
            val matches =
                result.matchesList
                    .filter { it.score >= thresholds.bind }
                    .sortedByDescending { it.score }
            if (matches.isEmpty()) return@forEachIndexed

            val top = matches.first()
            val contenders =
                if (top.score >= thresholds.exact) {
                    // Exact dominance: a near-name below `exact` is excluded (that's how a
                    // short exact code separates from a similar name). But two DISTINCT
                    // exact matches are a genuine tie — keep them both so the identity
                    // check below can surface it as a clarification, not a silent guess.
                    matches.filter { it.score >= thresholds.exact }
                } else {
                    matches.filter { top.score - it.score <= thresholds.ambiguityGap }
                }

            val identities = contenders.map { identityKey(it) }.distinct()
            if (identities.size > 1) {
                // instance ambiguity — offer the distinct contenders, don't bind. Each
                // option is attributed to THIS span and this span's options are capped
                // independently, so a second ambiguous span can never be silently dropped
                // by a global truncation (RG-P6 review M).
                contenders
                    .distinctBy { identityKey(it) }
                    .take(thresholds.maxOptions)
                    .forEach { options += toOption(it, cand, entityTypes) }
            } else {
                bindings += toBinding(cand, top, entityTypes, siblings, snapshotHash)
            }
        }

        // NOTE: no global re-truncation here — each span's options are already capped
        // at maxOptions above; a flat `options.take(maxOptions)` would drop later
        // spans wholesale (RG-P6 review M). Full multi-span RESUME (returning the
        // already-bound spans alongside a pin) remains a tracked design item.
        if (options.isNotEmpty()) return Clarify(options)

        val deduped = dedupeByIdentity(bindings)
        return Bound(deduped, confidence = deduped.minOfOrNull { it.score } ?: 0.0)
    }

    // --- helpers ------------------------------------------------------------

    private fun identityKey(m: FuzzyMatch): String =
        if (m.source == SourceTag.MEMBER) "M:${m.candidateId}" else "V:${m.targetRef}"

    /** The declared entity type owning a match's fuzzy category, or the category itself. */
    private fun entityRefOf(
        m: FuzzyMatch,
        entityTypes: List<ResolverEntityType>,
    ): String = entityTypes.firstOrNull { m.category in it.categories }?.ref ?: m.category

    private fun toBinding(
        cand: DomainSpanCandidate,
        top: FuzzyMatch,
        entityTypes: List<ResolverEntityType>,
        siblings: SiblingCatalog,
        snapshotHash: String,
    ): DomainBinding {
        val isMember = top.source == SourceTag.MEMBER
        val entityRef = entityRefOf(top, entityTypes)
        return DomainBinding(
            span = cand,
            entityTypeRef = entityRef,
            rawText = cand.text,
            vocabularySource = top.source.name,
            resolvedId = if (isMember) top.candidateId else null,
            resolvedLabel = top.candidate,
            targetRef = if (!isMember && top.targetRef.isNotBlank()) top.targetRef else null,
            siblingRefs = siblings[top.category].orEmpty(),
            score = top.score,
            algorithm = top.provenance.method.ifBlank { "TATRMAN" },
            snapshotHash = snapshotHash,
        )
    }

    private fun toOption(
        m: FuzzyMatch,
        cand: DomainSpanCandidate,
        entityTypes: List<ResolverEntityType>,
    ): ClarificationOption {
        val isMember = m.source == SourceTag.MEMBER
        return ClarificationOption(
            id = identityKey(m),
            label = m.candidate,
            resolvedId = if (isMember) m.candidateId else null,
            targetRef = if (!isMember && m.targetRef.isNotBlank()) m.targetRef else null,
            entityTypeRef = entityRefOf(m, entityTypes),
            spanStart = cand.start,
            spanEnd = cand.end,
            spanText = cand.text,
        )
    }

    /** Same resolved id (MEMBER) or same target_ref (VOCABULARY) → one binding (highest score). */
    private fun dedupeByIdentity(bindings: List<DomainBinding>): List<DomainBinding> {
        val best = LinkedHashMap<String, DomainBinding>()
        for (b in bindings) {
            val key =
                b.resolvedId?.let { "M:$it" } ?: b.targetRef?.let { "V:$it" } ?: "S:${b.entityTypeRef}:${b.rawText}"
            val existing = best[key]
            if (existing == null || b.score > existing.score) best[key] = b
        }
        return best.values.toList()
    }
}
