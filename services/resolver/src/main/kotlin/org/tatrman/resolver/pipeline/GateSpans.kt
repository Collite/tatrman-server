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
 * **RV-P2.2 moved the selection rule out of this file.** What used to live here — a bind floor, an
 * exact-dominance special case, a tie band over the whole contender field — is now [Binder], and
 * the three of them turned out to be one rule badly separated: RV-14's class order. This object
 * kept everything that is genuinely about *spans* (build one batch, read it positionally, expand
 * siblings, dedupe across spans, render options) and delegates the one question "which of these
 * candidates, if any" to the binder, which is the same call the P2.3 lookup rounds and the P2.4
 * re-gate make. There is deliberately no second selection rule in this service.
 *
 * What remains here, unchanged from RG:
 *  - the same resolved id reached via two spans dedupes to one binding;
 *  - a MEMBER value on a KOD/NAZEV column also points at its sibling column
 *    (Q-20 sibling-column expansion — a catalog lookup);
 *  - a span's clarification options are capped at [ResolverThresholds.maxOptions] (20)
 *    independently, so a second ambiguous span can never be dropped by a global truncation.
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
        // Every candidate, matched or not — the lattice's raw material (RV-P2.1). A span with
        // no surviving contender is recorded here as an empty one; it is a first-class unknown,
        // not an absence.
        val gated = mutableListOf<GatedSpan>()

        candidates.forEachIndexed { i, cand ->
            // The ONE decision, made in the one place that makes it (RV-P2.2). Note what is NOT
            // filtered before the call: the bind floor is the binder's too, because a candidate
            // the gate refused is still something the rung log should be able to name.
            val verdict =
                Binder.gate(
                    response.resultsList
                        .getOrNull(i)
                        ?.matchesList
                        .orEmpty(),
                    cand,
                    thresholds,
                )
            gated += GatedSpan(cand, verdict.admitted, ambiguous = verdict is Binder.Ambiguous)
            when (verdict) {
                is Binder.NoBind -> Unit
                is Binder.Ambiguous ->
                    // instance ambiguity — offer the distinct contenders, don't bind. Each option
                    // is attributed to THIS span and this span's options are capped independently
                    // (RG-P6 review M).
                    verdict.admitted
                        .take(thresholds.maxOptions)
                        .forEach { options += toOption(it.match, cand, entityTypes) }
                is Binder.Bind ->
                    bindings += toBinding(cand, verdict.winner.match, entityTypes, siblings, snapshotHash)
            }
        }

        // NOTE: no global re-truncation here — each span's options are already capped
        // at maxOptions above; a flat `options.take(maxOptions)` would drop later
        // spans wholesale (RG-P6 review M). Full multi-span RESUME (returning the
        // already-bound spans alongside a pin) remains a tracked design item.
        if (options.isNotEmpty()) return Clarify(options, gated)

        val deduped = dedupeByIdentity(bindings)
        return Bound(deduped, confidence = deduped.minOfOrNull { it.score } ?: 0.0, gated = gated)
    }

    // --- helpers ------------------------------------------------------------

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
            id = Binder.identityKey(m),
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
