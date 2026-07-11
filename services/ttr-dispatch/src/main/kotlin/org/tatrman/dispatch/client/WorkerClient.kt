package org.tatrman.dispatch.client

import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.GetCapabilitiesRequest
import org.tatrman.worker.v1.GetCapabilitiesResponse
import org.tatrman.worker.v1.ResultBatch
import org.tatrman.worker.v1.WorkerServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.flow.Flow
import shared.logging.OutgoingCallLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Thin contract over a Worker's gRPC surface. The dispatcher needs three
 * operations: capability poll, status, and a streamed Execute. v1 wires this
 * via plaintext gRPC (in-cluster traffic); production deployments move to
 * mTLS later.
 */
interface WorkerClient : AutoCloseable {
    suspend fun getCapabilities(): GetCapabilitiesResponse

    fun execute(request: ExecuteRequest): Flow<ResultBatch>
}

class GrpcWorkerClient(
    val endpoint: String,
    private val capabilityTimeoutSeconds: Long = 5,
) : WorkerClient {
    private val parsedEndpoint = parseEndpoint(endpoint)

    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(parsedEndpoint.first, parsedEndpoint.second)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .intercept(OutgoingCallLoggingInterceptor())
            .build()

    private val stub = WorkerServiceGrpcKt.WorkerServiceCoroutineStub(channel)

    override suspend fun getCapabilities(): GetCapabilitiesResponse =
        stub
            .withDeadlineAfter(capabilityTimeoutSeconds, TimeUnit.SECONDS)
            .getCapabilities(GetCapabilitiesRequest.getDefaultInstance())

    override fun execute(request: ExecuteRequest): Flow<ResultBatch> = stub.execute(request)

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }

    private companion object {
        fun parseEndpoint(endpoint: String): Pair<String, Int> {
            val parts = endpoint.split(":")
            require(parts.size == 2) { "Worker endpoint must be host:port, got '$endpoint'" }
            return parts[0] to parts[1].toInt()
        }
    }
}
