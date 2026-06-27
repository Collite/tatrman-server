package org.tatrman.kantheon.theseus.mcp.tools

import com.google.protobuf.kotlin.toByteString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
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
import org.tatrman.kantheon.theseus.cache.CompiledPlanCache
import org.tatrman.kantheon.theseus.client.DispatcherClient
import org.tatrman.kantheon.theseus.client.TranslatorClient
import org.tatrman.kantheon.theseus.client.TranslatorDetectClient
import org.tatrman.kantheon.theseus.client.TranslatorTranslateClient
import org.tatrman.kantheon.theseus.client.ValidatorClient
import org.tatrman.kantheon.theseus.grpc.TheseusServiceImpl
import org.tatrman.kantheon.theseus.mcp.QueryMcpConfig
import org.tatrman.kantheon.theseus.mcp.identity.IdentitySource
import org.tatrman.kantheon.theseus.mcp.identity.UserIdentity
import org.tatrman.kantheon.theseus.mcp.upstream.MetadataServiceClient
import org.tatrman.kantheon.theseus.mcp.upstream.QueryRunnerClient
import org.tatrman.kantheon.theseus.retry.RetryPolicy
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.theseus.v1.CompileResponse
import org.tatrman.theseus.v1.RunRequest
import org.tatrman.worker.v1.ResultBatch
import shared.formatter.core.ColumnDecoration
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels

/**
 * Fork Stage 3.5 T6 — full-chain component smoke. `run_query` via theseus-mcp's
 * QueryTool → a real in-process **Theseus** (TheseusServiceImpl) → mocked Proteus
 * (parse) → mocked Argos (validate) → mocked Kyklop (dispatch) → a mocked worker
 * emitting real Arrow IPC → decoded to JSON rows in the MCP response.
 *
 * Asserts (a) the labyrinth is walkable: rows come back as JSON; (b) the OBO
 * identity's roles travel as PipelineContext.auth_roles all the way to Argos —
 * the bearer-roles contract (kantheon-security §2/§3) end-to-end. A DataFrame-path
 * variant runs the same chain with a session_id (the Steropes/stateful shape).
 *
 * True on-K3s full-chain e2e is deferred to the separate integration-test suite.
 */
class RunQueryFullChainComponentSpec :
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

        // A worker (Brontes/Steropes) result: real Arrow IPC, one int column `id` = [1, 2].
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

        // Build a real Theseus wired to mocked downstreams; capture the roles Argos sees.
        fun theseusWithCapture(seenRoles: MutableList<List<String>>): TheseusServiceImpl {
            val detect =
                TranslatorDetectClient {
                    org.tatrman.proteus.v1.DetectSchemaResponse
                        .newBuilder()
                        .setDecision(org.tatrman.proteus.v1.SchemaDecision.CONFIRMED)
                        .setEffectiveSchema(SchemaCode.DB)
                        .build()
                }
            val parse =
                TranslatorClient { req ->
                    org.tatrman.proteus.v1.ParseResponse
                        .newBuilder()
                        .setPlan(dbPlan())
                        .setContext(req.context)
                        .build()
                }
            val translate =
                TranslatorTranslateClient { req ->
                    org.tatrman.proteus.v1.TranslateResponse
                        .newBuilder()
                        .setOutput("SELECT id FROM customers")
                        .setContext(req.context)
                        .build()
                }
            val validate =
                ValidatorClient { req ->
                    seenRoles.add(req.context.authRolesList.toList())
                    org.tatrman.argos.v1.ValidateResponse
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
            return TheseusServiceImpl(
                parse,
                detect,
                translate,
                validate,
                dispatch,
                CompiledPlanCache(100, java.time.Duration.ofMinutes(60)),
                RetryPolicy(maxAttempts = 2, initialBackoffMillis = 1, multiplier = 1.0, jitterPercent = 0),
            )
        }

        fun runnerOver(theseus: TheseusServiceImpl): QueryRunnerClient =
            object : QueryRunnerClient {
                override fun run(request: RunRequest): Flow<ResultBatch> = theseus.run(request)

                override suspend fun compile(request: RunRequest): CompileResponse = theseus.compile(request)
            }

        fun callToolRequest(args: Map<String, String>): CallToolRequest =
            CallToolRequest(
                params =
                    CallToolRequestParams(
                        name = "query",
                        arguments =
                            buildJsonObject {
                                args.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                            },
                    ),
            )

        "run_query walks the full chain and returns JSON rows; OBO roles reach Argos" {
            runBlocking {
                val seenRoles = mutableListOf<List<String>>()
                val tool = QueryTool(cfg, runnerOver(theseusWithCapture(seenRoles)), fakeMetadata)
                val identity = UserIdentity(id = "alice", roles = setOf("analyst"), source = IdentitySource.TOKEN)

                val res =
                    tool.execute(
                        callToolRequest(mapOf("source" to "SELECT id FROM customers", "source_language" to "sql")),
                        identity = identity,
                    )

                res.isError shouldBe false
                (res.structuredContent!!["rowCount"] as JsonPrimitive).content shouldBe "2"
                // The OBO identity's roles travelled as PipelineContext.auth_roles to Argos.
                seenRoles.isNotEmpty() shouldBe true
                seenRoles.last() shouldContain "analyst"
            }
        }

        "DataFrame-path variant: a session-scoped run also walks the chain and returns rows" {
            runBlocking {
                val seenRoles = mutableListOf<List<String>>()
                val tool = QueryTool(cfg, runnerOver(theseusWithCapture(seenRoles)), fakeMetadata)
                val identity = UserIdentity(id = "alice", roles = setOf("analyst"), source = IdentitySource.TOKEN)

                val res =
                    tool.execute(
                        callToolRequest(
                            mapOf(
                                "source" to "df.head()",
                                "source_language" to "dfdsl",
                                "session_id" to "sess-1",
                            ),
                        ),
                        identity = identity,
                    )

                res.isError shouldBe false
                (res.structuredContent!!["rowCount"] as JsonPrimitive).content shouldBe "2"
            }
        }
    })
