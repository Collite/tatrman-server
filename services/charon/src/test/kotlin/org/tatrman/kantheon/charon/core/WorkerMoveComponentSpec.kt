package org.tatrman.kantheon.charon.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.transfer.v1.Location
import org.tatrman.transfer.v1.MoveOptions
import org.tatrman.transfer.v1.MoveResult
import org.tatrman.transfer.v1.SeaweedBlob
import org.tatrman.transfer.v1.WorkerKind
import org.tatrman.transfer.v1.WorkerSessionDf
import org.tatrman.kantheon.charon.endpoints.FixtureMetisWorker
import org.tatrman.kantheon.charon.endpoints.FixturePolarsWorker
import org.tatrman.kantheon.charon.endpoints.MetisWorkerGateway
import org.tatrman.kantheon.charon.endpoints.PolarsWorkerGateway
import org.tatrman.kantheon.charon.endpoints.startInProcess
import org.tatrman.metis.v1.MetisServiceGrpcKt
import org.tatrman.worker.v1.WorkerServiceGrpcKt
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CopyObjectResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse

/**
 * Component suite for worker-DF moves through the real [CharonMoveExecutor]
 * (charon/plan.md §5 Stage 3.1 T3/T5): `Stage(seaweed → metis-worker)`,
 * `worker → seaweed` read-out, `Describe(worker_df)`, and the POLARS stage-in
 * path (worker.v1 `ImportDataFrame`). Blob = mocked S3; worker = fixtures.
 */
