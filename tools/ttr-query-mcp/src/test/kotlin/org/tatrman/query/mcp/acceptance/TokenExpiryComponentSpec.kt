// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.acceptance

import com.google.protobuf.kotlin.toByteString
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.grpc.QueryServiceImpl
import org.tatrman.query.mcp.QueryMcpConfig
import org.tatrman.mcp.identity.IdentityGate
import org.tatrman.mcp.identity.UserIdentity
import org.tatrman.query.mcp.tools.QueryTool
import org.tatrman.query.mcp.upstream.MetadataServiceClient
import org.tatrman.query.mcp.upstream.QueryRunnerClient
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.query.v1.CompileResponse
import org.tatrman.query.v1.RunRequest
import org.tatrman.worker.v1.ResultBatch
import shared.formatter.core.ColumnDecoration
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.util.Base64

/**
 * Fork Stage 3.6 T4 — token-expiry behavior at the query-mcp layer
 * (kantheon-security §2.1).
 *
 * Two facts, made explicit:
 *
 *  1. **The trust boundary.** query-mcp's IdentityResolver decodes claims but
 *     does NOT verify the token (signature *or* `exp`) — that is the ingress /
 *     sidecar's job (IdentityResolver KDoc). So a well-formed token with an `exp`
 *     in the past still resolves to an identity *here*; expired tokens are meant
 *     to be rejected upstream before reaching this edge. This test pins that
 *     boundary so no one assumes the edge enforces expiry.
 *
 *  2. **Fail-closed shape "at this layer".** When the data call fails (the shape a
 *     post-expiry call takes once the upstream auth terminator rejects it), the
 *     tool surfaces a **clean typed error**: `ok=false`, an error-severity
 *     message, **no partial results** (any batch already streamed is discarded),
 *     no rows leaked. The §2.1 "session expired — resume to continue" park/resume
 *     is the *agent* layer's job (Pythia); query-mcp's contribution is this
 *     clean error with no partial/stale data.
 *
 * Live short-TTL-token expiry against a running Keycloak + stack is deferred to
 * the separate integration-test suite (planning-conventions §4).
 */
