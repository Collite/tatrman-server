// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.LatticeAssembler
import org.tatrman.resolver.pipeline.LookupRounds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.resolver.v1.ValueKind

/**
 * RV-P2.3.T6 — **H1, resolved end to end by the deterministic core alone.**
 *
 * *"Zobraz náklady účtu 501001 v roce 2025 podle období"* → a gap-free lattice, with **zero LLM
 * anywhere**. That is the design's headline claim (design.md H1, "the 0-LLM proof"), and this is it
 * as an executable test rather than a paragraph.
 *
 * The `h1-cs` golden already proves the claim when the broad pass answers everything. This proves
 * the harder and more realistic version: the broad pass **misses both** the account mention and the
 * account code, and the narrowing loop closes the question on its own — round 1 binds the mention
 * cross-category, round 2 uses what round 1 learned to scope the code into the account axis. Two
 * rounds, no LLM, no ask.
 *
 * ⚑ **One deviation from the list's wording, recorded rather than glossed.** T6 says "rung_log
 * shows only `lookup` entries". It cannot: the core's own deterministic pass writes a `core` entry
 * (`annotate`, plus the RV-42 `ground-narrow` entries P1.6 added), and removing it would delete the
 * audit trail of the pass that did most of the work. The claim the list is making is about the
 * LADDER — that nothing above `lookup` ran — so that is what is asserted: every rung in the log is
 * `core` or `lookup`, and `local`/`capable`/`emulated` appear nowhere.
 */
