package org.tatrman.kantheon.argos.client

import org.tatrman.ariadne.v1.GetSnapshotRequest
import org.tatrman.ariadne.v1.GetSnapshotResponse
import org.tatrman.ariadne.v1.GetStatusRequest
import org.tatrman.ariadne.v1.AriadneServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import shared.logging.OutgoingCallLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Thin contract over the metadata service. The Validator only needs the
 * current model version (Section F schema-version verification); a fuller
 * metadata client lands when the RuleEnforcer needs column-level introspection
 * for allow/deny enforcement (Section C deferred portion).
 */
interface MetadataClient {
    suspend fun getSnapshot(ifNoneMatch: String = ""): GetSnapshotResponse

    /** Convenience: fetch only the current ETag (= ModelVersion.value). */
    suspend fun currentVersion(): String

    /**
     * Liveness probe for the readiness gate ([DependencyMonitor]). Returns the metadata
     * service's own `model_loaded` flag via the cheap `GetStatus` RPC. Throws when the
     * service is unreachable — the monitor treats a throw as "down".
     */
    suspend fun probeReady(): Boolean
}

class GrpcMetadataClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 10,
    private val probeDeadlineSeconds: Long = 5,
) : MetadataClient,
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

    private val stub = AriadneServiceGrpcKt.AriadneServiceCoroutineStub(channel)

    override suspend fun getSnapshot(ifNoneMatch: String): GetSnapshotResponse =
        stub
            .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
            .getSnapshot(GetSnapshotRequest.newBuilder().setIfNoneMatch(ifNoneMatch).build())

    override suspend fun currentVersion(): String = getSnapshot(ifNoneMatch = "").etag

    override suspend fun probeReady(): Boolean =
        stub
            .withDeadlineAfter(probeDeadlineSeconds, TimeUnit.SECONDS)
            .getStatus(GetStatusRequest.getDefaultInstance())
            .modelLoaded

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}

/**
 * Static [MetadataClient] for tests and fixture-only boots. Always returns the
 * configured version; never opens a channel.
 */
class StaticMetadataClient(
    private val version: String,
) : MetadataClient {
    override suspend fun getSnapshot(ifNoneMatch: String): GetSnapshotResponse =
        GetSnapshotResponse
            .newBuilder()
            .setEtag(version)
            .setNotModified(ifNoneMatch == version)
            .build()

    override suspend fun currentVersion(): String = version

    override suspend fun probeReady(): Boolean = true
}
