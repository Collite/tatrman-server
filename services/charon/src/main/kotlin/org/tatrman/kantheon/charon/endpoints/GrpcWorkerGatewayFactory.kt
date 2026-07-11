package org.tatrman.kantheon.charon.endpoints

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.tatrman.transfer.v1.WorkerKind
import org.tatrman.kantheon.charon.core.WorkerGateway
import org.tatrman.kantheon.charon.core.WorkerGatewayFactory
import org.tatrman.metis.v1.MetisServiceGrpcKt
import org.tatrman.worker.v1.WorkerServiceGrpcKt

private val log = LoggerFactory.getLogger(GrpcWorkerGatewayFactory::class.java)

/**
 * Production [WorkerGatewayFactory] — builds one gRPC channel + gateway per
 * engine from `target` addresses (`host:port`), lazily on first use.
 *
 * `polarsTarget` → [PolarsWorkerGateway] over `worker.v1` (Polars);
 * `metisTarget` → [MetisWorkerGateway] over `metis.v1`. A `null` target means
 * that engine isn't wired on this pod (the factory returns `null`, and the
 * executor surfaces `WorkerEngineUnavailable`). Channels use the same
 * plaintext + keep-alive idiom as dispatch's `WorkerClient`.
 */
class GrpcWorkerGatewayFactory(
    private val polarsTarget: String? = null,
    private val metisTarget: String? = null,
) : WorkerGatewayFactory,
    AutoCloseable {
    private val channels = ConcurrentHashMap<WorkerKind, ManagedChannel>()
    private val gateways = ConcurrentHashMap<WorkerKind, WorkerGateway>()

    override fun forKind(kind: WorkerKind): WorkerGateway? {
        val target =
            when (kind) {
                WorkerKind.POLARS -> polarsTarget
                WorkerKind.METIS -> metisTarget
                else -> null
            } ?: return null
        return gateways.computeIfAbsent(kind) { k ->
            val channel = channels.computeIfAbsent(k) { buildChannel(target) }
            when (k) {
                WorkerKind.POLARS -> PolarsWorkerGateway(WorkerServiceGrpcKt.WorkerServiceCoroutineStub(channel))
                WorkerKind.METIS -> MetisWorkerGateway(MetisServiceGrpcKt.MetisServiceCoroutineStub(channel))
                else -> error("unreachable")
            }
        }
    }

    private fun buildChannel(target: String): ManagedChannel {
        // Let gRPC's own resolver parse the target (`host:port`, `dns:///…`,
        // IPv6 literals) instead of a naive `split(":")` that mis-parses IPv6
        // and throws inside computeIfAbsent on a malformed value.
        log.info("worker gateway channel → {}", target)
        return ManagedChannelBuilder
            .forTarget(target)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
    }

    override fun close() {
        channels.values.forEach { runCatching { it.shutdownNow().awaitTermination(5, TimeUnit.SECONDS) } }
        channels.clear()
        gateways.clear()
    }
}
