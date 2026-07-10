package org.tatrman.kantheon.charon.endpoints

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
import org.tatrman.metis.v1.ArrowChunk
import org.tatrman.metis.v1.DropRequest
import org.tatrman.metis.v1.ExportRequest
import org.tatrman.metis.v1.ImportHeader
import org.tatrman.metis.v1.MetisServiceGrpcKt
import com.google.protobuf.ByteString

/**
 * The METIS worker gateway (charon/plan.md §5 Stage 3.1 T7) over
 * `org.tatrman.metis.v1.MetisService`. METIS has the **full** workspace surface,
 * so every worker-DF op is real here:
 *
 *   - **stageIn** → `ImportDataFrame` (client-stream `ArrowChunk`; first chunk
 *     carries the `ImportHeader{session, df, expected_fingerprint?}`).
 *   - **scanOut** → `ExportDataFrame` (server-stream `ArrowChunk`).
 *   - **describe** → an `ExportDataFrame` schema probe (first chunk → fingerprint).
 *   - **evict** → `DropWorkspaceEntry`.
 *
 * This delivers the Charon side of **Pythia 4.2** ("Charon stages a handle →
 * Metis session DF"). The coroutine stub is bridged to Charon's synchronous
 * Source/Target contract with `runBlocking`.
 */
class MetisWorkerGateway(
    private val stub: MetisServiceGrpcKt.MetisServiceCoroutineStub,
) : WorkerGateway {
    override fun kind(): WorkerKind = WorkerKind.METIS

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
            val chunks: Flow<ArrowChunk> =
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
                            ArrowChunk
                                .newBuilder()
                                .setHeader(header)
                                .setIpcPayload(ByteString.copyFrom(WorkerArrowCodec.schemaOnlyChunk(schema, alloc)))
                                .build(),
                        )
                    } else {
                        batches.forEachIndexed { i, batch ->
                            val payload = ByteString.copyFrom(WorkerArrowCodec.batchToChunk(schema, batch))
                            val b = ArrowChunk.newBuilder().setIpcPayload(payload)
                            if (i == 0) b.header = header
                            emit(b.build())
                        }
                    }
                }
            val result = runBlocking { stub.importDataFrame(chunks) }
            result.rows
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
                runBlocking {
                    stub
                        .exportDataFrame(
                            ExportRequest
                                .newBuilder()
                                .setSessionId(sessionId)
                                .setDfName(dfName)
                                .build(),
                        ).toList()
                        .map { it.ipcPayload.toByteArray() }
                }
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
            val firstPayload =
                runBlocking {
                    stub
                        .exportDataFrame(
                            ExportRequest
                                .newBuilder()
                                .setSessionId(sessionId)
                                .setDfName(dfName)
                                .setChunkRows(1)
                                .build(),
                        ).first()
                        .ipcPayload
                        .toByteArray()
                }
            val schema = WorkerArrowCodec.schemaOf(firstPayload, alloc)
            DescribeResult
                .newBuilder()
                .setExists(true)
                .setSchemaFingerprint(Integrity.fingerprint(schema))
                .setSchemaJson(schema.toString())
                .setRowCount(-1L)
                .setRowCountExact(false)
                .setSizeBytes(-1L)
                .build()
        } catch (e: StatusException) {
            if (e.status.code == Status.Code.NOT_FOUND) {
                DescribeResult.newBuilder().setExists(false).build()
            } else {
                throw e
            }
        } catch (e: StatusRuntimeException) {
            if (e.status.code == Status.Code.NOT_FOUND) {
                DescribeResult.newBuilder().setExists(false).build()
            } else {
                throw e
            }
        } catch (e: NoSuchElementException) {
            // Empty export stream → treat as an existing-but-empty DF.
            DescribeResult.newBuilder().setExists(true).build()
        }

    override fun evict(
        sessionId: String,
        dfName: String,
    ): EvictResult {
        val result =
            runBlocking {
                stub.dropWorkspaceEntry(
                    DropRequest
                        .newBuilder()
                        .setSessionId(sessionId)
                        .setName(dfName)
                        .build(),
                )
            }
        return EvictResult.newBuilder().setExisted(result.existed).build()
    }

    private fun notFoundOrRethrow(
        status: Status,
        ref: String,
        cause: Throwable,
    ): Throwable = if (status.code == Status.Code.NOT_FOUND) WorkerDfNotFoundException(ref) else cause
}
