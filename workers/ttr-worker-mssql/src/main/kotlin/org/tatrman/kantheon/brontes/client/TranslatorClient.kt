package org.tatrman.kantheon.brontes.client

import org.tatrman.proteus.v1.ProteusServiceGrpcKt
import org.tatrman.proteus.v1.UnparseRequest
import org.tatrman.proteus.v1.UnparseResponse
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import shared.logging.OutgoingCallLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Thin contract over the translator's `UnparseFromRelNode` RPC. The Worker
 * calls it with its target dialect (MSSQL for this Worker) to render the
 * incoming PlanNode as executable SQL.
 *
 * [probe] is a lightweight reachability check. The translator has no
 * dedicated health/capabilities RPC, so we call `unparseFromRelNode` with a
 * default request — the response payload doesn't matter, we only care that
 * the channel is up (no `StatusException`). Used at boot and by
 * `TranslatorHealth` to refresh dependency state.
 */
interface TranslatorClient {
    suspend fun unparse(request: UnparseRequest): UnparseResponse

    suspend fun probe()
}

class GrpcTranslatorClient(
    host: String,
    port: Int,
    private val timeoutSeconds: Long = 30,
) : TranslatorClient,
    AutoCloseable {
    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .intercept(OutgoingCallLoggingInterceptor())
            .build()

    private val stub = ProteusServiceGrpcKt.ProteusServiceCoroutineStub(channel)

    override suspend fun unparse(request: UnparseRequest): UnparseResponse =
        stub.withDeadlineAfter(timeoutSeconds, TimeUnit.SECONDS).unparseFromRelNode(request)

    override suspend fun probe() {
        // Empty request — the response will carry an error message, but the call itself
        // succeeds when the channel is up. UNAVAILABLE / DNS failures throw StatusException,
        // which `TranslatorHealth.probe` interprets as DOWN.
        stub.withDeadlineAfter(5, TimeUnit.SECONDS).unparseFromRelNode(UnparseRequest.getDefaultInstance())
    }

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