class TokenExpiryComponentSpec :
    StringSpec({

        val cfg =
            QueryMcpConfig(
                serverPort = 7307,
                mcpTransport = "streamable-http",
                mcpPath = "/mcp",
                upstream =
                    QueryMcpConfig.Upstream(
                        queryRunner = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                        translator = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                        validator = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                        metadata = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                    ),
                limits =
                    QueryMcpConfig.Limits(
                        rowLimitDefault = 500,
                        rowLimitMax = 5000,
                        requestTimeoutSeconds = 120,
                        maxMessageBytes = 32 * 1024 * 1024,
                    ),
                security = QueryMcpConfig.Security(requireIdentity = true),
                toolTimeoutsMs = mapOf("query" to 120_000L, "compile" to 60_000L),
            )

        val fakeMetadata =
            object : MetadataServiceClient {
                override suspend fun attributeDecorationsByLocalName(): Map<String, ColumnDecoration> = emptyMap()
            }

        fun makeJwt(payload: String): String {
            val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
            val body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
            return "$header.$body.signature-ignored"
        }

        fun arrowIdColumn(values: LongArray): ByteArray {
            val field = Field("id", FieldType.notNullable(ArrowType.Int(64, true)), null)
            val out = ByteArrayOutputStream()
            RootAllocator(Long.MAX_VALUE).use { alloc ->
                VectorSchemaRoot.create(Schema(listOf(field)), alloc).use { root ->
                    val vec = root.getVector("id") as BigIntVector
                    vec.allocateNew(values.size)
                    values.forEachIndexed { i, v -> vec.set(i, v) }
                    root.setRowCount(values.size)
                    ArrowStreamWriter(root, null, Channels.newChannel(out)).use { w ->
                        w.start()
                        w.writeBatch()
                        w.end()
                    }
                }
            }
            return out.toByteArray()
        }

        fun dbPlan(): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode.newBuilder().setTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName("customers"),
                    ),
                ).build()

        // A Query whose worker call streams one batch and THEN fails the data
        // call with UNAUTHENTICATED — the shape a post-expiry call takes once the
        // upstream rejects it. Proves the partial batch is not surfaced.
        fun queryFailingMidStream(): QueryServiceImpl {
            val detect =
                TranslatorDetectClient {
                    org.tatrman.translate.v1.DetectSchemaResponse
                        .newBuilder()
                        .setDecision(org.tatrman.translate.v1.SchemaDecision.CONFIRMED)
                        .setEffectiveSchema(SchemaCode.DB)
                        .build()
                }
            val parse =
                TranslatorClient { req ->
                    org.tatrman.translate.v1.ParseResponse
                        .newBuilder()
                        .setPlan(dbPlan())
                        .setContext(req.context)
                        .build()
                }
            val translate =
                TranslatorTranslateClient { req ->
                    org.tatrman.translate.v1.TranslateResponse
                        .newBuilder()
                        .setOutput("SELECT id FROM customers")
                        .setContext(req.context)
                        .build()
                }
            val validate =
                ValidatorClient { req ->
                    org.tatrman.validate.v1.ValidateResponse
                        .newBuilder()
                        .setPlan(req.plan)
                        .setContext(req.context)
                        .build()
                }
            val dispatch =
                DispatcherClient { _ ->
                    flow {
                        emit(
                            ResultBatch
                                .newBuilder()
                                .setIsFirst(true)
                                .setIsLast(false)
                                .setArrowIpc(arrowIdColumn(longArrayOf(1, 2)).toByteString())
                                .setContext(PipelineContext.getDefaultInstance())
                                .build(),
                        )
                        // Token expired between batches → the worker/DB call now fails auth.
                        throw StatusException(Status.UNAUTHENTICATED.withDescription("access token expired"))
                    }
                }
            return QueryServiceImpl(
                parse,
                detect,
                translate,
                validate,
                dispatch,
                CompiledPlanCache(100, java.time.Duration.ofMinutes(60)),
                RetryPolicy(maxAttempts = 1, initialBackoffMillis = 1, multiplier = 1.0, jitterPercent = 0),
            )
        }

        fun runnerOver(query: QueryServiceImpl): QueryRunnerClient =
            object : QueryRunnerClient {
                override fun run(request: RunRequest): Flow<ResultBatch> = query.run(request)

                override suspend fun compile(request: RunRequest): CompileResponse = query.compile(request)
            }

        fun callToolRequest(): CallToolRequest =
            CallToolRequest(
                params =
                    CallToolRequestParams(
                        name = "query",
                        arguments =
                            buildJsonObject {
                                put("source", JsonPrimitive("SELECT id FROM customers"))
                                put("source_language", JsonPrimitive("sql"))
                            },
                    ),
            )

        "a data-call auth failure mid-session → clean typed error, no partial results" {
            runBlocking {
                val tool = QueryTool(cfg, runnerOver(queryFailingMidStream()), fakeMetadata)
                val identity =
                    UserIdentity(
                        id = "alice",
                        roles = setOf("analyst"),
                        source = org.tatrman.mcp.identity.IdentitySource.TOKEN,
                    )

                val res = tool.execute(callToolRequest(), identity = identity)

                res.isError shouldBe true
                // Clean typed error envelope, not data.
                (res.structuredContent!!["ok"] as JsonPrimitive).content shouldBe "false"
                // No partial results: the one batch streamed before the failure is discarded.
                res.structuredContent!!["rowCount"] shouldBe null
            }
        }

        "trust boundary — query-mcp does not enforce token expiry (delegated to the ingress)" {
            // A well-formed token whose `exp` is far in the past. The edge still
            // resolves claims (signature + expiry are validated upstream); this pins
            // the documented trust boundary so the edge is not mistaken for the
            // expiry enforcement point.
            val expired =
                makeJwt("""{"preferred_username":"alice","exp":1000000000,"realm_access":{"roles":["analyst"]}}""")
            val decision = IdentityGate.decide("Bearer $expired", null, null, requireIdentity = true)
            val allow = decision.shouldBeInstanceOf<IdentityGate.Decision.Allow>()
            val identity = allow.identity.shouldBeInstanceOf<UserIdentity>()
            identity.id shouldBe "alice"
        }
    })
