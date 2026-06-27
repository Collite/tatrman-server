package org.tatrman.kantheon.brontes.grpc

import org.tatrman.ariadne.v1.OverallStatus
import org.tatrman.worker.v1.ConnectionInfo
import org.tatrman.worker.v1.ConnectionPoolStatus
import org.tatrman.worker.v1.ConnectionStatus
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ExecutionLimits
import org.tatrman.worker.v1.GetCapabilitiesRequest
import org.tatrman.worker.v1.GetCapabilitiesResponse
import org.tatrman.worker.v1.GetStatusRequest
import org.tatrman.worker.v1.GetStatusResponse
import org.tatrman.worker.v1.ResultBatch
import org.tatrman.worker.v1.WorkerServiceGrpcKt
import kotlinx.coroutines.flow.Flow
import org.tatrman.kantheon.brontes.client.TranslatorHealth
import org.tatrman.kantheon.brontes.connection.ConnectionPoolManager
import org.tatrman.kantheon.brontes.pipeline.ExecutePipeline

/**
 * gRPC entrypoint for the MS SQL Worker.
 *
 *   - `execute` delegates to [ExecutePipeline] which produces the streamed
 *     `ResultBatch` flow (including error batches for the four error codes
 *     surfaced by the pipeline).
 *   - `getCapabilities` advertises engine/dialect/connection coverage and
 *     stateful-session support (always false for MS SQL per Round 6).
 *   - `getStatus` reports readiness, active queries, per-pool stats,
 *     per-`connection_id` reachability, and dependency health (translator)
 *     so operators can debug "where is the failure" without grepping logs.
 */
class WorkerServiceImpl(
    private val pipeline: ExecutePipeline,
    private val pool: ConnectionPoolManager,
    private val translatorHealth: TranslatorHealth,
    private val capabilities: WorkerCapabilities,
) : WorkerServiceGrpcKt.WorkerServiceCoroutineImplBase() {
    override fun execute(request: ExecuteRequest): Flow<ResultBatch> = pipeline.execute(request)

    override suspend fun getCapabilities(request: GetCapabilitiesRequest): GetCapabilitiesResponse {
        // Issue #57 Phase B — advertise the engine-side database + default schema for each
        // configured connection_id, so the dispatcher can concretize logical TableScan qnames
        // before forwarding the SQL to the worker.
        val connectionInfos =
            pool.connectionDetails().map { cfg ->
                ConnectionInfo
                    .newBuilder()
                    .setConnectionId(cfg.id)
                    .setDatabase(cfg.database)
                    .setDefaultSchema(cfg.defaultSchema)
                    .build()
            }
        return GetCapabilitiesResponse
            .newBuilder()
            .setEngineName(capabilities.engineName)
            .setEngineVersion(capabilities.engineVersion)
            .addAllSupportedLanguages(listOf("SQL"))
            .addAllSupportedDialects(listOf("MSSQL"))
            .addAllSupportedConnections(pool.supportedConnections)
            .addAllConnections(connectionInfos)
            .setLimits(
                ExecutionLimits
                    .newBuilder()
                    .setDefaultTimeoutSeconds(capabilities.limits.defaultTimeoutSeconds)
                    .setMaxTimeoutSeconds(capabilities.limits.maxTimeoutSeconds)
                    .setDefaultBatchSizeRows(capabilities.limits.defaultBatchSizeRows)
                    .setMaxBatchSizeRows(capabilities.limits.maxBatchSizeRows)
                    .setMaxRowLimit(0)
                    .setMaxBlobBytesPerCell(capabilities.limits.maxBlobBytesPerCell),
            ).setSupportsStatefulSessions(false)
            .setMaxConcurrentSessions(0)
            .setSessionIdleTimeoutSeconds(0)
            .setMaxSessionMemoryMb(0)
            .build()
    }

    override suspend fun getStatus(request: GetStatusRequest): GetStatusResponse {
        val stats = pool.poolStats()
        val active = stats.values.sumOf { it.active }
        val idle = stats.values.sumOf { it.idle }
        val max = stats.values.sumOf { it.max }
        val awaiting = stats.values.sumOf { it.awaiting }

        val connectionStatuses =
            pool.connectionDetails().map { cfg ->
                val probe = pool.lastProbe(cfg.id)
                val stat = stats[cfg.id]
                ConnectionStatus
                    .newBuilder()
                    .setConnectionId(cfg.id)
                    .setJdbcUrl(cfg.jdbcUrl)
                    .setDatabase(cfg.database)
                    .setDefaultSchema(cfg.defaultSchema)
                    .setConnected(probe?.connected == true)
                    .setLastError(probe?.lastError.orEmpty())
                    .setLastProbed(probe?.lastProbed?.toString().orEmpty())
                    .setActiveConnections(stat?.active ?: 0)
                    .setIdleConnections(stat?.idle ?: 0)
                    .setMaxConnections(stat?.max ?: cfg.maxPoolSize)
                    .setPendingAcquires(stat?.awaiting ?: 0)
                    .build()
            }

        val translatorDep = translatorHealth.current()
        val connectionsConfigured = pool.supportedConnections.isNotEmpty()
        val anyConnectionUp = connectionStatuses.any { it.connected }
        val translatorOk = translatorDep.status == OverallStatus.OK
        val ready = connectionsConfigured && anyConnectionUp && translatorOk

        val overall =
            when {
                !connectionsConfigured -> OverallStatus.DEGRADED
                !anyConnectionUp -> OverallStatus.DOWN
                !translatorOk -> OverallStatus.DEGRADED
                else -> OverallStatus.OK
            }

        return GetStatusResponse
            .newBuilder()
            .setReady(ready)
            .setActiveQueries(pipeline.activeQueries)
            .setPool(
                ConnectionPoolStatus
                    .newBuilder()
                    .setActiveConnections(active)
                    .setIdleConnections(idle)
                    .setMaxConnections(max)
                    .setPendingAcquires(awaiting),
            ).setActiveSessions(0)
            .addAllConnections(connectionStatuses)
            .addDependencies(translatorDep)
            .setOverallStatus(overall)
            .build()
    }

    data class WorkerCapabilities(
        val engineName: String,
        val engineVersion: String,
        val limits: ExecutePipeline.ExecutionLimits,
    )
}
