package org.tatrman.worker.postgres.pipeline

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ExecutionOptions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.tatrman.worker.postgres.client.TranslatorClient
import org.tatrman.worker.postgres.connection.ConnectionConfig
import org.tatrman.worker.postgres.connection.ConnectionPoolManager
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types

private fun translator(unparseBody: (UnparseRequest) -> UnparseResponse): TranslatorClient =
    object : TranslatorClient {
        override suspend fun unparse(request: UnparseRequest): UnparseResponse = unparseBody(request)

        override suspend fun probe() = Unit
    }

class ExecutePipelineSpec :
    StringSpec({
        val limits =
            ExecutePipeline.ExecutionLimits(
                defaultBatchSizeRows = 100,
                maxBatchSizeRows = 1_000,
                defaultTimeoutSeconds = 30,
                maxTimeoutSeconds = 300,
                maxBlobBytesPerCell = 8 * 1024 * 1024,
            )

        "unknown connection_id surfaces connection_not_supported" {
            runBlocking {
                val pool = ConnectionPoolManager(emptyMap())
                val translator =
                    translator { req ->
                        UnparseResponse
                            .newBuilder()
                            .setOutput("SELECT 1")
                            .setContext(req.context)
                            .build()
                    }
                val pipeline = ExecutePipeline(pool, translator, limits)
                val out =
                    pipeline
                        .execute(
                            ExecuteRequest
                                .newBuilder()
                                .setPlan(scan("public", "positions"))
                                .setContext(PipelineContext.getDefaultInstance())
                                .setConnectionId("missing-conn")
                                .setOptions(ExecutionOptions.getDefaultInstance())
                                .build(),
                        ).toList()
                out.size shouldBe 1
                out[0].messagesList[0].code shouldBe "connection_not_supported"
                out[0].isFirst shouldBe true
                out[0].isLast shouldBe true
            }
        }

        "translator error surfaces translator_failed" {
            runBlocking {
                val pool =
                    ConnectionPoolManager(
                        mapOf(
                            "pg-midas" to
                                ConnectionConfig(
                                    id = "pg-midas",
                                    jdbcUrl = "jdbc:postgresql://nope:5432/midas",
                                    username = "u",
                                    password = "p",
                                    database = "midas",
                                ),
                        ),
                    )
                val translator =
                    translator { req ->
                        UnparseResponse
                            .newBuilder()
                            .setContext(req.context)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("language_not_supported_in_v1.1")
                                    .setHumanMessage("nope"),
                            ).build()
                    }
                val pipeline = ExecutePipeline(pool, translator, limits)
                val out =
                    pipeline
                        .execute(
                            ExecuteRequest
                                .newBuilder()
                                .setPlan(scan("public", "positions"))
                                .setContext(PipelineContext.getDefaultInstance())
                                .setConnectionId("pg-midas")
                                .setOptions(ExecutionOptions.getDefaultInstance())
                                .build(),
                        ).toList()
                out.size shouldBe 1
                out[0].messagesList[0].code shouldBe "translator_failed"
            }
        }

        "activeQueries returns 0 between calls" {
            val pool = ConnectionPoolManager(emptyMap())
            val translator = translator { _ -> UnparseResponse.getDefaultInstance() }
            val pipeline = ExecutePipeline(pool, translator, limits)
            pipeline.activeQueries shouldBe 0
        }

        // The "fake PG path": a fixture plan is unparsed (target dialect POSTGRESQL) and executed
        // against a mocked JDBC ResultSet; the streamed ResultBatches must carry the expected Arrow
        // schema + rows. Proves the unparse → JDBC → Arrow IPC wiring end-to-end without a real DB
        // (the real round-trip is the Stage 1.3 component test).
        "executes a plan against a fake Postgres ResultSet and streams Arrow rows" {
            runBlocking {
                var requestedDialect: SqlDialect? = null
                val translator =
                    translator { req ->
                        requestedDialect = req.targetDialect
                        UnparseResponse
                            .newBuilder()
                            .setOutput("SELECT account_id, amount, label FROM public.positions")
                            .setContext(req.context)
                            .build()
                    }

                val rsMeta = mockk<ResultSetMetaData>()
                every { rsMeta.columnCount } returns 3
                // col 1: account_id BIGINT (int8)
                every { rsMeta.getColumnLabel(1) } returns "account_id"
                every { rsMeta.getColumnName(1) } returns "account_id"
                every { rsMeta.getColumnTypeName(1) } returns "int8"
                every { rsMeta.getColumnType(1) } returns Types.BIGINT
                every { rsMeta.getPrecision(1) } returns 0
                every { rsMeta.getScale(1) } returns 0
                every { rsMeta.isNullable(1) } returns ResultSetMetaData.columnNullable
                // col 2: amount NUMERIC(20,4)
                every { rsMeta.getColumnLabel(2) } returns "amount"
                every { rsMeta.getColumnName(2) } returns "amount"
                every { rsMeta.getColumnTypeName(2) } returns "numeric"
                every { rsMeta.getColumnType(2) } returns Types.NUMERIC
                every { rsMeta.getPrecision(2) } returns 20
                every { rsMeta.getScale(2) } returns 4
                every { rsMeta.isNullable(2) } returns ResultSetMetaData.columnNullable
                // col 3: label VARCHAR
                every { rsMeta.getColumnLabel(3) } returns "label"
                every { rsMeta.getColumnName(3) } returns "label"
                every { rsMeta.getColumnTypeName(3) } returns "varchar"
                every { rsMeta.getColumnType(3) } returns Types.VARCHAR
                every { rsMeta.getPrecision(3) } returns 64
                every { rsMeta.getScale(3) } returns 0
                every { rsMeta.isNullable(3) } returns ResultSetMetaData.columnNullable

                val rs = mockk<ResultSet>(relaxed = true)
                every { rs.metaData } returns rsMeta
                every { rs.next() } returnsMany listOf(true, true, false)
                every { rs.wasNull() } returns false
                every { rs.getLong(1) } returnsMany listOf(1001L, 1002L)
                every { rs.getBigDecimal(2) } returnsMany
                    listOf(BigDecimal("123.4500"), BigDecimal("67.8900"))
                every { rs.getObject(3) } returnsMany listOf("alpha", "beta")

                val stmt = mockk<PreparedStatement>(relaxed = true)
                every { stmt.executeQuery() } returns rs

                val conn = mockk<Connection>(relaxed = true)
                every { conn.prepareStatement(any()) } returns stmt

                val pool = mockk<ConnectionPoolManager>()
                every { pool.supportedConnections } returns setOf("pg-midas")
                every { pool.requiresTenantId("pg-midas") } returns false
                every { pool.acquire("pg-midas") } returns conn

                val pipeline = ExecutePipeline(pool, translator, limits)
                val out =
                    pipeline
                        .execute(
                            ExecuteRequest
                                .newBuilder()
                                .setPlan(scan("public", "positions"))
                                .setContext(PipelineContext.getDefaultInstance())
                                .setConnectionId("pg-midas")
                                .setOptions(ExecutionOptions.getDefaultInstance())
                                .build(),
                        ).toList()

                // The worker asked Proteus for PostgreSQL.
                requestedDialect shouldBe SqlDialect.POSTGRESQL

                // First batch carries the schema + fingerprint + the data; tail batch closes the stream.
                out.size shouldBe 2
                val first = out[0]
                first.isFirst shouldBe true
                first.batchRowCount shouldBe 2L
                first.schemaFingerprint shouldNotBe ""
                first.arrowIpc.size() shouldNotBe 0
                out.last().isLast shouldBe true

                // Decode the Arrow IPC and assert the schema + row values round-tripped.
                RootAllocator(Long.MAX_VALUE).use { alloc ->
                    ArrowStreamReader(ByteArrayInputStream(first.arrowIpc.toByteArray()), alloc).use { reader ->
                        reader.loadNextBatch() shouldBe true
                        val root = reader.vectorSchemaRoot
                        root.rowCount shouldBe 2
                        root.schema.fields.map { it.name } shouldBe listOf("account_id", "amount", "label")

                        val ids = root.getVector("account_id") as BigIntVector
                        ids.get(0) shouldBe 1001L
                        ids.get(1) shouldBe 1002L

                        val amount = root.getVector("amount") as DecimalVector
                        amount.scale shouldBe 4
                        amount.getObject(0) shouldBe BigDecimal("123.4500")
                        amount.getObject(1) shouldBe BigDecimal("67.8900")

                        val label = root.getVector("label") as VarCharVector
                        String(label.get(0)) shouldBe "alpha"
                        String(label.get(1)) shouldBe "beta"
                    }
                }
            }
        }
    })

private fun scan(
    namespace: String,
    name: String,
): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(
            TableScanNode.newBuilder().setTable(
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace(namespace)
                    .setName(name),
            ),
        ).build()
