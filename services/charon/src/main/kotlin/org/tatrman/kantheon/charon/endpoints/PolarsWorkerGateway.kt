package org.tatrman.kantheon.charon.endpoints

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.transfer.v1.DescribeResult
import org.tatrman.transfer.v1.EvictResult
import org.tatrman.transfer.v1.WorkerKind
import org.tatrman.kantheon.charon.core.Integrity
import org.tatrman.kantheon.charon.core.WorkerDfNotFoundException
import org.tatrman.kantheon.charon.core.WorkerGateway
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.WorkspaceRef
import org.tatrman.worker.v1.DropWorkspaceRequest
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ImportChunk
import org.tatrman.worker.v1.ImportHeader
import org.tatrman.worker.v1.WorkerServiceGrpcKt

/**
 * The POLARS / Polars worker gateway (charon/plan.md §5 Stage 3.1) over
 * `org.tatrman.worker.v1.WorkerService`. Full parity with the METIS path since
 * the worker-arc `ImportDataFrame`/`DropWorkspaceEntry` RPCs landed (Charon
 * Stage 3.1 closeout, 2026-06-26):
 *
 *   - **stageIn** → `ImportDataFrame` (client-stream `ImportChunk`; first chunk
 *     carries the `ImportHeader{session, df, expected_fingerprint?}`).
 *   - **scanOut** → `Execute` over a `WorkspaceRef` plan node (server-stream
 *     `ResultBatch`; each `arrow_ipc` a standalone IPC stream).
 *   - **describe** → first `ResultBatch.schema_fingerprint` (populated on `is_first`).
 *   - **evict** → `DropWorkspaceEntry`.
 *
 * This delivers Pythia 4.1's "Charon stages data into a Polars-Worker session
 * DataFrame via Stage".
 */
class PolarsWorkerGateway(
    private val stub: WorkerServiceGrpcKt.WorkerServiceCoroutineStub,
) : WorkerGateway {
    override fun kind(): WorkerKind = WorkerKind.POLARS

    override fun stageIn(
        sessionId: String,
        dfName: String,
        schema: Schema,
        batches: List<VectorSchemaRoot>,
        expectedFingerprint: String?,
    ): Long {
        val alloc =
            org.apache.arrow.memory
                .RootAllocator()
        return try {
            val chunks: Flow<ImportChunk> =
                flow {
                    val header =
                        ImportHeader
                            .newBuilder()
                            .setSessionId(sessionId)
                            .setDfName(dfName)
                            .apply { if (expectedFingerprint != null) expectedSchemaFingerprint = expectedFingerprint }
                            .build()
                    if (batches.isEmpty()) {
                        emit(
                            ImportChunk
                                .newBuilder()
                                .setHeader(header)
                                .setIpcPayload(ByteString.copyFrom(WorkerArrowCodec.schemaOnlyChunk(schema, alloc)))
                                .build(),
                        )
                    } else {
                        batches.forEachIndexed { i, batch ->
                            val payload = ByteString.copyFrom(WorkerArrowCodec.batchToChunk(schema, batch))
                            val b = ImportChunk.newBuilder().setIpcPayload(payload)
                            if (i == 0) b.header = header
                            emit(b.build())
                        }
                    }
                }
            runBlocking { stub.importDataFrame(chunks) }.rows
        } finally {
            alloc.close()
        }
    }

    override fun scanOut(
        sessionId: String,
        dfName: String,
        alloc: BufferAllocator,
    ): ArrowReader {
        val payloads =
            try {
                runBlocking { stub.execute(workspaceScan(sessionId, dfName)).toList() }
                    .filter { it.arrowIpc.size() > 0 }
                    .map { it.arrowIpc.toByteArray() }
            } catch (e: StatusException) {
                throw notFoundOrRethrow(e.status, "$sessionId/$dfName", e)
            } catch (e: StatusRuntimeException) {
                throw notFoundOrRethrow(e.status, "$sessionId/$dfName", e)
            }
        return WorkerArrowCodec.payloadsToReader(payloads, alloc)
            ?: throw WorkerDfNotFoundException("$sessionId/$dfName")
    }

    override fun describe(
        sessionId: String,
        dfName: String,
        alloc: BufferAllocator,
    ): DescribeResult =
        try {
            // Liveness probe — only the first ResultBatch is needed (it carries
            // is_first + schema_fingerprint). Take one off the server stream
            // rather than draining the whole DataFrame over the wire (parity
            // with the METIS describe's chunkRows=1 + first()).
            val first = runBlocking { stub.execute(workspaceScan(sessionId, dfName)).firstOrNull() }
            if (first == null) {
                DescribeResult.newBuilder().setExists(false).build()
            } else {
                // ResultBatch.schema_fingerprint is populated on is_first.
                val fp =
                    first.schemaFingerprint.ifEmpty {
                        WorkerArrowCodec.schemaOf(first.arrowIpc.toByteArray(), alloc).let { Integrity.fingerprint(it) }
                    }
                DescribeResult
                    .newBuilder()
                    .setExists(true)
                    .setSchemaFingerprint(fp)
                    .setRowCount(-1L)
                    .setRowCountExact(false)
                    .setSizeBytes(-1L)
                    .build()
            }
        } catch (e: StatusException) {
            if (e.status.code ==
                Status.Code.NOT_FOUND
            ) {
                DescribeResult.newBuilder().setExists(false).build()
            } else {
                throw e
            }
        } catch (e: StatusRuntimeException) {
            if (e.status.code ==
                Status.Code.NOT_FOUND
            ) {
                DescribeResult.newBuilder().setExists(false).build()
            } else {
                throw e
            }
        }

    override fun evict(
        sessionId: String,
        dfName: String,
    ): EvictResult {
        val result =
            runBlocking {
                stub.dropWorkspaceEntry(
                    DropWorkspaceRequest
                        .newBuilder()
                        .setSessionId(sessionId)
                        .setName(dfName)
                        .build(),
                )
            }
        return EvictResult.newBuilder().setExisted(result.existed).build()
    }

    private fun workspaceScan(
        sessionId: String,
        dfName: String,
    ): ExecuteRequest =
        ExecuteRequest
            .newBuilder()
            .setPlan(PlanNode.newBuilder().setWorkspaceRef(WorkspaceRef.newBuilder().setWorkspaceName(dfName).build()))
            .setContext(PipelineContext.newBuilder().setSessionId(sessionId).build())
            .build()

    private fun notFoundOrRethrow(
        status: Status,
        ref: String,
        cause: Throwable,
    ): Throwable = if (status.code == Status.Code.NOT_FOUND) WorkerDfNotFoundException(ref) else cause
}
