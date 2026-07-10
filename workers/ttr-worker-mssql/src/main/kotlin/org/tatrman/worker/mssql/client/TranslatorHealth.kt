package org.tatrman.worker.mssql.client

import org.tatrman.meta.v1.DependencyStatus
import org.tatrman.meta.v1.OverallStatus
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks the worker's view of the translator dependency.
 *
 * Updated from two places:
 *  - At boot via [probe], which calls the translator's `GetCapabilities` so an operator sees
 *    "translator reachable" in the very first log lines.
 *  - On every Execute, via the [Wrapping] decorator below — translator success bumps the
 *    status to OK; gRPC `StatusException` / `StatusRuntimeException` (the canonical signal
 *    for "DNS / transport / server-side error") flips it to DOWN with the error text.
 *
 * The latest snapshot is what GetStatus reports as a [DependencyStatus]. There is no
 * background poll — status reflects the last real interaction, which is what an operator
 * actually wants to know.
 */
class TranslatorHealth {
    private val state = AtomicReference(unknown())

    fun current(): DependencyStatus = state.get()

    fun recordSuccess() {
        state.set(
            DependencyStatus
                .newBuilder()
                .setName(NAME)
                .setStatus(OverallStatus.OK)
                .setLastReached(Instant.now().toString())
                .build(),
        )
    }

    fun recordFailure(message: String) {
        val previous = state.get()
        state.set(
            DependencyStatus
                .newBuilder()
                .setName(NAME)
                .setStatus(OverallStatus.DOWN)
                .setLastReached(previous.lastReached) // preserve last-good timestamp
                .setLastError(message)
                .build(),
        )
    }

    suspend fun probe(client: TranslatorClient) {
        try {
            client.probe()
            recordSuccess()
            log.info("Translator probe OK")
        } catch (ex: StatusException) {
            val msg = "${ex.status.code}: ${ex.status.description ?: ex.message ?: "no detail"}"
            recordFailure(msg)
            log.warn("Translator probe failed: {}", msg)
        } catch (ex: StatusRuntimeException) {
            val msg = "${ex.status.code}: ${ex.status.description ?: ex.message ?: "no detail"}"
            recordFailure(msg)
            log.warn("Translator probe failed: {}", msg)
        } catch (t: Throwable) {
            recordFailure(t.message ?: t.javaClass.simpleName)
            log.warn("Translator probe failed (non-gRPC): {}", t.message, t)
        }
    }

    /**
     * Decorator that updates [TranslatorHealth] from every Execute-time call. gRPC status
     * exceptions mark the translator DOWN; successful responses mark it OK. Other exceptions
     * (translator returned an error message in the response payload) leave the health
     * untouched — the call itself reached the translator.
     */
    class Wrapping(
        private val delegate: TranslatorClient,
        private val health: TranslatorHealth,
    ) : TranslatorClient {
        override suspend fun unparse(request: UnparseRequest): UnparseResponse =
            try {
                delegate.unparse(request).also { health.recordSuccess() }
            } catch (ex: StatusException) {
                health.recordFailure("${ex.status.code}: ${ex.status.description ?: ex.message ?: "no detail"}")
                throw ex
            } catch (ex: StatusRuntimeException) {
                health.recordFailure("${ex.status.code}: ${ex.status.description ?: ex.message ?: "no detail"}")
                throw ex
            }

        override suspend fun probe() = delegate.probe()
    }

    companion object {
        const val NAME: String = "translator"
        private val log = LoggerFactory.getLogger(TranslatorHealth::class.java)

        private fun unknown(): DependencyStatus =
            DependencyStatus
                .newBuilder()
                .setName(NAME)
                .setStatus(OverallStatus.OVERALL_STATUS_UNSPECIFIED)
                .build()
    }
}
