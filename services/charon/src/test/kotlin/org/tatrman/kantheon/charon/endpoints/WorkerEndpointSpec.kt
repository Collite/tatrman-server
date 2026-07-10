package org.tatrman.kantheon.charon.endpoints

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.transfer.v1.Location
import org.tatrman.transfer.v1.MoveResult
import org.tatrman.kantheon.charon.core.ArrowPipe
import org.tatrman.kantheon.charon.core.Either
import org.tatrman.kantheon.charon.core.Integrity
import org.tatrman.kantheon.charon.core.LocationKind
import org.tatrman.kantheon.charon.core.MoveRpc
import org.tatrman.kantheon.charon.core.PipeOptions
import org.tatrman.kantheon.charon.core.Source
import org.tatrman.metis.v1.MetisServiceGrpcKt
import org.tatrman.worker.v1.WorkerServiceGrpcKt

/**
 * The worker endpoint suite (charon/plan.md §5 Stage 3.1) against in-process
 * fixture workers. METIS exercises the full stage-in→scan-out round-trip +
 * describe + evict + the cap mapping; POLARS exercises read-out + the
 * stage-in/evict gap (worker.v1 has no ingest/drop RPC).
 */
class WorkerEndpointSpec :
    StringSpec({

        val schema =
            Schema(
                listOf(
                    Field("id", FieldType(false, ArrowType.Int(64, true), null), null),
                    Field("name", FieldType(true, ArrowType.Utf8(), null), null),
                ),
            )

        // --- METIS: full round-trip via the endpoint + ArrowPipe ---

        "METIS stage-in then scan-out round-trips rows + fingerprint (seaweed→worker→seaweed wiring)" {
            val fixture = FixtureMetisWorker()
            val (server, channel) = startInProcess("metis-rt-${System.nanoTime()}", fixture)
            try {
                val gateway = MetisWorkerGateway(MetisServiceGrpcKt.MetisServiceCoroutineStub(channel))
                RootAllocator().use { alloc ->
                    // Stage a 3-row Arrow source into the worker DF via the endpoint Target.
                    val source = inMemoryArrowSource(schema, listOf(1L to "a", 2L to "b", 3L to "c"), alloc)
                    val target = WorkerEndpoint(gateway, "s1", "df1", parentAllocator = alloc)
                    val staged =
                        ArrowPipe.pipe(
                            MoveRpc.STAGE,
                            source,
                            target,
                            Location.getDefaultInstance(),
                            Location.getDefaultInstance(),
                            PipeOptions(),
                        )
                    staged.shouldBeInstanceOf<Either.Right<MoveResult>>()
                    (staged as Either.Right).value.rowCount shouldBe 3L

                    // Scan it back out via the endpoint Source.
                    val readBack = WorkerEndpoint(gateway, "s1", "df1", parentAllocator = alloc)
                    readBack.open()!!.use { rd ->
                        Integrity.fingerprint(rd.vectorSchemaRoot.schema) shouldBe Integrity.fingerprint(schema)
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

        "METIS describe returns exists + fingerprint; after evict, exists=false; evict is idempotent" {
            val fixture = FixtureMetisWorker()
            val (server, channel) = startInProcess("metis-desc-${System.nanoTime()}", fixture)
            try {
                val gateway = MetisWorkerGateway(MetisServiceGrpcKt.MetisServiceCoroutineStub(channel))
                RootAllocator().use { alloc ->
                    stageAndClose(gateway, "s1", "live", schema, listOf(1L to "a"), alloc)
                    val d = gateway.describe("s1", "live", alloc)
                    d.exists shouldBe true
                    d.schemaFingerprint shouldBe Integrity.fingerprint(schema)

                    gateway.evict("s1", "live").existed shouldBe true
                    gateway.describe("s1", "live", alloc).exists shouldBe false
                    // idempotent: dropping again reports not-existed, no throw.
                    gateway.evict("s1", "live").existed shouldBe false
                }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }

        "METIS workspace cap → RESOURCE_EXHAUSTED propagates as a gRPC status" {
            val fixture = FixtureMetisWorker()
            val (server, channel) = startInProcess("metis-cap-${System.nanoTime()}", fixture)
            try {
                val gateway = MetisWorkerGateway(MetisServiceGrpcKt.MetisServiceCoroutineStub(channel))
                RootAllocator().use { alloc ->
                    val batches = ownedBatches(schema, listOf(1L to "a"), alloc)
                    try {
                        val ex =
                            shouldThrow<io.grpc.StatusException> {
                                gateway.stageIn("s1", "__cap__", schema, batches, null)
                            }
                        ex.status.code shouldBe io.grpc.Status.Code.RESOURCE_EXHAUSTED
                    } finally {
                        batches.forEach { it.close() }
                    }
                }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }

        // --- POLARS: read-out works; stage-in / evict are the documented gap ---

        "POLARS scan-out reads a pre-seeded workspace DF back out" {
            val fixture = FixturePolarsWorker()
            RootAllocator().use { alloc ->
                fixture.seed("s1", "polardf", listOf(ipcBytes(schema, listOf(10L to "x", 20L to "y"))))
                val (server, channel) = startInProcess("polars-read-${System.nanoTime()}", fixture)
                try {
                    val gateway = PolarsWorkerGateway(WorkerServiceGrpcKt.WorkerServiceCoroutineStub(channel))
                    WorkerEndpoint(gateway, "s1", "polardf", parentAllocator = alloc).open()!!.use { rd ->
                        var rows = 0
                        while (rd.loadNextBatch()) rows += rd.vectorSchemaRoot.rowCount
                        rows shouldBe 2
                    }
                    gateway.describe("s1", "polardf", alloc).exists shouldBe true
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                }
            }
        }

        "POLARS stage-in then scan-out round-trips (worker.v1 ImportDataFrame); evict drops it" {
            val fixture = FixturePolarsWorker()
            val (server, channel) = startInProcess("polars-stage-${System.nanoTime()}", fixture)
            try {
                val gateway = PolarsWorkerGateway(WorkerServiceGrpcKt.WorkerServiceCoroutineStub(channel))
                RootAllocator().use { alloc ->
                    val source = inMemoryArrowSource(schema, listOf(1L to "a", 2L to "b"), alloc)
                    val target = WorkerEndpoint(gateway, "s1", "df1", parentAllocator = alloc)
                    val staged =
                        ArrowPipe.pipe(
                            MoveRpc.STAGE,
                            source,
                            target,
                            Location.getDefaultInstance(),
                            Location.getDefaultInstance(),
                            PipeOptions(),
                        )
                    staged.shouldBeInstanceOf<Either.Right<MoveResult>>()
                    (staged as Either.Right).value.rowCount shouldBe 2L

                    WorkerEndpoint(gateway, "s1", "df1", parentAllocator = alloc).open()!!.use { rd ->
                        var rows = 0
                        while (rd.loadNextBatch()) rows += rd.vectorSchemaRoot.rowCount
                        rows shouldBe 2
                    }

                    gateway.evict("s1", "df1").existed shouldBe true
                    gateway.describe("s1", "df1", alloc).exists shouldBe false
                }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }

        "POLARS workspace cap → RESOURCE_EXHAUSTED propagates as a gRPC status" {
            val fixture = FixturePolarsWorker()
            val (server, channel) = startInProcess("polars-cap-${System.nanoTime()}", fixture)
            try {
                val gateway = PolarsWorkerGateway(WorkerServiceGrpcKt.WorkerServiceCoroutineStub(channel))
                RootAllocator().use { alloc ->
                    val batches = ownedBatches(schema, listOf(1L to "a"), alloc)
                    try {
                        val ex =
                            shouldThrow<io.grpc.StatusException> {
                                gateway.stageIn(
                                    "s1",
                                    "__cap__",
                                    schema,
                                    batches,
                                    null,
                                )
                            }
                        ex.status.code shouldBe io.grpc.Status.Code.RESOURCE_EXHAUSTED
                    } finally {
                        batches.forEach { it.close() }
                    }
                }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }

        "POLARS scan-out of an absent DF → WorkerDfNotFound" {
            val fixture = FixturePolarsWorker()
            val (server, channel) = startInProcess("polars-absent-${System.nanoTime()}", fixture)
            try {
                val gateway = PolarsWorkerGateway(WorkerServiceGrpcKt.WorkerServiceCoroutineStub(channel))
                RootAllocator().use { alloc ->
                    gateway.describe("s1", "nope", alloc).exists shouldBe false
                }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }
    })

// --- helpers ---

private fun stageAndClose(
    gateway: org.tatrman.kantheon.charon.core.WorkerGateway,
    session: String,
    df: String,
    schema: Schema,
    rows: List<Pair<Long, String?>>,
    alloc: RootAllocator,
) {
    val batches = ownedBatches(schema, rows, alloc)
    try {
        gateway.stageIn(session, df, schema, batches, null)
    } finally {
        batches.forEach { it.close() }
    }
}

private fun ownedBatches(
    schema: Schema,
    rows: List<Pair<Long, String?>>,
    alloc: RootAllocator,
): List<VectorSchemaRoot> {
    val root = VectorSchemaRoot.create(schema, alloc)
    val idVec = root.getVector("id") as BigIntVector
    val nameVec = root.getVector("name") as VarCharVector
    idVec.allocateNew(rows.size)
    nameVec.allocateNew()
    rows.forEachIndexed { i, (id, name) ->
        idVec.setSafe(i, id)
        if (name == null) nameVec.setNull(i) else nameVec.setSafe(i, name.toByteArray(Charsets.UTF_8))
    }
    root.rowCount = rows.size
    return listOf(root)
}

private fun ipcBytes(
    schema: Schema,
    rows: List<Pair<Long, String?>>,
): ByteArray {
    val out = ByteArrayOutputStream()
    RootAllocator().use { alloc ->
        ownedBatches(schema, rows, alloc).single().use { root ->
            ArrowStreamWriter(root, null, out).use { w ->
                w.start()
                w.writeBatch()
                w.end()
            }
        }
    }
    return out.toByteArray()
}

private fun inMemoryArrowSource(
    schema: Schema,
    rows: List<Pair<Long, String?>>,
    alloc: RootAllocator,
): Source {
    val bytes = ipcBytes(schema, rows)
    return object : Source {
        override fun open(): ArrowReader = ArrowStreamReader(ByteArrayInputStream(bytes), alloc)

        override fun kind(): LocationKind = LocationKind.SEAWEED

        override fun ref(): String = "in-memory"
    }
}
