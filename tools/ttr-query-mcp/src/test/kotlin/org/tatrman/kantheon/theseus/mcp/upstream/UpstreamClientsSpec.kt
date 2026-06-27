package org.tatrman.kantheon.theseus.mcp.upstream

import org.tatrman.plan.v1.PipelineContext
import org.tatrman.theseus.v1.RunRequest
import org.tatrman.proteus.v1.ParseRequest
import org.tatrman.proteus.v1.ParseResponse
import org.tatrman.proteus.v1.SqlDialect
import org.tatrman.proteus.v1.TranslateRequest
import org.tatrman.proteus.v1.TranslateResponse
import org.tatrman.proteus.v1.UnparseRequest
import org.tatrman.proteus.v1.UnparseResponse
import org.tatrman.argos.v1.ValidateRequest
import org.tatrman.argos.v1.ValidateResponse
import org.tatrman.worker.v1.ResultBatch
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Pure-interface contract tests for the upstream client surface. The Grpc
 * implementations require a live channel; we exercise the *interface*
 * contract here so consumer code (Section E / F) can rely on it.
 */
class UpstreamClientsSpec :
    StringSpec({

        "QueryRunnerClient.run produces a Flow of ResultBatch" {
            val canned =
                ResultBatch
                    .newBuilder()
                    .setBatchIndex(0)
                    .setIsFirst(true)
                    .setIsLast(true)
                    .build()
            val client =
                object : QueryRunnerClient {
                    override fun run(request: RunRequest): Flow<ResultBatch> = flowOf(canned)

                    override suspend fun compile(request: RunRequest) = throw UnsupportedOperationException()
                }
            runBlocking {
                val req = RunRequest.newBuilder().setSource("SELECT 1").build()
                val collected = client.run(req).toList()
                collected shouldHaveSize 1
                collected[0].isFirst shouldBe true
            }
        }

        "TranslatorClient.unparseFromRelNode round-trips dialect" {
            val client =
                object : TranslatorClient {
                    override suspend fun parseToRelNode(request: ParseRequest): ParseResponse =
                        ParseResponse.getDefaultInstance()

                    override suspend fun translate(request: TranslateRequest): TranslateResponse =
                        TranslateResponse
                            .newBuilder()
                            .setOutput("translated:${request.source}")
                            .build()

                    override suspend fun unparseFromRelNode(request: UnparseRequest): UnparseResponse =
                        UnparseResponse
                            .newBuilder()
                            .setOutput("dialect=${request.targetDialect}")
                            .build()
                }
            runBlocking {
                val r =
                    client.unparseFromRelNode(
                        UnparseRequest
                            .newBuilder()
                            .setTargetDialect(SqlDialect.MSSQL)
                            .build(),
                    )
                r.output shouldBe "dialect=MSSQL"
            }
        }

        "ValidatorClient.validate echoes context" {
            val client =
                object : ValidatorClient {
                    override suspend fun validate(request: ValidateRequest): ValidateResponse =
                        ValidateResponse
                            .newBuilder()
                            .setContext(request.context)
                            .build()
                }
            runBlocking {
                val ctx = PipelineContext.newBuilder().setUserId("alice").build()
                val r =
                    client.validate(
                        ValidateRequest.newBuilder().setContext(ctx).build(),
                    )
                r.context.userId shouldBe "alice"
            }
        }
    })
