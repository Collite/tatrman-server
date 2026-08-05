// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.v1.Attribution
import org.tatrman.resolver.v1.Grounding
import org.tatrman.resolver.v1.LexiconVersions
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.RungLogEntry
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.ValueFinding
import org.tatrman.resolver.v1.ValueKind

/**
 * RV-P2.1.T4 — the annotation lattice (contracts §1), assembled from what the deterministic
 * core actually computed. Nothing here invents evidence: every binding comes from a matcher
 * candidate that survived the gate, every value from a proposed span, every grounding from the
 * universal extraction. Where a concept was missing it was added to the internal model
 * ([DomainSpanCandidate.origin], [GatedSpan], [ResolverEntityType.objectKind]) rather than
 * faked at this boundary.
 *
 * The two layers are RV-2's: **mentions** are content nominal phrases (what the question talks
 * about, bound to model objects and operators), **values** are literals, proper names and
 * grounded spans (what the question restricts BY). A span's layer is decided by which
 * deterministic source proposed it — never by what happened to match, so a mention that binds
 * nothing is still a mention, and that is what makes G1 expressible at all.
 */
object LatticeAssembler {
    fun assemble(
        parse: AnalyzeResponse,
        gate: GateOutcome,
        ungatedMentions: List<DomainSpanCandidate>,
        universals: List<UniversalBinding>,
        thresholds: ResolverThresholds,
        snapshotHash: String,
        batch: BatchMatchResponse,
    ): ResolutionState {
        val gatedByLayer = gate.gated.groupBy { layerOf(it) }
        val mentionSpans =
            (gatedByLayer[Layer.MENTION].orEmpty() + ungatedMentions.map { GatedSpan(it, emptyList(), false) })
                .sortedWith(compareBy({ it.candidate.start }, { it.candidate.end }))
        val valueSpans = gatedByLayer[Layer.VALUE].orEmpty().sortedWith(compareBy({ it.candidate.start }))

        val mentions =
            mentionSpans.mapIndexed { i, span ->
                val builder =
                    Mention
                        .newBuilder()
                        .setId("m${i + 1}")
                        .setSpan(span(span.candidate.start, span.candidate.end, span.candidate.text))
                        .setLemma(span.candidate.lemma)
                for (match in span.contenders) {
                    builder.addBindings(Bindings.of(match, span.candidate, thresholds, snapshotHash))
                }
                builder.build()
            }
        // Which mention a literal's scope came from — resolvable only now that ids exist. An
        // UNBOUND mention is not an anchor: it has no categories to lend, and that distinction
        // is what separates a scoped miss (G4) from an unscoped one (G3).
        val mentionIdByHead =
            mentionSpans
                .mapIndexed { i, span -> span to "m${i + 1}" }
                .filter { (span, _) -> span.candidate.headToken >= 0 && span.contenders.isNotEmpty() }
                .associate { (span, id) -> span.candidate.headToken to id }

        val gatedValues =
            valueSpans.map { span ->
                val builder =
                    ValueFinding
                        .newBuilder()
                        .setSpan(span(span.candidate.start, span.candidate.end, span.candidate.text))
                        .setKind(ValueKind.VALUE_KIND_LITERAL)
                mentionIdByHead[span.candidate.anchorHeadToken]?.let { builder.anchorMentionId = it }
                for (match in span.contenders) {
                    builder.addAttributions(
                        Attribution
                            .newBuilder()
                            .setAttributeRef(Bindings.attributeRefOf(match))
                            .setBinding(Bindings.of(match, span.candidate, thresholds, snapshotHash)),
                    )
                }
                builder
            }
        val groundedValues =
            universals.map { universal ->
                ValueFinding
                    .newBuilder()
                    .setSpan(span(universal.start, universal.end, universal.text))
                    .setKind(ValueKind.VALUE_KIND_GROUNDED)
                    .setGrounding(
                        Grounding
                            .newBuilder()
                            .setKernel(universal.sourceEngine)
                            .setKind(universal.entityType.name)
                            .setNormalizedValue(universal.normalizedValue),
                    )
            }
        val values =
            (gatedValues + groundedValues)
                .sortedWith(compareBy({ it.span.start }, { it.span.end }))
                .mapIndexed { i, builder -> builder.setId("v${i + 1}").build() }

        val builder =
            ResolutionState
                .newBuilder()
                .setParse(parse)
                .addAllMentions(mentions)
                .addAllValues(values)
                .setLexiconVersions(lexiconVersions(batch))
        builder.addRungLog(
            RungLogEntry
                .newBuilder()
                .setRung(CORE_RUNG)
                .setAction("annotate")
                .setBindingsAdded(mentions.sumOf { it.bindingsCount } + values.sumOf { it.attributionsCount }),
        )
        return builder.build()
    }

    /** The rung name the core writes for its own deterministic pass (contracts §3 vocabulary). */
    const val CORE_RUNG: String = "core"

    private enum class Layer { MENTION, VALUE, DROPPED }

    private fun layerOf(span: GatedSpan): Layer =
        when (span.candidate.origin) {
            DomainSpanCandidate.Origin.ANCHOR_PHRASE -> Layer.MENTION
            DomainSpanCandidate.Origin.GOVERNED_VALUE,
            DomainSpanCandidate.Origin.PROPER_NOUN,
            DomainSpanCandidate.Origin.NER_ENTITY,
            DomainSpanCandidate.Origin.LITERAL,
            -> Layer.VALUE
            // The parse-less n-gram floor guesses spans; a floor guess that matched is worth
            // reporting, one that did not is noise, and the honest record of the whole situation
            // is the G5 degrade gap rather than a mention layer built on no syntax.
            DomainSpanCandidate.Origin.NGRAM_FLOOR ->
                if (span.contenders.isEmpty()) Layer.DROPPED else Layer.MENTION
        }

    private fun lexiconVersions(batch: BatchMatchResponse): LexiconVersions {
        val layers =
            batch.resultsList
                .firstOrNull { it.hasLayerVersions() }
                ?.layerVersions
                ?: return LexiconVersions.getDefaultInstance()
        val builder =
            LexiconVersions
                .newBuilder()
                .setLexiconArtifactHash(layers.lexiconArtifactHash)
                .putAllMemberIndexVersions(layers.memberIndexVersionsMap)
        if (layers.hasOverlayVersion()) builder.overlayVersion = layers.overlayVersion
        return builder.build()
    }

    private fun span(
        start: Int,
        end: Int,
        text: String,
    ): Span =
        Span
            .newBuilder()
            .setStart(start)
            .setEnd(end)
            .setText(text)
            .build()
}