class WorkerMoveComponentSpec :
    StringSpec({

        val schema =
            Schema(
                listOf(
                    Field("id", FieldType(false, ArrowType.Int(64, true), null), null),
                    Field("name", FieldType(true, ArrowType.Utf8(), null), null),
                ),
            )

        fun workerLoc(
            kind: WorkerKind,
            df: String,
        ): Location =
            Location
                .newBuilder()
                .setWorkerDf(
                    WorkerSessionDf
                        .newBuilder()
                        .setWorkerKind(kind)
                        .setSessionId("s1")
                        .setDfName(df)
                        .build(),
                ).build()

        fun seaweedLoc(key: String): Location =
            Location
                .newBuilder()
                .setSeaweed(
                    SeaweedBlob
                        .newBuilder()
                        .setBucket("b")
                        .setKey(key)
                        .build(),
                ).build()

        "Stage(seaweed → METIS worker) stages the rows; worker → seaweed reads them back; Describe sees it" {
            val metis = FixtureMetisWorker()
            val (server, channel) = startInProcess("comp-metis-${System.nanoTime()}", metis)
            try {
                val gateway = MetisWorkerGateway(MetisServiceGrpcKt.MetisServiceCoroutineStub(channel))
                val factory = FixtureWorkerFactory(metis = gateway, polars = null)
                val s3 = mockk<S3Client>(relaxed = false)
                // GET returns a 3-row Arrow IPC stream (the seaweed source).
                every { s3.getObject(any<GetObjectRequest>()) } returns
                    ResponseInputStream(
                        GetObjectResponse.builder().build(),
                        ByteArrayInputStream(ipcBytes(schema, listOf(1L to "a", 2L to "b", 3L to "c"))),
                    )
                // PUT/copy/delete capture for the read-back-to-seaweed leg.
                val put = slot<ByteArray>()
                every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } answers {
                    val sink = ByteArrayOutputStream()
                    secondArg<RequestBody>().contentStreamProvider().newStream().use { it.copyTo(sink) }
                    put.captured = sink.toByteArray()
                    PutObjectResponse.builder().build()
                }
                every { s3.copyObject(any<CopyObjectRequest>()) } returns CopyObjectResponse.builder().build()
                every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()

                val exec =
                    CharonMoveExecutor(s3Client = s3, redisOps = mockk(relaxed = true), workerGatewayFactory = factory)

                // Stage seaweed → worker.
                val staged =
                    exec.stage(
                        Plan(
                            MoveRpc.STAGE,
                            seaweedLoc("src.arrow"),
                            workerLoc(WorkerKind.METIS, "df1"),
                            MoveOptions.getDefaultInstance(),
                        ),
                    )
                staged.shouldBeInstanceOf<Either.Right<MoveResult>>()
                (staged as Either.Right).value.rowCount shouldBe 3L

                // Describe(worker_df) → exists + fingerprint.
                val described =
                    exec.describe(
                        Plan(
                            MoveRpc.DESCRIBE,
                            workerLoc(WorkerKind.METIS, "df1"),
                            null,
                            MoveOptions.getDefaultInstance(),
                        ),
                    )
                described.shouldBeInstanceOf<Either.Right<org.tatrman.transfer.v1.DescribeResult>>()
                (described as Either.Right).value.exists shouldBe true

                // worker → seaweed read-out.
                val readOut =
                    exec.materialize(
                        Plan(
                            MoveRpc.MATERIALIZE,
                            workerLoc(WorkerKind.METIS, "df1"),
                            seaweedLoc("out.arrow"),
                            MoveOptions.getDefaultInstance(),
                        ),
                    )
                readOut.shouldBeInstanceOf<Either.Right<MoveResult>>()
                (readOut as Either.Right).value.rowCount shouldBe 3L
                RootAllocator().use { alloc ->
                    ArrowStreamReader(ByteArrayInputStream(put.captured), alloc).use { rd ->
                        var rows = 0
                        while (rd.loadNextBatch()) rows += rd.vectorSchemaRoot.rowCount
                        rows shouldBe 3
                    }
                }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }

        "Stage(seaweed → POLARS worker) stages rows (worker.v1 ImportDataFrame); worker → seaweed reads back" {
            val polars = FixturePolarsWorker()
            val (server, channel) = startInProcess("comp-polars-${System.nanoTime()}", polars)
            try {
                val gateway = PolarsWorkerGateway(WorkerServiceGrpcKt.WorkerServiceCoroutineStub(channel))
                val factory = FixtureWorkerFactory(metis = null, polars = gateway)
                val s3 = mockk<S3Client>(relaxed = false)
                every { s3.getObject(any<GetObjectRequest>()) } returns
                    ResponseInputStream(
                        GetObjectResponse.builder().build(),
                        ByteArrayInputStream(ipcBytes(schema, listOf(1L to "a", 2L to "b"))),
                    )
                every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns
                    PutObjectResponse.builder().build()
                every { s3.copyObject(any<CopyObjectRequest>()) } returns CopyObjectResponse.builder().build()
                every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()
                val exec =
                    CharonMoveExecutor(s3Client = s3, redisOps = mockk(relaxed = true), workerGatewayFactory = factory)

                val staged =
                    exec.stage(
                        Plan(
                            MoveRpc.STAGE,
                            seaweedLoc("src.arrow"),
                            workerLoc(WorkerKind.POLARS, "df1"),
                            MoveOptions.getDefaultInstance(),
                        ),
                    )
                staged.shouldBeInstanceOf<Either.Right<MoveResult>>()
                (staged as Either.Right).value.rowCount shouldBe 2L

                val readOut =
                    exec.materialize(
                        Plan(
                            MoveRpc.MATERIALIZE,
                            workerLoc(WorkerKind.POLARS, "df1"),
                            seaweedLoc("out.arrow"),
                            MoveOptions.getDefaultInstance(),
                        ),
                    )
                readOut.shouldBeInstanceOf<Either.Right<MoveResult>>()
                (readOut as Either.Right).value.rowCount shouldBe 2L
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }

        "a worker_df request with no gateway factory → WorkerEngineUnavailable" {
            val s3 = mockk<S3Client>(relaxed = true)
            val exec = CharonMoveExecutor(s3Client = s3, redisOps = mockk(relaxed = true))
            val r =
                exec.describe(
                    Plan(MoveRpc.DESCRIBE, workerLoc(WorkerKind.METIS, "df1"), null, MoveOptions.getDefaultInstance()),
                )
            r.shouldBeInstanceOf<Either.Left<CharonError>>()
            ((r as Either.Left).value is CharonError.WorkerEngineUnavailable) shouldBe true
        }
    })

/** A test [WorkerGatewayFactory] holding pre-built fixture-backed gateways. */
private class FixtureWorkerFactory(
    private val metis: WorkerGateway?,
    private val polars: WorkerGateway?,
) : WorkerGatewayFactory {
    override fun forKind(kind: WorkerKind): WorkerGateway? =
        when (kind) {
            WorkerKind.METIS -> metis
            WorkerKind.POLARS -> polars
            else -> null
        }
}

private fun ipcBytes(
    schema: Schema,
    rows: List<Pair<Long, String?>>,
): ByteArray {
    val out = ByteArrayOutputStream()
    RootAllocator().use { alloc ->
        VectorSchemaRoot.create(schema, alloc).use { root ->
            val idVec = root.getVector("id") as BigIntVector
            val nameVec = root.getVector("name") as VarCharVector
            idVec.allocateNew(rows.size)
            nameVec.allocateNew()
            rows.forEachIndexed { i, (id, name) ->
                idVec.setSafe(i, id)
                if (name == null) nameVec.setNull(i) else nameVec.setSafe(i, name.toByteArray(Charsets.UTF_8))
            }
            root.rowCount = rows.size
            ArrowStreamWriter(root, null, out).use { w ->
                w.start()
                w.writeBatch()
                w.end()
            }
        }
    }
    return out.toByteArray()
}
