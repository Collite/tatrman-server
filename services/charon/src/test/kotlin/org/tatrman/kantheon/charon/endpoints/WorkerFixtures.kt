package org.tatrman.kantheon.charon.endpoints

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.tatrman.metis.v1.ArrowChunk
import org.tatrman.metis.v1.DropRequest
import org.tatrman.metis.v1.DropResult
import org.tatrman.metis.v1.ExportRequest
import org.tatrman.metis.v1.ImportResult
import org.tatrman.metis.v1.MetisServiceGrpcKt
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ResultBatch
import org.tatrman.worker.v1.WorkerServiceGrpcKt

/*
 * In-process gRPC fixture workers for the Stage 3.1 mocked suite. They store
 * workspace DataFrames as lists of standalone IPC-stream payloads, keyed
 * (session_id, df_name) — exactly the Steropes/Metis keying. The live pods are
 * the integration suite (testing policy §4); these prove the Charon-side wiring.
 */

/** A Metis fixture implementing the workspace surface (Import/Export/Drop). */
class FixtureMetisWorker : MetisServiceGrpcKt.MetisServiceCoroutineImplBase() {
    private val store = ConcurrentHashMap<String, List<ByteArray>>()

    private fun key(
        session: String,
        df: String,
    ): String = "$session/$df"

    override suspend fun importDataFrame(requests: Flow<ArrowChunk>): ImportResult {
        val chunks = requests.toList()
        val header = chunks.first().header
        // A df named "__cap__" simulates the workspace cap → RESOURCE_EXHAUSTED
        // (Steropes `workspace_cap_exceeded`).
        if (header.dfName == "__cap__") {
            throw StatusException(Status.RESOURCE_EXHAUSTED.withDescription("workspace_cap_exceeded"))
        }
        val payloads = chunks.map { it.ipcPayload.toByteArray() }
        store[key(header.sessionId, header.dfName)] = payloads
        val rows = payloads.sumOf { countRows(it) }
        return ImportResult
            .newBuilder()
            .setDfName(header.dfName)
            .setRows(rows)
            .build()
    }

    override fun exportDataFrame(request: ExportRequest): Flow<ArrowChunk> {
        val payloads =
            store[key(request.sessionId, request.dfName)]
                ?: throw StatusException(Status.NOT_FOUND.withDescription("no such DF"))
        return flow {
            payloads.forEach { emit(ArrowChunk.newBuilder().setIpcPayload(ByteString.copyFrom(it)).build()) }
        }
    }

    override suspend fun dropWorkspaceEntry(request: DropRequest): DropResult {
        val existed = store.remove(key(request.sessionId, request.name)) != null
        return DropResult.newBuilder().setExisted(existed).build()
    }
}

/** A POLARS/worker fixture: `ImportDataFrame` stages a DF in, `Execute` over a
 *  `WorkspaceRef` reads it back out, `DropWorkspaceEntry` drops it — the full
 *  workspace surface Steropes now exposes (worker-arc closeout 2026-06-26). */
class FixturePolarsWorker : WorkerServiceGrpcKt.WorkerServiceCoroutineImplBase() {
    private val store = ConcurrentHashMap<String, List<ByteArray>>()

    fun seed(
        session: String,
        df: String,
        payloads: List<ByteArray>,
    ) {
        store["$session/$df"] = payloads
    }

    override suspend fun importDataFrame(
        requests: Flow<org.tatrman.worker.v1.ImportChunk>,
    ): org.tatrman.worker.v1.ImportDataFrameResult {
        val chunks = requests.toList()
        val header = chunks.first().header
        if (header.dfName == "__cap__") {
            throw StatusException(Status.RESOURCE_EXHAUSTED.withDescription("workspace_cap_exceeded"))
        }
        val payloads = chunks.map { it.ipcPayload.toByteArray() }
        store["${header.sessionId}/${header.dfName}"] = payloads
        return org.tatrman.worker.v1.ImportDataFrameResult
            .newBuilder()
            .setDfName(header.dfName)
            .setRows(payloads.sumOf { countRows(it) })
            .build()
    }

    override suspend fun dropWorkspaceEntry(
        request: org.tatrman.worker.v1.DropWorkspaceRequest,
    ): org.tatrman.worker.v1.DropWorkspaceResult {
        val existed = store.remove("${request.sessionId}/${request.name}") != null
        return org.tatrman.worker.v1.DropWorkspaceResult
            .newBuilder()
            .setExisted(existed)
            .build()
    }

    override fun execute(request: ExecuteRequest): Flow<ResultBatch> {
        val ref = request.plan.workspaceRef.workspaceName
        val session = request.context.sessionId
        val payloads =
            store["$session/$ref"]
                ?: throw StatusException(Status.NOT_FOUND.withDescription("no such workspace DF"))
        return flow {
            payloads.forEachIndexed { i, p ->
                emit(
                    ResultBatch
                        .newBuilder()
                        .setArrowIpc(ByteString.copyFrom(p))
                        .setBatchIndex(i)
                        .setIsFirst(i == 0)
                        .setIsLast(i == payloads.size - 1)
                        .setSchemaFingerprint(if (i == 0) fingerprintOf(p) else "")
                        .build(),
                )
            }
        }
    }

    private fun fingerprintOf(payload: ByteArray): String =
        RootAllocator().use { alloc ->
            ArrowStreamReader(ByteArrayInputStream(payload), alloc).use {
                org.tatrman.kantheon.charon.core.Integrity
                    .fingerprint(it.vectorSchemaRoot.schema)
            }
        }
}

private fun countRows(payload: ByteArray): Long =
    RootAllocator().use { alloc ->
        ArrowStreamReader(ByteArrayInputStream(payload), alloc).use { rd ->
            var rows = 0L
            while (rd.loadNextBatch()) rows += rd.vectorSchemaRoot.rowCount
            rows
        }
    }

/** Start an in-process gRPC server hosting [service] and return a connected
 *  channel; the caller shuts both down. */
fun startInProcess(
    name: String,
    service: io.grpc.BindableService,
): Pair<io.grpc.Server, io.grpc.ManagedChannel> {
    val server =
        InProcessServerBuilder
            .forName(name)
            .directExecutor()
            .addService(service)
            .build()
            .start()
    val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    return server to channel
}

/** A `PipelineContext` builder shim for tests that need one inline. */
fun ctx(session: String): PipelineContext = PipelineContext.newBuilder().setSessionId(session).build()
