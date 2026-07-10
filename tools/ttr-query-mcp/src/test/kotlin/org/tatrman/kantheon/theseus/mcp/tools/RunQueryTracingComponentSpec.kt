package org.tatrman.kantheon.theseus.mcp.tools

import com.google.protobuf.kotlin.toByteString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
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
import org.tatrman.kantheon.theseus.mcp.mcp.InstrumentedTool
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fork Stage 4.1 T3 — observability. Proves the `run_query` chain emits **one
 * trace with properly-nested spans** in-process: theseus-mcp's tool boundary is
 * the root, Theseus's orchestration spans nest under it, and each downstream
 * stage (Proteus parse/detect, Argos validate, Kyklop dispatch) is a child of
 * `theseus.run`.
 *
 * Both the theseus-mcp [InstrumentedTool] root span and the in-process
 * [TheseusServiceImpl] orchestration spans export to the **same** SDK (an
 * in-memory exporter), so the in-process seam is captured end-to-end. Across
 * pods the same nesting is delivered by gRPC auto-instrumentation, verified in
 * the separate integration-test suite (planning-conventions §4).
 */
class RunQueryTracingComponentSpec :
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

        fun theseus(sdk: OpenTelemetrySdk): TheseusServiceImpl {
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
                sdk,
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

        "run_query emits one trace nesting mcp.tool.query → theseus.run → parse/detect/validate/dispatch" {
            val exporter = InMemorySpanExporter.create()
            val sdk =
                OpenTelemetrySdk
                    .builder()
                    .setTracerProvider(
                        SdkTracerProvider
                            .builder()
                            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                            .build(),
                    ).build()

            runBlocking {
                // The tool is wrapped in InstrumentedTool exactly as in production wiring,
                // sharing the SDK with the in-process Theseus.
                val tool =
                    InstrumentedTool(
                        QueryTool(cfg, runnerOver(theseus(sdk)), fakeMetadata),
                        AtomicInteger(0),
                        SimpleMeterRegistry(),
                        sdk,
                    )
                val identity = UserIdentity(id = "alice", roles = setOf("analyst"), source = IdentitySource.TOKEN)

                val res =
                    tool.execute(
                        callToolRequest(mapOf("source" to "SELECT id FROM customers", "source_language" to "sql")),
                        identity = identity,
                    )
                res.isError shouldBe false
            }

            val spans = exporter.finishedSpanItems.associateBy { it.name }
            val root = spans.getValue("mcp.tool.query")
            val theseusRun = spans.getValue("theseus.run")

            // Single trace across the whole in-process chain.
            exporter.finishedSpanItems.map { it.traceId }.toSet() shouldBe setOf(root.traceId)

            // Root is the tool boundary; theseus.run nests under it.
            root.parentSpanContext.isValid shouldBe false
            theseusRun.parentSpanId shouldBe root.spanId

            // The orchestration stages are children of theseus.run.
            val stages = listOf("theseus.detect_schema", "theseus.parse", "theseus.validate", "theseus.dispatch")
            spans.keys shouldContainAll stages
            stages.forEach { name ->
                spans.getValue(name).parentSpanId shouldBe theseusRun.spanId
            }
        }
    })
