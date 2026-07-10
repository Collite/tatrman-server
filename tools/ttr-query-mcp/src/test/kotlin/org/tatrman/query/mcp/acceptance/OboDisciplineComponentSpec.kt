package org.tatrman.query.mcp.acceptance

import com.google.protobuf.kotlin.toByteString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import org.tatrman.query.mcp.identity.IdentityGate
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
 * Fork Stage 3.6 T3 — OBO discipline (mocked component test).
 *
 * Composes the real OBO gate ([IdentityGate], the same decision `McpTransport`
 * switches on) with the real `run_query` chain (QueryTool → in-process Query →
 * mocked Translate/Validate/Dispatch → real Arrow → JSON). The Validate stub records the
 * roles it is asked to validate, so we can prove the **negative paths never reach
 * Validate** (kantheon-security §2): the gate fails closed *before* execution.
 *
 *   - valid user bearer → allowed; rows return; Validate sees the user's roles.
 *   - no token (identity required) → fail-closed `missing_user_identity`; Validate
 *     never called (no roleless request reaches it).
 *   - service-identity token (no user claim) → rejected; Validate never called.
 *   - credential hygiene: the rejection surface never echoes the bearer token.
 *
 * Live query-mcp OBO e2e (real HTTP transport + Keycloak) is deferred to the
 * separate integration-test suite (planning-conventions §4).
 */
class OboDisciplineComponentSpec :
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
                // Production posture: identity required at the edge.
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

        // Real in-process Query; the Validate (validator) stub records the roles it sees.
        fun queryWithCapture(seenRoles: MutableList<List<String>>): QueryServiceImpl {
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
                    seenRoles.add(req.context.authRolesList.toList())
                    org.tatrman.validate.v1.ValidateResponse
                        .newBuilder()
                        .setPlan(req.plan)
                        .setContext(req.context)
                        .build()
                }
            val dispatch =
                DispatcherClient { _ ->
                    flowOf(
                        ResultBatch
                            .newBuilder()
                            .setIsFirst(true)
                            .setIsLast(true)
                            .setArrowIpc(arrowIdColumn(longArrayOf(1, 2)).toByteString())
                            .setContext(PipelineContext.getDefaultInstance())
                            .build(),
                    )
                }
            return QueryServiceImpl(
                parse,
                detect,
                translate,
                validate,
                dispatch,
                CompiledPlanCache(100, java.time.Duration.ofMinutes(60)),
                RetryPolicy(maxAttempts = 2, initialBackoffMillis = 1, multiplier = 1.0, jitterPercent = 0),
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

        // Mirror McpTransport's gate: Reject → no execution (transport returns Rule-6);
        // Allow → execute the tool with the resolved identity. Returns the reject code
        // (or null) so a test can assert the gate short-circuited before Validate.
        suspend fun gatedExecute(
            tool: QueryTool,
            authHeader: String?,
        ): Pair<String?, CallToolResult?> =
            when (val d = IdentityGate.decide(authHeader, null, null, cfg.security.requireIdentity)) {
                is IdentityGate.Decision.Reject -> d.code to null
                is IdentityGate.Decision.Allow -> null to tool.execute(callToolRequest(), d.identity)
            }

        "valid user bearer → allowed; rows return; Validate sees the user's roles" {
            runBlocking {
                val seenRoles = mutableListOf<List<String>>()
                val tool = QueryTool(cfg, runnerOver(queryWithCapture(seenRoles)), fakeMetadata)
                val token = makeJwt("""{"preferred_username":"alice","realm_access":{"roles":["analyst"]}}""")

                val (rejectCode, result) = gatedExecute(tool, "Bearer $token")

                rejectCode shouldBe null
                result!!.isError shouldBe false
                (result.structuredContent!!["rowCount"] as JsonPrimitive).content shouldBe "2"
                // The OBO roles travelled to Validate.
                seenRoles.isNotEmpty() shouldBe true
                seenRoles.last() shouldContain "analyst"
            }
        }

        "no token → fail-closed missing_user_identity; Validate is never called" {
            runBlocking {
                val seenRoles = mutableListOf<List<String>>()
                val tool = QueryTool(cfg, runnerOver(queryWithCapture(seenRoles)), fakeMetadata)

                val (rejectCode, result) = gatedExecute(tool, authHeader = null)

                rejectCode shouldBe "missing_user_identity"
                result shouldBe null
                // No roleless request reached Validate — the gate short-circuited.
                seenRoles.isEmpty() shouldBe true
            }
        }

        "service-identity token (no user claim) → rejected; Validate is never called" {
            runBlocking {
                val seenRoles = mutableListOf<List<String>>()
                val tool = QueryTool(cfg, runnerOver(queryWithCapture(seenRoles)), fakeMetadata)
                val svcToken = makeJwt("""{"clientId":"svc-pythia","typ":"Bearer","azp":"svc-pythia"}""")

                val (rejectCode, result) = gatedExecute(tool, "Bearer $svcToken")

                rejectCode shouldBe "missing_user_identity"
                result shouldBe null
                seenRoles.isEmpty() shouldBe true
                // Credential hygiene: the rejection surface never echoes the bearer token.
                val reject =
                    IdentityGate.decide("Bearer $svcToken", null, null, true) as IdentityGate.Decision.Reject
                reject.message shouldNotContain svcToken
            }
        }
    })
