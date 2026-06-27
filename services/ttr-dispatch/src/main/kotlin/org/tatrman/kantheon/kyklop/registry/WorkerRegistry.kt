package org.tatrman.kantheon.kyklop.registry

import org.tatrman.kyklop.v1.WorkerHealthStatus
import org.tatrman.worker.v1.ConnectionInfo
import org.tatrman.worker.v1.GetCapabilitiesResponse
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.kyklop.client.WorkerClient
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Snapshot of one Worker as seen by this Dispatcher instance. Mutates only
 * via [WorkerRegistry.recordSuccess] / [WorkerRegistry.recordFailure].
 */
data class WorkerEntry(
    val endpoint: String,
    val roleHint: String,
    val client: WorkerClient,
    val capabilities: GetCapabilitiesResponse?,
    val health: WorkerHealthStatus,
    val lastPolled: Instant?,
    val consecutiveFailures: Int,
) {
    val supportedConnections: Set<String>
        get() = capabilities?.supportedConnectionsList?.toSet() ?: emptySet()

    val supportsStateful: Boolean
        get() = capabilities?.supportsStatefulSessions == true

    /**
     * Issue #57 Phase C — look up the ConnectionInfo (database + default_schema) the worker
     * advertised for [connectionId] via `GetCapabilitiesResponse.connections`. Returns null
     * if the worker hasn't been polled yet, or hasn't been redeployed since Phase B (the
     * field is empty on the legacy capabilities response).
     */
    fun connectionInfo(connectionId: String): ConnectionInfo? =
        capabilities
            ?.connectionsList
            ?.firstOrNull { it.connectionId == connectionId }
}

/**
 * Holds the live view of all Workers configured for this Dispatcher. Keyed
 * by endpoint (one entry per HOCON config row); a secondary lookup by
 * connection_id is computed from the per-Worker capabilities.
 *
 * Health transitions follow the plan's Section B rules:
 *   - HEALTHY ← every successful poll
 *   - DEGRADED ← `consecutiveFailures >= degradedAfter`
 *   - UNREACHABLE ← `consecutiveFailures >= unreachableAfter`
 *
 * This class is thread-safe: the inner [AtomicReference] holds an immutable
 * map; updates use a CAS loop. Clients calling [lookupForConnection] always
 * see a consistent snapshot.
 */
class WorkerRegistry(
    private val degradedAfter: Int = 2,
    private val unreachableAfter: Int = 5,
) {
    private val entries = AtomicReference<Map<String, WorkerEntry>>(emptyMap())
    private val onUnreachable = ConcurrentHashMap<String, () -> Unit>()

    fun seed(workers: List<WorkerEntry>) {
        entries.set(workers.associateBy { it.endpoint })
    }

    fun all(): List<WorkerEntry> = entries.get().values.toList()

    /** Returns only HEALTHY workers that advertise [connectionId]. */
    fun lookupForConnection(connectionId: String): List<WorkerEntry> =
        entries
            .get()
            .values
            .filter { it.health == WorkerHealthStatus.HEALTHY && connectionId in it.supportedConnections }

    fun recordSuccess(
        endpoint: String,
        capabilities: GetCapabilitiesResponse,
    ) {
        update(endpoint) { existing ->
            val healthChanged = existing.health != WorkerHealthStatus.HEALTHY
            val capsChanged = existing.capabilities?.supportedConnectionsList != capabilities.supportedConnectionsList
            if (healthChanged) {
                log.info("Worker {} recovered to HEALTHY after {} failures", endpoint, existing.consecutiveFailures)
            }
            if (capsChanged) {
                log.info(
                    "Worker {} capabilities changed: connections {}",
                    endpoint,
                    capabilities.supportedConnectionsList,
                )
            }
            existing.copy(
                capabilities = capabilities,
                health = WorkerHealthStatus.HEALTHY,
                lastPolled = Instant.now(),
                consecutiveFailures = 0,
            )
        }
    }

    fun recordFailure(
        endpoint: String,
        cause: Throwable,
    ) {
        update(endpoint) { existing ->
            val nextFailures = existing.consecutiveFailures + 1
            val nextHealth =
                when {
                    nextFailures >= unreachableAfter -> WorkerHealthStatus.UNREACHABLE
                    nextFailures >= degradedAfter -> WorkerHealthStatus.DEGRADED
                    else -> existing.health.takeIf { it == WorkerHealthStatus.HEALTHY } ?: existing.health
                }
            if (nextHealth != existing.health) {
                log.warn(
                    "Worker {} transitioned {} → {} after {} failures: {}",
                    endpoint,
                    existing.health,
                    nextHealth,
                    nextFailures,
                    cause.message,
                )
                if (nextHealth == WorkerHealthStatus.UNREACHABLE) {
                    onUnreachable[endpoint]?.invoke()
                }
            }
            existing.copy(
                health = nextHealth,
                lastPolled = Instant.now(),
                consecutiveFailures = nextFailures,
            )
        }
    }

    /** Register a callback fired once when [endpoint] transitions to UNREACHABLE. */
    fun onUnreachable(
        endpoint: String,
        callback: () -> Unit,
    ) {
        onUnreachable[endpoint] = callback
    }

    private fun update(
        endpoint: String,
        transform: (WorkerEntry) -> WorkerEntry,
    ) {
        while (true) {
            val current = entries.get()
            val existing = current[endpoint] ?: return
            val updated = transform(existing)
            val next = current.toMutableMap()
            next[endpoint] = updated
            if (entries.compareAndSet(current, next)) return
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WorkerRegistry::class.java)
    }
}
