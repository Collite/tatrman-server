// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.SpanQuery
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.EngineVersion
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Binder
import org.tatrman.resolver.pipeline.Bindings
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.MatchMethod
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.resolver.v1.ValueKind

/**
 * RV-P2.2.T5 — **the method is dispatched by the lexicon, never by code**, and the grounded path
 * never reaches the lexicon at all.
 *
 * Two claims, and they pull in opposite directions, which is why they are pinned together:
 *
 *  - Everything the estate declared as vocabulary is matched by the method the ESTATE authored for
 *    it (RV-32). The core does not choose EXACT for codes and TYPOS for names; it asks, and the
 *    answer arrives on the row.
 *  - Everything that was never vocabulary — dates, amounts, free literals (RV-32: "`grounded` and
 *    `passthrough` survive OUTSIDE the lexicon") — bypasses the whole apparatus. No method, no
 *    class, no gate. That path is unchanged by this list, and the last case here is what proves it
 *    stayed unchanged rather than merely being believed to have.
 */
class MethodDispatchTest :
    StringSpec({

        val thresholds = ResolverThresholds.LIVE

        "the broad pass CANNOT dispatch a method: `SpanQuery` has nowhere to put one" {
            // Not a policy the resolver follows — a shape it cannot violate. The core's one
            // BatchMatch carries a term, its categories and a limit, so every row that comes back
            // was matched by whatever the estate authored for it.
            SpanQuery
                .getDescriptor()
                .fields
                .map { it.name } shouldContainExactly listOf("query", "categories", "limit")

            // The contrast, and the reason the absence above is a design and not an oversight:
            // widening IS expressible, on `Lookup` (contracts §1 addendum), where it belongs to a
            // round the caller decided to run. That is P2.3's seam, not the broad pass's.
            LookupRequest
                .getDescriptor()
                .fields
                .map { it.name } shouldContainExactly
                listOf("term", "categories", "target_classes", "method_override", "max_candidates")
        }

        "the SAME span and the SAME score classify differently because the estate authored differently" {
            val span = mention("účtu")

            fun classOf(method: String) =
                Binder
                    .gate(listOf(declared("md.dimension.Account", method, score = 0.94)), span, thresholds)
                    .shouldBeInstanceOf<Binder.Bind>()
                    .winner.evidenceClass

            classOf("EXACT") shouldBe EvidenceClass.EVIDENCE_CLASS_EXACT
            classOf("TYPOS(1)") shouldBe EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
            classOf("TOKENS") shouldBe EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
        }

        "the authored method rides onto the binding intact, parameter and all" {
            val span = mention("naklady")
            val verdict =
                Binder
                    .gate(listOf(declared("md.measure.cost", "TYPOS(2)", score = 0.94)), span, thresholds)
                    .shouldBeInstanceOf<Binder.Bind>()
            val binding = Bindings.of(verdict.winner, "snap-1")
            binding.method shouldBe MatchMethod.MATCH_METHOD_TYPOS
            binding.maxDistance shouldBe 2
        }

        "a row with NO authored method reports none — the core does not fill the gap with a guess" {
            val span = mention("501001")
            val verdict =
                Binder
                    .gate(listOf(member("501001", score = 1.0)), span, thresholds)
                    .shouldBeInstanceOf<Binder.Bind>()
            val binding = Bindings.of(verdict.winner, "snap-1")
            binding.method shouldBe MatchMethod.MATCH_METHOD_UNSPECIFIED
            binding.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_EXACT
        }

        "REGRESSION: a date still grounds without ever touching the lexicon or the gate" {
            val fuzzy = RecordingFuzzy(HERO_BATCH)
            val pipeline =
                ResolverPipeline(
                    FakeNlp(heroParse()),
                    fuzzy,
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), thresholds),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                )

            val resp = runBlocking { pipeline.resolve(request()) }

            // (1) the date was never asked about. The span `poslední fiskální čtvrtletí` (55..82)
            // is a universal, so span proposal never proposes it and no slot of the one BatchMatch
            // covers it — the gate cannot have had an opinion about something it never saw.
            fuzzy.calls shouldBe 1
            fuzzy.queries.none { it in DATE_SURFACE } shouldBe true

            // (2) it grounded anyway, on the path that was never the lexicon's.
            val universal = resp.resolution.bindingsList.single { it.hasUniversal() }
            universal.universal.entityType shouldBe UniversalEntityType.DATE
            universal.universal.sourceEngine shouldBe "nametag3"

            // (3) and the lattice records it as a GROUNDED value with no gap — a date IS the value
            // a planner will use, so nothing is owed on it (`Gaps.selfGrounding`).
            val value = resp.resolutionState.valuesList.single { it.kind == ValueKind.VALUE_KIND_GROUNDED }
            value.grounding.kind shouldBe "DATE"
            value.attributionsCount shouldBe 0
            resp.resolutionState.gapsList
                .none { it.valueId == value.id }
                .shouldBeTrue()
        }
    }) {
    private class FakeNlp(
        private val parse: AnalyzeResponse,
    ) : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

        override suspend fun getStatus(): StatusResponse =
            StatusResponse
                .newBuilder()
                .setReady(true)
                .addCapabilities(
                    Capability
                        .newBuilder()
                        .setOp(NlpOp.NER)
                        .setLanguage("cs")
                        .setEngine("nametag3"),
                ).addCapabilities(
                    Capability
                        .newBuilder()
                        .setOp(NlpOp.DEP_PARSE)
                        .setLanguage("cs")
                        .setEngine("stanza"),
                ).build()
    }

    /** Keeps what was actually asked, so "never touched the lexicon" is checkable and not assumed. */
    private class RecordingFuzzy(
        private val response: BatchMatchResponse,
    ) : FuzzyClient {
        var calls = 0
        val queries = mutableListOf<String>()

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            calls++
            queries += request.spansList.map { it.query }
            return response
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }

    private companion object {
        private const val HERO_TEXT =
            "Kolik jsme utržili za Octavie v pražských pobočkách za poslední fiskální čtvrtletí?"

        /** Every surface the date span could have been asked about, whole or in part. */
        private val DATE_SURFACE =
            setOf("poslední fiskální čtvrtletí", "poslední", "fiskální", "čtvrtletí")

        private fun request(): ResolveRequest =
            ResolveRequest
                .newBuilder()
                .setConversationId("c-dispatch")
                .setFresh(FreshQuestion.newBuilder().setText(HERO_TEXT).setLocale("cs"))
                .setRegistry(
                    Registry
                        .newBuilder()
                        .addEntityTypes(
                            EntityType
                                .newBuilder()
                                .setRef("er.branch")
                                .addCategories("er.branch")
                                .addAnchors("pobočka"),
                        ).addEntityTypes(
                            EntityType
                                .newBuilder()
                                .setRef("er.product")
                                .addCategories("er.product"),
                        ).addLocales("cs")
                        .setSnapshotHash("snap-dispatch"),
                ).build()

        // Positional to proposeDomainSpans output: [0]=`pražských pobočkách`, [1]=`Octavie`.
        private val HERO_BATCH: BatchMatchResponse =
            BatchMatchResponse
                .newBuilder()
                .addResults(
                    FuzzyMatchResponse.newBuilder().addMatches(
                        FuzzyMatch
                            .newBuilder()
                            .setCandidateId("term-pobocka")
                            .setCandidate("pobočka")
                            .setScore(0.88)
                            .setCategory("er.branch")
                            .setSource(SourceTag.VOCABULARY)
                            .setTargetRef("er.branch#term-pobocka")
                            .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN")),
                    ),
                ).addResults(
                    FuzzyMatchResponse.newBuilder().addMatches(
                        FuzzyMatch
                            .newBuilder()
                            .setCandidateId("p-octavia")
                            .setCandidate("Škoda Octavia")
                            .setScore(0.95)
                            .setCategory("er.product")
                            .setSource(SourceTag.MEMBER)
                            .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN")),
                    ),
                ).build()

        private fun mention(text: String) =
            DomainSpanCandidate(
                text,
                0,
                text.length,
                listOf("er.x"),
                listOf("er.x"),
                anchored = true,
                origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
                lemma = text,
            )

        private fun declared(
            targetRef: String,
            method: String,
            score: Double,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:$targetRef")
                .setCandidate(targetRef)
                .setScore(score)
                .setCategory(targetRef)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(targetRef)
                .setMatchMethod(method)
                .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
                .build()

        private fun member(
            id: String,
            score: Double,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(id)
                .setCandidate(id)
                .setScore(score)
                .setCategory("md.dimension.Account.code")
                .setSource(SourceTag.MEMBER)
                .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
                .build()

        private fun heroParse(): AnalyzeResponse =
            AnalyzeResponse
                .newBuilder()
                .setLanguage("cs")
                .setDetectedLanguage("cs")
                .setTraceId("trace-dispatch")
                .addAllTokens(
                    listOf(
                        tok("Kolik", 0, 5, "kolik", "ADV", 3, "advmod"),
                        tok("jsme", 6, 10, "být", "AUX", 3, "aux"),
                        tok("utržili", 11, 18, "utržit", "VERB", 0, "root"),
                        tok("za", 19, 21, "za", "ADP", 5, "case"),
                        tok("Octavie", 22, 29, "Octavie", "PROPN", 3, "obl"),
                        tok("v", 30, 31, "v", "ADP", 8, "case"),
                        tok("pražských", 32, 41, "pražský", "ADJ", 8, "amod"),
                        tok("pobočkách", 42, 51, "pobočka", "NOUN", 3, "obl"),
                        tok("za", 52, 54, "za", "ADP", 12, "case"),
                        tok("poslední", 55, 63, "poslední", "ADJ", 12, "amod"),
                        tok("fiskální", 64, 72, "fiskální", "ADJ", 12, "amod"),
                        tok("čtvrtletí", 73, 82, "čtvrtletí", "NOUN", 3, "obl"),
                        tok("?", 82, 83, "?", "PUNCT", 3, "punct"),
                    ),
                ).addEntities(
                    NerEntity
                        .newBuilder()
                        .setText("poslední fiskální čtvrtletí")
                        .setCharStart(55)
                        .setCharEnd(82)
                        .setLabel("DATE")
                        .setSourceEngine("nametag3"),
                ).addUsed(
                    EngineVersion
                        .newBuilder()
                        .setOp("NER")
                        .setEngine("nametag3")
                        .setModel("cnec2.0")
                        .setModelVersion("240830"),
                ).build()

        private fun tok(
            text: String,
            start: Int,
            end: Int,
            lemma: String,
            upos: String,
            depHead: Int,
            depRelation: String,
        ): Token =
            Token
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(end)
                .setLemma(lemma)
                .setUpos(upos)
                .setDepHead(depHead)
                .setDepRelation(depRelation)
                .build()
    }
}
