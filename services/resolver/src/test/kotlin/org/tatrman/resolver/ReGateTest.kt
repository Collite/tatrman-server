// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
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
import org.tatrman.resolver.pipeline.LookupRoundConfig
import org.tatrman.resolver.pipeline.LookupRounds
import org.tatrman.resolver.pipeline.ReGate
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.DeclaredVocabularyEntry
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.GateResponse
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.Span

/**
 * RV-P2.4.T1/T4 — **`resolve.gate:v1`**, the re-gate sibling (Q-13 RULED = B).
 *
 * The tool exists so an LLM rung can be *wrong safely*. A rung reads a lattice, forms a hypothesis
 * — *"`5010O1` is a typo for `501001`"* — and hands it back; the core turns it into a lexicon
 * question and puts the answer through the **same gate** as any other candidate. RV-7's
 * proposer-not-binder, structurally: there is no path in this tool from "a rung said so" to "it is
 * bound".
 *
 * Every case here gates a **real lattice** — one the pipeline actually produced from the h1′/h2
 * fixtures — rather than a hand-built one, because the interesting part of the contract is what
 * happens to the *gaps* afterwards, and a hand-built lattice would let the test choose those.
 */
class ReGateTest :
    StringSpec({

        "(a) H1′ — a correct correction survives the gate and arrives with its provenance" {
            val fuzzy = GateFuzzy(answers = mapOf("501001" to listOf(member("501001", ACCOUNT_CODE))))
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
                )

            val binding = response.gatedBindingsList.single()
            binding.ref shouldBe "$ACCOUNT_CODE#501001"
            // `gate_evidence` is the evidence class, and it is EXACT for the same reason any other
            // member that matched itself is: the hypothesis bought no trust, the lookup did.
            binding.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_EXACT
            binding.producer.proposingRung shouldBe "local"

            response.outcomesList
                .single()
                .accepted
                .shouldBeTrue()
            // (e) the gaps are recomputed, not patched: the G4 this correction closed is gone.
            response.updatedGapsList shouldBe emptyList()
            response.rungLogEntry.rung shouldBe "local"
            response.rungLogEntry.action shouldBe ReGate.ACTION
            response.rungLogEntry.bindingsAdded shouldBe 1

            // the corrected surface is what was looked up, scoped to the ref the rung proposed
            fuzzy.lookups.single().term shouldBe "501001"
            fuzzy.lookups.single().categoriesList shouldContainExactly listOf(ACCOUNT_CODE)
        }

        "(b) a wrong-but-plausible hypothesis binds NOTHING, and the gap it aimed at is untouched" {
            // The ref exists; the term simply is not in it. A rung guessing `501002` gets the same
            // answer as a rung guessing nothing.
            val fuzzy = GateFuzzy(answers = emptyMap())
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    correction("5010O1", 20, 26, to = "501002", ref = ACCOUNT_CODE, rung = "local"),
                )

            response.gatedBindingsList shouldBe emptyList()
            val outcome = response.outcomesList.single()
            outcome.accepted.shouldBeFalse()
            outcome.reason shouldBe ReGate.Reason.NO_CANDIDATE
            // unchanged, and still typed — the ladder is exactly where it was, and knows it
            response.updatedGapsList.single().kind shouldBe GapKind.GAP_KIND_G4_METHOD_MISS
        }

        "a candidate that binds something OTHER than the proposed ref is a contradiction, not a confirmation" {
            val fuzzy = GateFuzzy(answers = mapOf("5010O1" to listOf(declared("md.measure.cost"))))
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    hypothesis("5010O1", 20, 26, ref = ACCOUNT_CODE, rung = "capable"),
                )
            response.gatedBindingsList shouldBe emptyList()
            response.outcomesList.single().reason shouldBe ReGate.Reason.REF_MISMATCH
        }

        "a candidate the class floor refuses is reported as WEAK, not quietly dropped" {
            val fuzzy = GateFuzzy(answers = mapOf("501001" to listOf(member("5AU 5001", ACCOUNT_CODE, 0.62))))
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
                )
            response.gatedBindingsList shouldBe emptyList()
            response.outcomesList.single().reason shouldBe ReGate.Reason.WEAK
        }

        "(c) H2 pre-learning — vocabulary that does not exist yet binds nothing, and that is correct" {
            val fuzzy = GateFuzzy(answers = emptyMap())
            val h2 = h2Lattice()
            val stanic = h2.mentionsList.single { it.span.text == "čerpacích stanic" }
            val response =
                gate(
                    fuzzy,
                    lattice = h2,
                    hypothesis(
                        stanic.span.text,
                        stanic.span.start,
                        stanic.span.end,
                        ref = "er.fuel_station",
                        rung = "capable",
                    ),
                )

            response.gatedBindingsList shouldBe emptyList()
            response.outcomesList.single().reason shouldBe ReGate.Reason.NO_CANDIDATE
            // The G1 stays. This is the ask/learning path's cue, and forcing a binding here is
            // precisely the issues.md §2 failure the whole effort is aimed at.
            response.updatedGapsList.any { it.kind == GapKind.GAP_KIND_G1_UNBOUND }.shouldBeTrue()
        }

        "(d) a mixed batch partially succeeds, and every hypothesis gets its own verdict" {
            val fuzzy = GateFuzzy(answers = mapOf("501001" to listOf(member("501001", ACCOUNT_CODE))))
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
                    correction("5010O1", 20, 26, to = "999999", ref = ACCOUNT_CODE, rung = "local"),
                    // a span this lattice does not have: the caller is gating against a stale one
                    hypothesis("Brno", 400, 404, ref = "er.branch", rung = "local"),
                )

            response.gatedBindingsList.size shouldBe 1
            response.outcomesList.map { it.accepted } shouldContainExactly listOf(true, false, false)
            response.outcomesList[1].reason shouldBe ReGate.Reason.NO_CANDIDATE
            response.outcomesList[2].reason shouldBe ReGate.Reason.NO_SPAN
            // one entry per escalate→gate PAIR (RV-11), carrying everything that was proposed
            response.rungLogEntry.hypothesesCount shouldBe 3
            response.rungLogEntry.bindingsAdded shouldBe 1
        }

        "(T4) gating the same batch twice is the same call twice — stateless, and idempotent" {
            val hypotheses =
                arrayOf(
                    correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
                    hypothesis("Brno", 400, 404, ref = "er.branch", rung = "local"),
                )
            val lattice = h1primeLattice()
            val first = gate(GateFuzzy(mapOf("501001" to listOf(member("501001", ACCOUNT_CODE)))), lattice, *hypotheses)
            val second =
                gate(GateFuzzy(mapOf("501001" to listOf(member("501001", ACCOUNT_CODE)))), lattice, *hypotheses)

            // Byte-identical. The service holds nothing between calls, so a retry after a dropped
            // connection cannot double-count a binding or advance a round — which is what the
            // Golem loop's retry semantics rest on.
            first shouldBe second
        }

        "the caller's lattice is never mutated — `updated_gaps` describes a world it can choose to adopt" {
            val lattice = h1primeLattice()
            val before = lattice.toByteArray().toList()
            gate(
                GateFuzzy(mapOf("501001" to listOf(member("501001", ACCOUNT_CODE)))),
                lattice,
                correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
            )
            lattice.toByteArray().toList() shouldBe before
        }
    }) {
    private companion object {
        private const val ACCOUNT_CODE = "md.dimension.Account.code"
        private val json = Json { ignoreUnknownKeys = true }
        private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()

        private fun gate(
            fuzzy: FuzzyClient,
            lattice: ResolutionState,
            vararg hypotheses: Hypothesis,
        ): GateResponse =
            runBlocking {
                pipelineFor(fuzzy, "h1-cs").gate(
                    GateRequest
                        .newBuilder()
                        .setLattice(lattice)
                        .addAllHypotheses(hypotheses.toList())
                        .build(),
                )
            }

        /** A real lattice, produced by the real pipeline from the fixture. */
        private fun h1primeLattice(): ResolutionState = resolveFixture("h1prime-cs")

        private fun h2Lattice(): ResolutionState = resolveFixture("h2-cs")

        private fun resolveFixture(id: String): ResolutionState {
            val case = loadJson("/lattice/$id.case.json")
            val fuzzy = FixtureFuzzy(case)
            return runBlocking {
                pipelineFor(fuzzy, id).resolve(
                    ResolveRequest
                        .newBuilder()
                        .setConversationId(id)
                        .setFresh(
                            FreshQuestion
                                .newBuilder()
                                .setText(case["text"]!!.jsonPrimitive.content)
                                .setLocale(case["locale"]!!.jsonPrimitive.content),
                        ).setRegistry(merge(case["registry"]!!.jsonObject, Registry.newBuilder()).build())
                        .build(),
                )
            }.resolutionState
        }

        /**
         * The gate reads its vocabulary from the SNAPSHOT registry, not from a per-request override
         * (see `ResolverPipeline.gate`), so the account entity is declared here — this is the seam
         * the ⚑ on that method is about.
         */
        private fun pipelineFor(
            fuzzy: FuzzyClient,
            fixture: String,
        ): ResolverPipeline =
            ResolverPipeline(
                FakeNlp(
                    merge(
                        loadJson(
                            "/lattice/${loadJson("/lattice/$fixture.case.json")["parseFile"]!!.jsonPrimitive.content}",
                        ),
                        AnalyzeResponse.newBuilder(),
                    ).build(),
                ),
                fuzzy,
                SnapshotRegistry(
                    StubRegistrySource(
                        DeclaredVocabulary(
                            entries =
                                listOf(
                                    DeclaredVocabularyEntry(
                                        category = ACCOUNT_CODE,
                                        targetRef = "md.dimension.Account",
                                        values = emptyList(),
                                    ),
                                ),
                        ),
                        "snap-gate",
                    ),
                    ResolverThresholds.LIVE,
                ),
                emptyMap(),
                ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                lookupRounds = LookupRounds(fuzzy, LookupRoundConfig.DISABLED),
            )

        private fun hypothesis(
            text: String,
            start: Int,
            end: Int,
            ref: String,
            rung: String,
        ): Hypothesis =
            Hypothesis
                .newBuilder()
                .setSpan(
                    Span
                        .newBuilder()
                        .setStart(start)
                        .setEnd(end)
                        .setText(text),
                ).setRef(ref)
                .setProposingRung(rung)
                .build()

        private fun correction(
            text: String,
            start: Int,
            end: Int,
            to: String,
            ref: String,
            rung: String,
        ): Hypothesis = hypothesis(text, start, end, ref, rung).toBuilder().setCorrection(to).build()

        private fun member(
            id: String,
            category: String,
            score: Double = 1.0,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(id)
                .setCandidate(id)
                .setScore(score)
                .setCategory(category)
                .setSource(SourceTag.MEMBER)
                .build()

        private fun declared(targetRef: String): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:$targetRef")
                .setCandidate(targetRef)
                .setScore(1.0)
                .setCategory(targetRef)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(targetRef)
                .setMatchMethod("EXACT")
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
                    ReGateTest::class.java.getResource(resource)?.readText()
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

    /** Replays a lattice fixture's vocabulary, so the gated lattices are the real ones. */
    private class FixtureFuzzy(
        case: JsonObject,
    ) : FuzzyClient {
        private val byQuery: Map<String, List<FuzzyMatch>> =
            case["matcher"]!!
                .jsonObject["byQuery"]!!
                .jsonObject
                .mapValues { (_, matches) ->
                    matches.jsonArray.map { merge(it.jsonObject, FuzzyMatch.newBuilder()).build() }
                }

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val scoped = span.categoriesList.toSet()
                builder.addResults(
                    FuzzyMatchResponse.newBuilder().addAllMatches(
                        byQuery[span.query].orEmpty().filter { scoped.isEmpty() || it.category in scoped },
                    ),
                )
            }
            return builder.build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }

    /** Answers `Lookup` by term and records every request — the gate's only upstream. */
    private class GateFuzzy(
        private val answers: Map<String, List<FuzzyMatch>>,
    ) : FuzzyClient {
        val lookups: MutableList<LookupRequest> = mutableListOf()

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse =
            BatchMatchResponse.getDefaultInstance()

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            lookups += request
            return LookupResponse.newBuilder().addAllCandidates(answers[request.term].orEmpty()).build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }
}
