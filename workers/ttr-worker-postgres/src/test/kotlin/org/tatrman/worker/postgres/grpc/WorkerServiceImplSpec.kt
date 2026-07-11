package org.tatrman.worker.postgres.grpc

import org.tatrman.meta.v1.OverallStatus
import org.tatrman.common.v1.Severity
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.GetCapabilitiesRequest
import org.tatrman.worker.v1.GetStatusRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.tatrman.worker.postgres.client.TranslatorClient
import org.tatrman.worker.postgres.client.TranslatorHealth
import org.tatrman.worker.postgres.connection.ConnectionConfig
import org.tatrman.worker.postgres.connection.ConnectionPoolManager
import org.tatrman.worker.postgres.pipeline.ExecutePipeline
import java.time.Instant

class WorkerServiceImplSpec :
    StringSpec({
        val limits =
            ExecutePipeline.ExecutionLimits(
                defaultBatchSizeRows = 10_000,
                maxBatchSizeRows = 100_000,
                defaultTimeoutSeconds = 300,
                maxTimeoutSeconds = 600,
                maxBlobBytesPerCell = 8 * 1024 * 1024,
            )

        val noopTranslator =
            object : TranslatorClient {
                override suspend fun unparse(request: UnparseRequest): UnparseResponse =
                    UnparseResponse.getDefaultInstance()

                override suspend fun probe() = Unit
            }

        fun service(
            connections: Map<String, ConnectionConfig>,
            seedProbesOk: Boolean = true,
            translatorOk: Boolean = true,
        ): Pair<WorkerServiceImpl, ConnectionPoolManager> {
            val pool = ConnectionPoolManager(connections)
            if (seedProbesOk) {
                connections.keys.forEach { id ->
                    pool.recordProbeResult(
                        ConnectionPoolManager.ProbeResult(
                            connectionId = id,
                            connected = true,
                            lastError = "",
                            lastProbed = Instant.now(),
                        ),
                    )
                }
            }
            val translatorHealth = TranslatorHealth()
            if (translatorOk) translatorHealth.recordSuccess()
            val pipeline = ExecutePipeline(pool, noopTranslator, limits)
            val svc =
                WorkerServiceImpl(
                    pipeline = pipeline,
                    pool = pool,
                    translatorHealth = translatorHealth,
                    capabilities =
                        WorkerServiceImpl.WorkerCapabilities(
                            engineName = "postgres",
                            engineVersion = "PostgreSQL 16",
                            limits = limits,
                        ),
                )
            return svc to pool
        }

        fun pgConn(
            id: String,
            database: String = "midas",
            defaultSchema: String = "public",
        ) = ConnectionConfig(
            id = id,
            jdbcUrl = "jdbc:postgresql://h:5432/$database",
            username = "u",
            password = "p",
            database = database,
            defaultSchema = defaultSchema,
        )

        "GetCapabilities advertises postgres + POSTGRESQL dialect + stateful=false" {
            runBlocking {
                val (svc, _) = service(mapOf("pg-midas" to pgConn("pg-midas")))
                val resp = svc.getCapabilities(GetCapabilitiesRequest.getDefaultInstance())
                resp.engineName shouldBe "postgres"
                resp.engineVersion shouldBe "PostgreSQL 16"
                resp.supportedLanguagesList shouldContain "SQL"
                resp.supportedDialectsList shouldContain "POSTGRESQL"
                resp.supportedConnectionsList shouldContain "pg-midas"
                resp.supportsStatefulSessions shouldBe false
                resp.maxConcurrentSessions shouldBe 0
                resp.limits.defaultTimeoutSeconds shouldBe 300L
                resp.limits.maxBatchSizeRows shouldBe 100_000
                resp.limits.maxBlobBytesPerCell shouldBe (8 * 1024 * 1024).toLong()
            }
        }

        "GetStatus reports OK when DB connection probe is up and translator is OK" {
            runBlocking {
                val (svc, _) = service(mapOf("pg-midas" to pgConn("pg-midas")))
                val resp = svc.getStatus(GetStatusRequest.getDefaultInstance())
                resp.ready shouldBe true
                resp.overallStatus shouldBe OverallStatus.OK
                val cs = resp.connectionsList.first { it.connectionId == "pg-midas" }
                cs.connected shouldBe true
                cs.database shouldBe "midas"
                cs.defaultSchema shouldBe "public"
                cs.jdbcUrl shouldContainStr "jdbc:postgresql://"
            }
        }

        "GetStatus reports DEGRADED when no connections are configured" {
            runBlocking {
                val (svc, _) = service(emptyMap())
                val resp = svc.getStatus(GetStatusRequest.getDefaultInstance())
                resp.ready shouldBe false
                resp.overallStatus shouldBe OverallStatus.DEGRADED
            }
        }

        "GetStatus reports DOWN when every configured connection failed its probe" {
            runBlocking {
                val (svc, pool) = service(mapOf("pg-midas" to pgConn("pg-midas")), seedProbesOk = false)
                pool.recordProbeResult(
                    ConnectionPoolManager.ProbeResult(
                        connectionId = "pg-midas",
                        connected = false,
                        lastError = "FATAL: password authentication failed for user \"u\"",
                        lastProbed = Instant.now(),
                    ),
                )
                val resp = svc.getStatus(GetStatusRequest.getDefaultInstance())
                resp.ready shouldBe false
                resp.overallStatus shouldBe OverallStatus.DOWN
                resp.connectionsList.first().connected shouldBe false
                resp.connectionsList.first().lastError shouldContainStr "password authentication failed"
            }
        }

        "GetStatus reports DEGRADED when translator is DOWN even though DB is up" {
            runBlocking {
                val (svc, _) = service(mapOf("pg-midas" to pgConn("pg-midas")), translatorOk = false)
                val resp = svc.getStatus(GetStatusRequest.getDefaultInstance())
                resp.ready shouldBe false
                resp.overallStatus shouldBe OverallStatus.DEGRADED
                resp.dependenciesList.first().name shouldBe "translator"
                resp.dependenciesList.first().status shouldBe OverallStatus.OVERALL_STATUS_UNSPECIFIED
            }
        }

        "GetStatus reports translator OK when health was recorded successful" {
            runBlocking {
                val (svc, _) = service(mapOf("pg-midas" to pgConn("pg-midas")))
                val resp = svc.getStatus(GetStatusRequest.getDefaultInstance())
                resp.dependenciesList.first().name shouldBe "translator"
                resp.dependenciesList.first().status shouldBe OverallStatus.OK
            }
        }

        "GetCapabilities advertises per-connection database + default schema" {
            runBlocking {
                val (svc, _) =
                    service(
                        mapOf(
                            "pg-midas" to
                                pgConn("pg-midas", database = "midas", defaultSchema = "public"),
                            "pg-analytics" to
                                pgConn("pg-analytics", database = "analytics", defaultSchema = "reporting"),
                        ),
                    )
                val resp = svc.getCapabilities(GetCapabilitiesRequest.getDefaultInstance())
                val byId = resp.connectionsList.associateBy { it.connectionId }
                byId.keys shouldContainExactlyInAnyOrder setOf("pg-midas", "pg-analytics")
                byId["pg-midas"]!!.database shouldBe "midas"
                byId["pg-midas"]!!.defaultSchema shouldBe "public"
                byId["pg-analytics"]!!.database shouldBe "analytics"
                byId["pg-analytics"]!!.defaultSchema shouldBe "reporting"
            }
        }

        // The service delegates execute to ExecutePipeline; an unknown connection_id fails closed
        // with a single error batch. Full pipeline behaviour (unparse → JDBC → Arrow) is covered
        // by ExecutePipelineSpec. (Default ExecuteRequest carries connection_id="", absent here.)
        "execute delegates to the pipeline and rejects an unknown connection_id" {
            runBlocking {
                val (svc, _) = service(mapOf("pg-midas" to pgConn("pg-midas")))
                val batches = svc.execute(ExecuteRequest.getDefaultInstance()).toList()
                batches.size shouldBe 1
                val batch = batches.first()
                batch.isFirst shouldBe true
                batch.isLast shouldBe true
                batch.arrowIpc.size() shouldBe 0
                val msg = batch.messagesList.first()
                msg.severity shouldBe Severity.ERROR
                msg.code shouldBe "connection_not_supported"
            }
        }
    })
