// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.slf4j.LoggerFactory
import org.tatrman.diagnostics.RgDiagnostics
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverRegistry
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.token.ResumeOption
import org.tatrman.resolver.token.ResumePayload
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.token.ResumeTokenException
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
 * The deterministic resolver pipeline (RG-P5): parse → extractUniversal →
 * proposeDomainSpans (anchored, Q-20) → gateSpans (one BatchMatch) → assemble a
 * `Resolution | AwaitingClarification`. ZERO LLM — the only upstreams are ttr-nlp
 * (parse + capability matrix) and ttr-fuzzy (vocabulary match), neither an LLM;
 * NoLlmDependencyTest guards the module.
 *
 * S2 additions: the registry is snapshot-fed ([registry], RS-24) with the caller's
 * per-request `Registry` override winning; a clarification is offered under a
 * signed HMAC resume token and a resume with a matching pin binds at confidence
 * 1.0 with **no re-fuzzy** (RS-26); the capability matrix drives degrade — an
 * unsupported language falls to the fold+fuzzy floor with every binding
 * `degraded=true` + RG-RES-001, and the `capabilities` echo reports what actually
 * backed the resolve (F-T3 honesty, RS-25).
 */
class ResolverPipeline(
    private val nlp: NlpClient,
    private val fuzzy: FuzzyClient,
    private val registry: SnapshotRegistry,
    private val siblings: SiblingCatalog,
    private val tokenCodec: ResumeTokenCodec,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val analyzeOps =
        listOf(NlpOp.TOKENIZE, NlpOp.LEMMATIZE, NlpOp.POS_TAG, NlpOp.DEP_PARSE, NlpOp.NER, NlpOp.DETECT_LANGUAGE)

    suspend fun resolve(request: ResolveRequest): ResolveResponse =
        when {
            request.hasResume() -> resumeResolve(request)
            request.hasFresh() -> freshResolve(request)
            else -> {
                log.info("empty resolve request conversation_id={}", request.conversationId)
                ResolveResponse
                    .newBuilder()
                    .setResolution(Resolution.getDefaultInstance())
                    .setTraceId(request.conversationId)
                    .setCapabilities(Capabilities.getDefaultInstance())
                    .build()
            }
        }

    // --- fresh path ---------------------------------------------------------

    private suspend fun freshResolve(request: ResolveRequest): ResolveResponse {
        val fresh = request.fresh
        val locale = fresh.locale
        val parse = nlp.analyze(analyzeRequest(fresh.text, locale))
        val status = runCatching { nlp.getStatus() }.getOrNull()
        val assessment = assess(parse, status)

        val resolverRegistry =
            if (request.hasRegistry()) {
                fromProto(
                    request.registry,
                    registry.current().thresholds,
                )
            } else {
                registry.current()
            }

        val universals = if (assessment.csNer) UniversalExtraction.extractUniversal(parse) else emptyList()
        val candidates = SpanProposal.proposeDomainSpans(parse, resolverRegistry.entityTypes)
        val batchReq =
            GateSpans.buildBatchRequest(
                candidates,
                locale.ifBlank { null },
                resolverRegistry.thresholds.maxOptions,
            )
        val batchResp = fuzzy.batchMatch(batchReq)
        val outcome =
            GateSpans.gate(
                candidates,
                batchResp,
                resolverRegistry.entityTypes,
                resolverRegistry.thresholds,
                siblings,
                resolverRegistry.snapshotHash,
            )

        val builder =
            ResolveResponse
                .newBuilder()
                .setParse(parse)
                .setTraceId(parse.traceId.ifBlank { request.conversationId })
                .setElapsedMs(parse.elapsedMs)
                .setCapabilities(capabilities(assessment))

        when (outcome) {
            is Clarify ->
                builder.awaiting = awaitingOf(outcome, request.conversationId, parse.traceId, request.callerSubject)
            is Bound -> builder.resolution = resolutionOf(universals, outcome, parse, assessment.degradedFloor)
        }
        return builder.build()
    }

    // --- resume path (RS-26) ------------------------------------------------

    private fun resumeResolve(request: ResolveRequest): ResolveResponse {
        val resume = request.resume
        // A bad token is RG-RES-002 — refuse over guess; the gRPC layer maps the throw.
        val payload = tokenCodec.verify(resume.token).getOrThrow()
        // Subject binding (RG-P6 review C): the token was signed to a specific OBO
        // subject; a different principal (or an empty-subject caller replaying a
        // user's token) is refused even though the HMAC is valid. Empty==empty is the
        // dev-network path where no identity was required at issue OR resume.
        if (payload.subject != request.callerSubject) {
            throw ResumeTokenException(
                "resume token was issued to a different caller than the one resuming it",
            )
        }
        val option =
            payload.options.firstOrNull { it.id == resume.selectedOptionId }
                ?: throw ResumeTokenException(
                    "selected_option_id '${resume.selectedOptionId}' is not in the signed option set",
                )

        // A signed pin binds at confidence 1.0 with NO re-fuzzy (fuzzy is not called).
        val binding = pinnedBinding(option)
        return ResolveResponse
            .newBuilder()
            .setTraceId(payload.conversationId)
            .setResolution(
                Resolution
                    .newBuilder()
                    .addBindings(binding)
                    .setConfidence(1.0)
                    .setRationale("resumed via signed pin (${option.id})"),
            ).setCapabilities(Capabilities.newBuilder().setFuzzyReady(true))
            .build()
    }

    private fun pinnedBinding(option: ResumeOption): EntityBinding {
        // Prefer the signed entity_type_ref (present for MEMBER options too, RG-P6
        // review F); fall back to the VOCABULARY target's ref prefix for older tokens.
        val entityTypeRef = option.entityTypeRef?.ifBlank { null } ?: option.targetRef?.substringBefore('#') ?: ""
        val domain =
            Domain
                .newBuilder()
                .setEntityTypeRef(entityTypeRef)
                .setRawText(option.label)
                .setResolvedLabel(option.label)
        if (option.resolvedId != null) domain.resolvedId = option.resolvedId
        if (option.targetRef !=
            null
        ) {
            domain.addCandidates(
                Candidate
                    .newBuilder()
                    .setTargetRef(option.targetRef)
                    .setScore(1.0)
                    .setResolvedLabel(option.label),
            )
        }
        return EntityBinding
            .newBuilder()
            .setDomain(domain)
            .setProvenance(
                BindingProvenance
                    .newBuilder()
                    .setVocabularySource(if (option.resolvedId != null) "MEMBER" else "VOCABULARY")
                    .setAlgorithm("hmac-pin")
                    .setScore(1.0),
            ).build()
    }

    // --- capability assessment (RS-25) --------------------------------------

    /**
     * What actually backed this resolve, honestly (F-T3). A capability counts as
     * available if the matrix advertises it OR the parse itself carried it. An
     * unsupported language (no dep parse AND no NER either way) is the fold+fuzzy
     * floor — every binding is then degraded.
     */
    private data class Assessment(
        val language: String,
        val csNer: Boolean,
        val depParse: Boolean,
        val degradedFloor: Boolean,
    )

    private fun assess(
        parse: AnalyzeResponse,
        status: StatusResponse?,
    ): Assessment {
        val lang = parse.detectedLanguage.ifBlank { parse.language }
        val caps = status?.capabilitiesList.orEmpty().filter { it.language.equals(lang, ignoreCase = true) }
        val csNer = caps.any { it.op == NlpOp.NER } || parse.entitiesList.isNotEmpty()
        val depParse = caps.any { it.op == NlpOp.DEP_PARSE } || parse.tokensList.any { it.depHead > 0 }
        return Assessment(lang, csNer, depParse, degradedFloor = !csNer && !depParse)
    }

    private fun capabilities(a: Assessment): Capabilities {
        val builder =
            Capabilities
                .newBuilder()
                .setLanguage(a.language)
                .setCsNer(a.csNer)
                .setDepParse(a.depParse)
                .setFuzzyReady(true)
                .setDegraded(a.degradedFloor)
        if (a.degradedFloor) builder.addDegradedReasons(RgDiagnostics.render("RG-RES-001", "span" to "(all)"))
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

    private fun resolutionOf(
        universals: List<UniversalBinding>,
        bound: Bound,
        parse: AnalyzeResponse,
        degraded: Boolean,
    ): Resolution {
        val nerVersions = parse.usedList.filter { it.op.equals("NER", ignoreCase = true) }
        val builder = Resolution.newBuilder().setConfidence(bound.confidence)
        for (u in universals) builder.addBindings(universalBinding(u, nerVersions, degraded))
        for (b in bound.bindings) builder.addBindings(domainBinding(b, parse, degraded))
        builder.rationale =
            "deterministic bind: ${universals.size} universal, ${bound.bindings.size} domain" +
            if (degraded) " (degraded floor)" else ""
        return builder.build()
    }

    private fun universalBinding(
        u: UniversalBinding,
        nerVersions: List<org.tatrman.nlp.v1.EngineVersion>,
        degraded: Boolean,
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
            ).setDegraded(degraded)
            .build()

    private fun domainBinding(
        b: DomainBinding,
        parse: AnalyzeResponse,
        degraded: Boolean,
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
            ).setDegraded(degraded)
            .build()
    }

    private fun awaitingOf(
        clarify: Clarify,
        conversationId: String,
        parseRef: String,
        subject: String,
    ): AwaitingClarification {
        val builder = AwaitingClarification.newBuilder()
        val signedOptions = mutableListOf<ResumeOption>()
        for (o in clarify.options) {
            val opt = Option.newBuilder().setId(o.id).setLabel(o.label)
            if (o.resolvedId != null) opt.resolvedId = o.resolvedId
            if (o.targetRef != null) opt.targetRef = o.targetRef
            if (o.entityTypeRef.isNotBlank()) opt.entityTypeRef = o.entityTypeRef
            opt.span = span(o.spanStart, o.spanEnd, o.spanText)
            builder.addOptions(opt)
            signedOptions += ResumeOption(o.id, o.label, o.targetRef, o.resolvedId, o.entityTypeRef)
        }
        // Sign the EXACT offered set (so a resume can only pick from it, RS-26) AND the
        // OBO subject it was issued to (so a leaked token can't be replayed by another
        // principal, RG-P6 review C).
        val payload =
            ResumePayload(
                conversationId = conversationId,
                parseRef = parseRef,
                options = signedOptions,
                issuedAt = System.currentTimeMillis() / 1000,
                keyId = tokenCodec.activeKeyId,
                subject = subject,
            )
        builder.resumeToken = tokenCodec.sign(payload)
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
                    // proto3 scalar default 0 is the "unset" sentinel. A caller may only
                    // move a safety threshold in the CONSERVATIVE direction — raising
                    // `bind`/`ambiguity_gap`/`exact` all make the gate MORE likely to
                    // refuse/clarify. Clamping to `maxOf(override, fallback)` closes the
                    // refuse-over-guess hole where a per-request `bind = 0.1` lowered the
                    // 0.5 floor and let near-junk matches bind (RG-P6 review E). `max_options`
                    // is a display cap, not a safety floor, so it takes any positive value.
                    val t = reg.thresholds
                    ResolverThresholds(
                        bind = if (t.bind > 0) maxOf(t.bind, fallback.bind) else fallback.bind,
                        ambiguityGap =
                            if (t.ambiguityGap >
                                0
                            ) {
                                maxOf(t.ambiguityGap, fallback.ambiguityGap)
                            } else {
                                fallback.ambiguityGap
                            },
                        exact = if (t.exact > 0) maxOf(t.exact, fallback.exact) else fallback.exact,
                        maxOptions = if (t.maxOptions > 0) t.maxOptions else fallback.maxOptions,
                    )
                } else {
                    fallback
                }
            return ResolverRegistry(entityTypes, reg.localesList.toList(), thresholds, reg.snapshotHash)
        }
    }
}
