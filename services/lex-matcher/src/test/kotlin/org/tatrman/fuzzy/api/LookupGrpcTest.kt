// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.api

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.core.TargetClass
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.TargetClass as ProtoTargetClass

/**
 * RV-P1.4 T5 — the Lookup rpc over a real channel.
 *
 * `LookupTest` (lex-matcher-core) pins the semantics; this pins that they survive the wire, which
 * is where the shape is frozen as the P2.3 contract. Two things are only observable here: that the
 * RV-38 target class and the RV-32 margin actually serialise, and that absent stays absent — a
 * proto3 `optional` field left unset is the whole reason `auto_bindable=false` means something.
 */
class LookupGrpcTest :
    StringSpec({

        fun cfg() =
            AppConfig(
                serverPort = 7104,
                grpcPort = 7204,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        // One category per target ref — the convention LexiconArchiveSource produces.
        val loader =
            object : LoaderSource {
                override suspend fun loadNextCache() =
                    mapOf(
                        "md.net" to
                            listOf(
                                Candidate.vocabulary(
                                    "t1",
                                    "čistý obrat",
                                    "md.net",
                                    SourceTag.DECLARED,
                                    "TOKENS",
                                    TargetClass.MODEL_OBJECT,
                                ),
                            ),
                        "md.gross" to
                            listOf(
                                Candidate.vocabulary(
                                    "t2",
                                    "hrubý obrat",
                                    "md.gross",
                                    SourceTag.DECLARED,
                                    "TOKENS",
                                    TargetClass.MODEL_OBJECT,
                                ),
                            ),
                        "op:trend" to
                            listOf(
                                Candidate.vocabulary(
                                    "t3",
                                    "vývoj",
                                    "op:trend",
                                    SourceTag.DECLARED,
                                    "EXACT",
                                    TargetClass.OPERATOR,
                                ),
                            ),
                        "db.t.col" to listOf(Candidate.fromValues("pk-1", "Praha")),
                    )
            }

        fun <T> withStub(block: suspend (FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub) -> T): T {
            val repo = StringRepository(cfg(), loader)
            runBlocking { repo.forceRefresh() }
            val service = GrpcService(FuzzyMatcher(repo), repo)
            val name = "fuzzy-lookup-${System.identityHashCode(service)}"
            val server =
                InProcessServerBuilder
                    .forName(name)
                    .directExecutor()
                    .addService(service)
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
            try {
                return runBlocking { block(FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub(channel)) }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
                repo.close()
            }
        }

        "a class-scoped lookup returns only operators, with the class on the wire" {
            withStub { stub ->
                val resp =
                    stub.lookup(
                        LookupRequest
                            .newBuilder()
                            .setTerm("vývoj")
                            .addTargetClasses(ProtoTargetClass.TARGET_CLASS_OPERATOR)
                            .build(),
                    )

                resp.candidatesCount shouldBe 1
                val hit = resp.getCandidates(0)
                hit.targetRef shouldBe "op:trend"
                hit.targetClass shouldBe ProtoTargetClass.TARGET_CLASS_OPERATOR
                hit.matchMethod shouldBe "EXACT"
                // Not a TOKENS row, so no uniqueness decision applies — and absent must stay absent.
                hit.hasUniquenessMargin() shouldBe false
                hit.hasAutoBindable() shouldBe false
                resp.layerVersions.memberIndexVersionsCount shouldBe 4
                resp.vocabularyVersion.shouldNotBeBlank()
            }
        }

        "an ambiguous cross-category lookup crosses the wire flagged, margin and all" {
            withStub { stub ->
                val resp =
                    stub.lookup(
                        LookupRequest
                            .newBuilder()
                            .setTerm("obrat")
                            .addCategories("md.net")
                            .addCategories("md.gross")
                            .build(),
                    )

                resp.candidatesCount shouldBe 2
                resp.candidatesList.forEach {
                    it.hasAutoBindable() shouldBe true
                    it.autoBindable shouldBe false
                    it.hasUniquenessMargin() shouldBe true
                }
            }
        }

        "unknown categories come back named" {
            withStub { stub ->
                val resp =
                    stub.lookup(
                        LookupRequest
                            .newBuilder()
                            .setTerm("obrat")
                            .addCategories("md.net")
                            .addCategories("md.deleted")
                            .build(),
                    )

                resp.unknownCategoriesList shouldContainExactly listOf("md.deleted")
                resp.candidatesCount shouldBe 1
            }
        }

        "a method override is echoed back, and the candidate still reports the AUTHORED method" {
            withStub { stub ->
                val resp =
                    stub.lookup(
                        LookupRequest
                            .newBuilder()
                            .setTerm("čistý obrat")
                            .addCategories("md.net")
                            .setMethodOverride("EXACT")
                            .build(),
                    )

                resp.appliedMethodOverride shouldBe "EXACT"
                resp.getCandidates(0).matchMethod shouldBe "TOKENS"
            }
        }

        "an unparseable method override fails the call with INVALID_ARGUMENT" {
            // The code matters, not just that it threw. grpc-kotlin maps any exception that is not
            // a StatusException to UNKNOWN, which tells a caller nothing — it cannot distinguish
            // "your request is malformed, retrying will not help" from "the server fell over".
            withStub { stub ->
                val thrown =
                    shouldThrow<StatusException> {
                        stub.lookup(
                            LookupRequest
                                .newBuilder()
                                .setTerm("obrat")
                                .setMethodOverride("SEMANTIC(0.8)")
                                .build(),
                        )
                    }

                thrown.status.code shouldBe Status.Code.INVALID_ARGUMENT
                thrown.status.description!! shouldContain "SEMANTIC(0.8)"
            }
        }

        "a member value carries no target class on the wire" {
            withStub { stub ->
                val resp =
                    stub.lookup(
                        LookupRequest
                            .newBuilder()
                            .setTerm("Praha")
                            .addCategories("db.t.col")
                            .build(),
                    )

                resp.getCandidates(0).targetClass shouldBe ProtoTargetClass.TARGET_CLASS_UNSPECIFIED
            }
        }
    })
