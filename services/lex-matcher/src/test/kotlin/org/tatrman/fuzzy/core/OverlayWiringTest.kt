// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.api.GrpcService
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest

/**
 * RV-P1.4 T6 — the overlay slot's last mile: repository → tuple → wire.
 *
 * `OverlayLayerTest` (lex-matcher-core) pins the semantics against a fake store. This pins the one
 * thing only the service can show: that `overlay_version` reaches the wire when a store reports one
 * and stays **UNSET** when it does not. T2 made the field `optional` for exactly that, and an unset
 * proto3 string reads as `""`, so "no overlay" and "an overlay at version ''" would otherwise be
 * the same bytes.
 */
class OverlayWiringTest :
    StringSpec({

        fun cfg() =
            AppConfig(
                serverPort = 7105,
                grpcPort = 7205,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        val loader =
            object : LoaderSource {
                override suspend fun loadNextCache() = mapOf("region" to listOf(Candidate.fromValues("pk-1", "Praha")))
            }

        fun store(v: String?) =
            object : OverlayStore {
                override fun version() = v

                override suspend fun consult(request: OverlayRequest) = OverlayVerdict.EMPTY
            }

        "with no overlay store the version is absent in the tuple and UNSET on the wire" {
            val repo = StringRepository(cfg(), loader)
            runBlocking { repo.forceRefresh() }
            repo.layerVersions().overlayVersion.shouldBeNull()

            val service = GrpcService(FuzzyMatcher(repo), repo)
            val name = "overlay-absent-${System.identityHashCode(service)}"
            val server =
                InProcessServerBuilder
                    .forName(name)
                    .directExecutor()
                    .addService(service)
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
            try {
                runBlocking {
                    val status =
                        FuzzyServiceGrpcKt
                            .FuzzyServiceCoroutineStub(channel)
                            .getStatus(FuzzyStatusRequest.getDefaultInstance())

                    status.layerVersions.hasOverlayVersion() shouldBe false
                }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
                repo.close()
            }
        }

        "a store that reports a version puts it in the tuple and on the wire" {
            val repo = StringRepository(cfg(), loader, overlayStore = store("overlay-7"))
            runBlocking { repo.forceRefresh() }
            repo.layerVersions().overlayVersion shouldBe "overlay-7"

            val service = GrpcService(FuzzyMatcher(repo), repo)
            val name = "overlay-present-${System.identityHashCode(service)}"
            val server =
                InProcessServerBuilder
                    .forName(name)
                    .directExecutor()
                    .addService(service)
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
            try {
                runBlocking {
                    val status =
                        FuzzyServiceGrpcKt
                            .FuzzyServiceCoroutineStub(channel)
                            .getStatus(FuzzyStatusRequest.getDefaultInstance())

                    status.layerVersions.hasOverlayVersion() shouldBe true
                    status.layerVersions.overlayVersion shouldBe "overlay-7"
                }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
                repo.close()
            }
        }
    })
