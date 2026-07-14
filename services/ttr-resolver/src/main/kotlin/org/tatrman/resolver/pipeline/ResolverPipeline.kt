// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.slf4j.LoggerFactory
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverRegistry
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.v1.AwaitingClarification
import org.tatrman.resolver.v1.BindingProvenance
import org.tatrman.resolver.v1.Candidate
import org.tatrman.resolver.v1.Capabilities
import org.tatrman.resolver.v1.Domain
import org.tatrman.resolver.v1.EntityBinding
import org.tatrman.resolver.v1.Option
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.Resolution
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.Universal

/**
 * The deterministic resolver pipeline (RG-P5.S1.T5): parse → extractUniversal →
 * proposeDomainSpans (anchored, Q-20) → gateSpans (one BatchMatch) → assemble a
 * `Resolution | AwaitingClarification`. ZERO LLM — the only upstreams are ttr-nlp
 * (parse + capability matrix) and ttr-fuzzy (vocabulary match), neither an LLM;
 * NoLlmDependencyTest guards the module.
 *
 * The registry is the caller's `Registry` override when present, else the
 * [defaultRegistry] (snapshot-fed in S2). The HMAC resume path + capability-driven
 * degrade land in S2; S1 handles the fresh-question path and echoes the capability
 * matrix honestly.
 */
