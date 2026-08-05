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
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.TargetClass

/**
 * RV-P2.2.T6 — issues.md §"Looking in wrong entity", as a permanent component test.
 *
 * The 2026-07-28 failure had **two** causes and this list fixes the second one. Cause one was
 * scope: nothing tied `501001` to the entity the user named, so the code was searched against
 * *středisko* at all — that is Q-20 anchored proposal, landed at P2.1, and pinned by the `h1-cs`
 * golden. Cause two is what this test is about: the resolver then **bound** the best of what came
 * back, and the best was `5AU 5001` at **0.667**. A gate that ranks by score cannot tell that
 * apart from an answer; a gate that ranks by evidence class does not have to try.
 *
 * So the garbage here sits in `md.dimension.Account.code` — the *correctly* scoped index — on
 * purpose. Fixing the scope does not make a 0.667 near-miss into an answer, it only changes which
 * index the near-miss comes from, and that is exactly why the class gate is a second and
 * independent line of defence rather than a restatement of the first.
 *
 * Both directions in one test, because the asymmetry IS the assertion: the right answer binds at
 * EXACT even while the garbage is sitting in the same result slot, and the garbage alone binds
 * nothing at all — it becomes a typed gap, which is the honest answer the era before this had no
 * way to give.
 */
class H1GatePairTest :
    StringSpec({

        "the H1 pair: an EXACT member binds through the 0.667 garbage — and the garbage alone binds NOTHING" {
            // ── direction one: both candidates in one slot ────────────────────────────────
            val bothPresent =
                resolve(
                    "501001" to
                        listOf(
                            accountMember(),
                            // the issues.md candidate, verbatim: `5AU 5001@0.667`
                            garbage(),
                        ),
                )
            val value = valueFor(bothPresent, "501001")

            value.attributionsList.map { it.attributeRef } shouldContainExactly listOf("md.dimension.Account.code")
            val bound = value.attributionsList.single().binding
            bound.ref shouldBe "md.dimension.Account.code#501001"
            bound.targetClass shouldBe TargetClass.TARGET_CLASS_MEMBER
            bound.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_EXACT
            // The garbage is not a weaker binding recorded beside the good one — it is absent.
            // A WEAK candidate never reaches the lattice, so nothing downstream can be tempted
            // by it, and `5AU 5001` cannot be shown to a user as an option either.
            value.attributionsList.none { it.binding.ref.contains("5AU") }.shouldBeTrue()
            bothPresent.gapsList.none { it.valueId == value.id }.shouldBeTrue()

            // ── direction two: the garbage alone ─────────────────────────────────────────
            val garbageOnly = resolve("501001" to listOf(garbage()))
            val unbound = valueFor(garbageOnly, "501001")

            unbound.attributionsCount shouldBe 0
            val gap = garbageOnly.gapsList.single { it.valueId == unbound.id }
            // G4, not G3: the user DID name the axis (*účtu* scoped the lookup), so this is a
            // method miss inside a known scope — which is what a P2.3 widening round acts on.
            gap.kind shouldBe GapKind.GAP_KIND_G4_METHOD_MISS
            gap.disposition shouldBe Disposition.DISPOSITION_UNRESOLVED
        }

        "the account mention still binds either way — one bad candidate does not poison the question" {
            val garbageOnly = resolve("501001" to listOf(garbage()))
            val ucet = garbageOnly.mentionsList.single { it.span.text == "účtu" }
            ucet.bindingsList
                .first()
                .ref shouldBe "md.dimension.Account"
        }
    }) {
    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()

        private val CASE: JsonObject = loadJson("/lattice/h1-cs.case.json")

        /** Run the H1 question with one query's candidate list replaced. */
        private fun resolve(override: Pair<String, List<FuzzyMatch>>): ResolutionState {
            val pipeline =
                ResolverPipeline(
                    FakeNlp(
                        merge(
                            loadJson("/lattice/${CASE["parseFile"]!!.jsonPrimitive.content}"),
                            AnalyzeResponse.newBuilder(),
                        ).build(),
                    ),
                    FakeFuzzy(byQuery() + override),
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                )
            val request =
                ResolveRequest
                    .newBuilder()
                    .setConversationId("h1-gate-pair")
                    .setFresh(
                        FreshQuestion
                            .newBuilder()
                            .setText(CASE["text"]!!.jsonPrimitive.content)
                            .setLocale(CASE["locale"]!!.jsonPrimitive.content),
                    ).setRegistry(merge(CASE["registry"]!!.jsonObject, Registry.newBuilder()).build())
                    .build()
            return runBlocking { pipeline.resolve(request) }.resolutionState
        }

        private fun valueFor(
            state: ResolutionState,
            text: String,
        ) = state.valuesList.single { it.span.text == text }

        /** The vocabulary the h1 fixture declares, minus whatever the caller is overriding. */
        private fun byQuery(): Map<String, List<FuzzyMatch>> =
            CASE["matcher"]!!
                .jsonObject["byQuery"]!!
                .jsonObject
                .mapValues { (_, matches) ->
                    matches.jsonArray.map { merge(it.jsonObject, FuzzyMatch.newBuilder()).build() }
                }

        /** The account code, matched exactly. What the question actually asked for. */
        private fun accountMember(): FuzzyMatch = member("501001", "501001", 1.0)

        /**
         * `5AU 5001@0.667` — the top hit of the 2026-07-28 log, in the correctly scoped index.
         * A member row, so nothing authored a method for it: pure surface similarity, which is
         * precisely what [org.tatrman.resolver.model.ResolverThresholds.strong] exists to floor.
         */
        private fun garbage(): FuzzyMatch = member("5AU 5001", "5AU 5001", 0.667)

        private fun member(
            id: String,
            candidate: String,
            score: Double,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(id)
                .setCandidate(candidate)
                .setScore(score)
                .setCategory("md.dimension.Account.code")
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
                    H1GatePairTest::class.java.getResource(resource)?.readText()
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

    /** Answers by query text, category-scoped per slot — the same contract as `LatticeGoldenTest`'s. */
    private class FakeFuzzy(
        private val byQuery: Map<String, List<FuzzyMatch>>,
    ) : FuzzyClient {
        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val scoped = span.categoriesList.toSet()
                builder.addResults(
                    FuzzyMatchResponse
                        .newBuilder()
                        .addAllMatches(
                            byQuery[span.query].orEmpty().filter { scoped.isEmpty() || it.category in scoped },
                        ).setMatchedAlgorithm("TATRMAN"),
                )
            }
            return builder.build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }
}