class H1CorePassTest :
    StringSpec({

        "H1 resolves with ZERO gaps and ZERO LLM — broad pass + lookup rounds, nothing else" {
            val fuzzy =
                RoundFuzzy(
                    // The realistic miss: neither the axis nor the code comes back from the broad,
                    // wide, single-question pass. Everything below is the rung's work.
                    broadMisses = setOf("účtu", "501001"),
                    answers =
                        mapOf(
                            "účtu" to
                                listOf(
                                    declared("md.dimension.Account", TargetClass.TARGET_CLASS_MODEL_OBJECT),
                                ),
                            "501001" to listOf(member("501001", "md.dimension.Account.code")),
                        ),
                )
            val state = resolve(fuzzy)

            // --- the annotation the design promises -------------------------------------
            state.gapsList shouldBe emptyList()

            val show = state.mentionsList.single { it.span.text == "Zobraz" }
            show.bindingsList.single().ref shouldBe "op:show"
            show.bindingsList.single().targetClass shouldBe TargetClass.TARGET_CLASS_OPERATOR

            val ucet = state.mentionsList.single { it.span.text == "účtu" }
            ucet.bindingsList.single().ref shouldBe "md.dimension.Account"
            ucet.frameRolesList shouldContainExactly listOf(FrameRole.FRAME_ROLE_FILTER)

            val code = state.valuesList.single { it.span.text == "501001" }
            code.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            val attribution = code.attributionsList.single()
            attribution.attributeRef shouldBe "md.dimension.Account.code"
            attribution.binding.targetClass shouldBe TargetClass.TARGET_CLASS_MEMBER
            attribution.binding.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_EXACT

            val year = state.valuesList.single { it.span.text == "2025" }
            year.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            year.grounding.kind shouldBe "DATE"

            state.mentionsList
                .single { it.span.text == "období" }
                .frameRolesList shouldContainExactly listOf(FrameRole.FRAME_ROLE_GROUPING)

            // --- and the ladder never left the bottom rung -------------------------------
            val rungs = state.rungLogList.map { it.rung }.distinct()
            rungs.toSet() shouldBe setOf(LatticeAssembler.CORE_RUNG, LookupRounds.RUNG)
            rungs.none { it in setOf("local", "capable", "emulated") }.shouldBeTrue()

            // two rounds, and the second is the one that could only exist because of the first
            val rounds = state.rungLogList.filter { it.rung == LookupRounds.RUNG }
            rounds.size shouldBe 2
            rounds.sumOf { it.bindingsAdded } shouldBe 2
            rounds.last().gapsOpen shouldBe 0
            fuzzy.lookups.map { it.term } shouldContainExactly listOf("účtu", "501001")
            fuzzy.lookups[1]
                .categoriesList
                .contains("md.dimension.Account.code")
                .shouldBeTrue()

            // one broad pass, and the rung's rounds — no cascade, no re-broadening
            fuzzy.batches shouldBe 1
        }

        "the DOOR agrees with the lattice: what a round bound is in `Resolution.bindings` too" {
            val fuzzy =
                RoundFuzzy(
                    broadMisses = setOf("účtu", "501001"),
                    answers =
                        mapOf(
                            "účtu" to
                                listOf(
                                    declared("md.dimension.Account", TargetClass.TARGET_CLASS_MODEL_OBJECT),
                                ),
                            "501001" to listOf(member("501001", "md.dimension.Account.code")),
                        ),
                )
            val response = runBlocking { pipelineFor(fuzzy).resolve(request()) }

            response.hasResolution().shouldBeTrue()
            // Before P2.3 the outcome was welded to the one BatchMatch that happened to come first,
            // so a round could bind a span the door still reported as missing. Both readers now get
            // the same story.
            response.resolution.bindingsList
                .filter { it.hasDomain() }
                .map { it.domain.entityTypeRef }
                .contains("md.dimension.Account")
                .shouldBeTrue()
        }
    }) {
    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()
        private val CASE: JsonObject = loadJson("/lattice/h1-cs.case.json")

        private fun pipelineFor(fuzzy: FuzzyClient): ResolverPipeline =
            ResolverPipeline(
                FakeNlp(
                    merge(
                        loadJson("/lattice/${CASE["parseFile"]!!.jsonPrimitive.content}"),
                        AnalyzeResponse.newBuilder(),
                    ).build(),
                ),
                fuzzy,
                SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                emptyMap(),
                ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                lookupRounds = LookupRounds(fuzzy),
            )

        private fun request(): ResolveRequest =
            ResolveRequest
                .newBuilder()
                .setConversationId("h1-core-pass")
                .setFresh(
                    FreshQuestion
                        .newBuilder()
                        .setText(CASE["text"]!!.jsonPrimitive.content)
                        .setLocale(CASE["locale"]!!.jsonPrimitive.content),
                ).setRegistry(merge(CASE["registry"]!!.jsonObject, Registry.newBuilder()).build())
                .build()

        private fun resolve(fuzzy: FuzzyClient) = runBlocking { pipelineFor(fuzzy).resolve(request()) }.resolutionState

        private fun declared(
            targetRef: String,
            targetClass: TargetClass,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:$targetRef")
                .setCandidate(targetRef)
                .setScore(1.0)
                .setCategory(targetRef)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(targetRef)
                .setTargetClass(
                    org.tatrman.fuzzy.v1.TargetClass
                        .forNumber(targetClass.number),
                ).setMatchMethod("EXACT")
                .build()

        private fun member(
            id: String,
            category: String,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(id)
                .setCandidate(id)
                .setScore(1.0)
                .setCategory(category)
                .setSource(SourceTag.MEMBER)
                .build()

        private fun <T : com.google.protobuf.Message.Builder> merge(
            source: JsonObject,
            builder: T,
        ): T {
            parser.merge(source.toString(), builder)
            return builder
        }

        private fun loadJson(resource: String): JsonObject =
            json
                .parseToJsonElement(
                    H1CorePassTest::class.java.getResource(resource)?.readText()
                        ?: error("missing test resource $resource"),
                ).jsonObject
    }

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

    private class RoundFuzzy(
        private val broadMisses: Set<String>,
        private val answers: Map<String, List<FuzzyMatch>> = emptyMap(),
    ) : FuzzyClient {
        var batches = 0
        val lookups: MutableList<LookupRequest> = mutableListOf()

        private val byQuery: Map<String, List<FuzzyMatch>> =
            CASE["matcher"]!!
                .jsonObject["byQuery"]!!
                .jsonObject
                .mapValues { (_, matches) ->
                    matches.jsonArray.map { merge(it.jsonObject, FuzzyMatch.newBuilder()).build() }
                }

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            batches++
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val scoped = span.categoriesList.toSet()
                val matches =
                    if (span.query in broadMisses) {
                        emptyList()
                    } else {
                        byQuery[span.query].orEmpty().filter { scoped.isEmpty() || it.category in scoped }
                    }
                builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(matches))
            }
            return builder.build()
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            lookups += request
            return LookupResponse.newBuilder().addAllCandidates(answers[request.term].orEmpty()).build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }
}