class ResolverPipeline(
    private val nlp: NlpClient,
    private val fuzzy: FuzzyClient,
    private val defaultRegistry: ResolverRegistry,
    private val siblings: SiblingCatalog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val analyzeOps =
        listOf(NlpOp.TOKENIZE, NlpOp.LEMMATIZE, NlpOp.POS_TAG, NlpOp.DEP_PARSE, NlpOp.NER, NlpOp.DETECT_LANGUAGE)

    suspend fun resolve(request: ResolveRequest): ResolveResponse {
        if (!request.hasFresh()) {
            // S2 owns the HMAC resume path; S1 serves fresh questions only.
            log.info(
                "resume path is S2 (HMAC tokens) — returning empty resolution for conversation_id={}",
                request.conversationId,
            )
            return ResolveResponse
                .newBuilder()
                .setResolution(Resolution.getDefaultInstance())
                .setTraceId(request.conversationId)
                .setCapabilities(Capabilities.getDefaultInstance())
                .build()
        }

        val fresh = request.fresh
        val locale = fresh.locale
        val parse = nlp.analyze(analyzeRequest(fresh.text, locale))
        val status = runCatching { nlp.getStatus() }.getOrNull()
        val capabilities = capabilitiesOf(parse, status)

        val registry =
            if (request.hasRegistry()) {
                fromProto(
                    request.registry,
                    defaultRegistry.thresholds,
                )
            } else {
                defaultRegistry
            }

        val universals = UniversalExtraction.extractUniversal(parse)
        val candidates = SpanProposal.proposeDomainSpans(parse, registry.entityTypes)
        val batchReq = GateSpans.buildBatchRequest(candidates, locale.ifBlank { null }, registry.thresholds.maxOptions)
        val batchResp = fuzzy.batchMatch(batchReq)
        val outcome =
            GateSpans.gate(
                candidates,
                batchResp,
                registry.entityTypes,
                registry.thresholds,
                siblings,
                registry.snapshotHash,
            )

        val builder =
            ResolveResponse
                .newBuilder()
                .setParse(parse)
                .setTraceId(parse.traceId.ifBlank { request.conversationId })
                .setElapsedMs(parse.elapsedMs)
                .setCapabilities(capabilities)

        when (outcome) {
            is Clarify -> builder.awaiting = awaitingOf(outcome)
            is Bound -> builder.resolution = resolutionOf(universals, outcome, parse)
        }
        return builder.build()
    }

    // --- assembly -----------------------------------------------------------

    private fun analyzeRequest(
        text: String,
        locale: String,
    ): AnalyzeRequest =
        AnalyzeRequest
            .newBuilder()
            .setText(text)
            .setLanguage(locale)
            .addAllOps(analyzeOps)
            .build()

    private fun capabilitiesOf(
        parse: AnalyzeResponse,
        status: StatusResponse?,
    ): Capabilities {
        val lang = parse.detectedLanguage.ifBlank { parse.language }
        val caps = status?.capabilitiesList.orEmpty()
        val csNer = caps.any { it.op == NlpOp.NER && it.language.equals(lang, ignoreCase = true) }
        val depParse = caps.any { it.op == NlpOp.DEP_PARSE && it.language.equals(lang, ignoreCase = true) }
        return Capabilities
            .newBuilder()
            .setLanguage(lang)
            .setCsNer(csNer)
            .setDepParse(depParse)
            .setFuzzyReady(true) // full capability-driven degrade lands in S2 (RS-25)
            .setDegraded(false)
            .build()
    }

    private fun resolutionOf(
        universals: List<UniversalBinding>,
        bound: Bound,
        parse: AnalyzeResponse,
    ): Resolution {
        val nerVersions = parse.usedList.filter { it.op.equals("NER", ignoreCase = true) }
        val builder = Resolution.newBuilder().setConfidence(bound.confidence)
        for (u in universals) builder.addBindings(universalBinding(u, nerVersions))
        for (b in bound.bindings) builder.addBindings(domainBinding(b, parse))
        builder.rationale = "deterministic bind: ${universals.size} universal, ${bound.bindings.size} domain"
        return builder.build()
    }

    private fun universalBinding(
        u: UniversalBinding,
        nerVersions: List<org.tatrman.nlp.v1.EngineVersion>,
    ): EntityBinding =
        EntityBinding
            .newBuilder()
            .setSpan(span(u.start, u.end, u.text))
            .setUniversal(
                Universal
                    .newBuilder()
                    .setEntityType(u.entityType)
                    .setRawText(u.rawText)
                    .setNormalizedValue(u.normalizedValue)
                    .setSourceEngine(u.sourceEngine),
            ).setProvenance(
                BindingProvenance
                    .newBuilder()
                    .setVocabularySource("universal:${u.sourceEngine}")
                    .setAlgorithm("ner")
                    .setScore(1.0)
                    .addAllModelVersions(nerVersions),
            ).build()

    private fun domainBinding(
        b: DomainBinding,
        parse: AnalyzeResponse,
    ): EntityBinding {
        val domain =
            Domain
                .newBuilder()
                .setEntityTypeRef(b.entityTypeRef)
                .setRawText(b.rawText)
                .setResolvedLabel(b.resolvedLabel)
        if (b.resolvedId != null) domain.resolvedId = b.resolvedId
        // Sibling-column expansion (Q-20): the value also points at its sibling column.
        for (sibling in b.siblingRefs) {
            domain.addCandidates(
                Candidate
                    .newBuilder()
                    .setTargetRef(sibling)
                    .setScore(b.score)
                    .setResolvedLabel(b.resolvedLabel),
            )
        }
        if (b.targetRef != null && b.siblingRefs.isEmpty()) {
            domain.addCandidates(
                Candidate
                    .newBuilder()
                    .setTargetRef(b.targetRef)
                    .setScore(b.score)
                    .setResolvedLabel(b.resolvedLabel),
            )
        }
        return EntityBinding
            .newBuilder()
            .setSpan(span(b.span.start, b.span.end, b.rawText))
            .setDomain(domain)
            .setProvenance(
                BindingProvenance
                    .newBuilder()
                    .setVocabularySource(b.vocabularySource)
                    .setAlgorithm(b.algorithm)
                    .setScore(b.score)
                    .setSnapshotHash(b.snapshotHash)
                    .addAllModelVersions(parse.usedList),
            ).setDegraded(false)
            .build()
    }

    private fun awaitingOf(clarify: Clarify): AwaitingClarification {
        val builder = AwaitingClarification.newBuilder()
        for (o in clarify.options) {
            val opt = Option.newBuilder().setId(o.id).setLabel(o.label)
            if (o.resolvedId != null) opt.resolvedId = o.resolvedId
            if (o.targetRef != null) opt.targetRef = o.targetRef
            builder.addOptions(opt)
        }
        // resume_token stays empty in S1 — HMAC signing lands in S2.T3/T4 (RS-26).
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

    companion object {
        /** Map the caller-supplied `Registry` proto override into the internal model (RS-24). */
        fun fromProto(
            reg: Registry,
            fallback: ResolverThresholds,
        ): ResolverRegistry {
            val entityTypes =
                reg.entityTypesList.map {
                    ResolverEntityType(
                        it.ref,
                        it.categoriesList.toList(),
                        it.anchorsList.toList(),
                    )
                }
            val thresholds =
                if (reg.hasThresholds()) {
                    val t = reg.thresholds
                    ResolverThresholds(
                        bind = if (t.bind > 0) t.bind else fallback.bind,
                        ambiguityGap = if (t.ambiguityGap > 0) t.ambiguityGap else fallback.ambiguityGap,
                        exact = if (t.exact > 0) t.exact else fallback.exact,
                        maxOptions = if (t.maxOptions > 0) t.maxOptions else fallback.maxOptions,
                    )
                } else {
                    fallback
                }
            return ResolverRegistry(entityTypes, reg.localesList.toList(), thresholds, reg.snapshotHash)
        }
    }
}
