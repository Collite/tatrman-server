// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.api

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.SpanQuery

/**
 * RG-P2 phase-exit runtime smoke — the gRPC WIRE path (the one layer the model
 * tests don't cover). Boots FuzzyService in-process and drives BatchMatch +
 * GetStatus over a real channel, asserting the additive fields (source,
 * target_ref, provenance, vocabulary_version) reach the wire.
 */
class GrpcServiceSmokeTest :
    StringSpec({

        fun cfg() =
            AppConfig(
                serverPort = 7103,
                grpcPort = 7203,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        val loader =
            object : LoaderSource {
                override suspend fun loadNextCache() =
                    mapOf(
                        "product" to listOf(Candidate.fromValues("p-octavia", "Škoda Octavia")),
                        "measure-term" to listOf(Candidate.vocabulary("t-trzba", "tržba", "md.measure.net")),
                    )
            }

        "BatchMatch + GetStatus round-trip over gRPC with source tags + vocabulary_version" {
            val repo = StringRepository(cfg(), loader)
            runBlocking { repo.forceRefresh() }
            val service = GrpcService(FuzzyMatcher(repo), repo)

            val serverName = "fuzzy-smoke-${System.identityHashCode(service)}"
            val server =
                InProcessServerBuilder
                    .forName(serverName)
                    .directExecutor()
                    .addService(service)
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
            val stub = FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub(channel)

            try {
                runBlocking {
                    val resp =
                        stub.batchMatch(
                            BatchMatchRequest
                                .newBuilder()
                                .addSpans(
                                    SpanQuery
                                        .newBuilder()
                                        .setQuery(
                                            "Škoda Octavia",
                                        ).addCategories("product")
                                        .setLimit(5),
                                ).addSpans(
                                    SpanQuery
                                        .newBuilder()
                                        .setQuery("tržba")
                                        .addCategories("measure-term")
                                        .setLimit(5),
                                ).build(),
                        )
                    resp.resultsCount shouldBe 2
                    val product = resp.getResults(0).getMatches(0)
                    product.candidateId shouldBe "p-octavia"
                    product.source shouldBe SourceTag.MEMBER
                    product.provenance.producer shouldBe "fuzzy"
                    resp.getResults(0).vocabularyVersion.shouldNotBeBlank()

                    val vocab = resp.getResults(1).getMatches(0)
                    vocab.candidateId shouldBe "t-trzba"
                    vocab.source shouldBe SourceTag.VOCABULARY
                    vocab.targetRef shouldBe "md.measure.net"

                    val status = stub.getStatus(FuzzyStatusRequest.getDefaultInstance())
                    status.ready shouldBe true
                    status.vocabularyVersion.shouldNotBeBlank()
                    status.categoriesCount shouldBe 2
                }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
                repo.close()
            }
        }
    })
