// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
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
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.grpc.ResolverGrpcService
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.LookupRoundConfig
import org.tatrman.resolver.pipeline.LookupRounds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.DeclaredVocabularyEntry
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolverServiceGrpcKt

/**
 * RV-P2.4.T6 — the H1′ re-gate pair, driven **over a real gRPC channel**.
 *
 * Everything else in P2.4 exercises `ReGate` in-process. This is the one that proves the rpc
 * exists, that `GateRequest`/`GateResponse` survive serialization, and that a caller holding
 * nothing but a lattice from turn 1 can drive turn 2 — which is precisely how the Golem loop will
 * use it, one process away.
 *
 * The fixture is `conformance/calls/gate-h1prime-correction.json`, alongside the existing
 * `resolve.bind:v1` seeds. It is the first fixture with a `"surface": "grpc"` turn: the door-tier
 * seeds go through the MCP door, and this one deliberately does not, because P2.4 built the rpc
 * and **not** an MCP door for it (see the list — T2 specifies the rpc, T6 says "end-to-end over
 * gRPC", and a lattice-shaped MCP arg surface is its own piece of design work).
 */
class GateConformanceTest :
    StringSpec({

        "gate-h1prime-correction: the typo gaps, the correction closes it, over the wire" {
            val fixture = loadJson("/conformance/calls/gate-h1prime-correction.json")
            val turns = fixture["turns"]!!.jsonArray
            val bind = turns[0].jsonObject
            val regate = turns[1].jsonObject

            val fuzzy =
                ConformanceFuzzy(
                    byQuery = fixtureVocabulary(bind["fixture"]!!.jsonPrimitive.content),
                    lookups =
                        regate["lookups"]!!.jsonObject.mapValues { (_, rows) ->
                            rows.jsonArray.map { merge(it.jsonObject, FuzzyMatch.newBuilder()).build() }
                        },
                )

            withGrpc(fuzzy) { stub ->
                // ---- turn 1: resolve.bind:v1 -------------------------------------------
                val resolved =
                    stub.resolve(
                        ResolveRequest
                            .newBuilder()
                            .setConversationId(bind["args"]!!.jsonObject["conversation_id"]!!.jsonPrimitive.content)
                            .setFresh(
                                FreshQuestion
                                    .newBuilder()
                                    .setText(bind["args"]!!.jsonObject["text"]!!.jsonPrimitive.content)
                                    .setLocale(bind["args"]!!.jsonObject["locale"]!!.jsonPrimitive.content),
                            ).setRegistry(registryFor(bind["fixture"]!!.jsonPrimitive.content))
                            .build(),
                    )

                resolved.hasResolution().shouldBeTrue()
                resolved.resolutionState.gapsList.map { it.kind.name } shouldContainExactly
                    expected(bind, "gap_kinds")
                // the refusal-over-guess invariant every fixture in this suite asserts
                resolved.resolution.bindingsList
                    .filter { it.hasDomain() }
                    .all { it.provenance.score >= ResolverThresholds.LIVE.bind }
                    .shouldBeTrue()

                // ---- turn 2: resolve.gate:v1, carrying turn 1's lattice ------------------
                val gated =
                    stub.gate(
                        GateRequest
                            .newBuilder()
                            .setLattice(resolved.resolutionState)
                            .addAllHypotheses(
                                regate["hypotheses"]!!.jsonArray.map {
                                    merge(it.jsonObject, Hypothesis.newBuilder()).build()
                                },
                            ).build(),
                    )

                gated.gatedBindingsList.map { it.ref } shouldContainExactly expected(regate, "gated_refs")
                gated.gatedBindingsList.map { it.evidenceClass.name } shouldContainExactly
                    expected(regate, "evidence_classes")
                gated.gatedBindingsList
                    .single()
                    .producer.proposingRung shouldBe
                    regate["expect"]!!.jsonObject["proposing_rung"]!!.jsonPrimitive.content
                gated.updatedGapsList.map { it.kind.name } shouldContainExactly expected(regate, "gap_kinds")
                gated.outcomesList
                    .single()
                    .accepted
                    .shouldBeTrue()
            }
        }
    }) {
    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()

        private fun expected(
            turn: JsonObject,
            key: String,
        ): List<String> =
            turn["expect"]!!
                .jsonObject[key]!!
                .jsonArray
                .map { it.jsonPrimitive.content }

        /** Stand up the real gRPC service on an in-process channel, run, tear down. */
        private fun withGrpc(
            fuzzy: FuzzyClient,
            block: suspend (ResolverServiceGrpcKt.ResolverServiceCoroutineStub) -> Unit,
        ) {
            val name = InProcessServerBuilder.generateName()
            val server =
                InProcessServerBuilder
                    .forName(name)
                    .directExecutor()
                    .addService(ResolverGrpcService(pipelineFor(fuzzy)))
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
            try {
                runBlocking { block(ResolverServiceGrpcKt.ResolverServiceCoroutineStub(channel)) }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }

        private fun pipelineFor(fuzzy: FuzzyClient): ResolverPipeline =
            ResolverPipeline(
                FakeNlp(
                    merge(
                        loadJson("/lattice/h1prime-cs.parse.json"),
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
                                        category = "md.dimension.Account.code",
                                        targetRef = "md.dimension.Account",
                                        values = emptyList(),
                                    ),
                                ),
                        ),
                        "snap-gate-conformance",
                    ),
                    ResolverThresholds.LIVE,
                ),
                emptyMap(),
                ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                // Rounds off for this fixture: the point is the RE-GATE path, and a lookup round
                // that happened to close the same gap would make turn 2 untestable.
                lookupRounds = LookupRounds(fuzzy, LookupRoundConfig.DISABLED),
            )

        private fun registryFor(fixture: String): Registry =
            merge(loadJson("/lattice/$fixture.case.json")["registry"]!!.jsonObject, Registry.newBuilder()).build()

        private fun fixtureVocabulary(fixture: String): Map<String, List<FuzzyMatch>> =
            loadJson("/lattice/$fixture.case.json")["matcher"]!!
                .jsonObject["byQuery"]!!
                .jsonObject
                .mapValues { (_, matches) ->
                    matches.jsonArray.map { merge(it.jsonObject, FuzzyMatch.newBuilder()).build() }
                }

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
                    GateConformanceTest::class.java.getResource(resource)?.readText()
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

    private class ConformanceFuzzy(
        private val byQuery: Map<String, List<FuzzyMatch>>,
        private val lookups: Map<String, List<FuzzyMatch>>,
    ) : FuzzyClient {
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

        override suspend fun lookup(request: LookupRequest): LookupResponse =
            LookupResponse.newBuilder().addAllCandidates(lookups[request.term].orEmpty()).build()

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }
}
