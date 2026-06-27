package org.tatrman.kantheon.ariadne.refresh

import org.tatrman.kantheon.ariadne.graph.ModelGraph
import org.tatrman.kantheon.ariadne.reconcile.ModelReconciler
import org.tatrman.kantheon.ariadne.registry.MetadataRegistry
import org.tatrman.kantheon.ariadne.source.ModelSource
import org.tatrman.kantheon.ariadne.source.SourceSnapshot
import org.tatrman.kantheon.ariadne.source.logModelLoadIssues
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * DF-M04 / Phase 07 B2 — `Refresh` RPC handler. Re-loads model sources on demand, atomically
 * swaps the registry snapshot, returns per-source results.
 *
 * Concurrency:
 *   - `force = false` (default): `tryLock` on the refresh mutex. If a refresh is already in
 *     flight, return all results with `success = false` and `error_message = "refresh_in_flight"`
 *     so the caller can retry.
 *   - `force = true`: blocks on the mutex, runs after the in-flight refresh completes.
 *   - The snapshot swap inside `MetadataRegistry.swap(...)` is atomic (CAS over an
 *     `AtomicReference<RegistrySnapshot>`), so concurrent `GetObject`/etc. callers never see a
 *     half-loaded model — they keep observing the prior snapshot until the swap commits.
 *
 * Per-source vs full refresh:
 *   - `sourceId = ""` (empty) → re-load every configured source.
 *   - `sourceId = "X"` → re-load just source X, reuse the most recently cached snapshots for
 *     the others, reconcile + swap. The refresher caches `lastSnapshotBySource` on every
 *     successful load.
 *
 * `SourceRefreshResult` semantics:
 *   - `old_version` / `new_version` are the *source's* version stamp before/after this refresh.
 *   - `snapshot_swapped = true` when the reconciled model differs from the prior model version
 *     (or, conservatively, when at least one source produced a new version).
 *   - `new_model_version` carries the swapped-in model version when `snapshot_swapped`.
 */
class MetadataRefresher(
    private val sources: List<ModelSource>,
    private val sourceIds: List<String>,
    private val reconciler: ModelReconciler,
    private val registry: MetadataRegistry,
) {
    init {
        require(sources.size == sourceIds.size) {
            "sources and sourceIds must have the same length (${sources.size} vs ${sourceIds.size})"
        }
    }

    private val log = LoggerFactory.getLogger(MetadataRefresher::class.java)
    private val mutex = Mutex()

    /** Snapshot of each source's most recent contribution, keyed by source id. */
    private val lastSnapshotBySource = ConcurrentHashMap<String, SourceSnapshot>()

    /** Seed the cache from the initial load so per-source refresh can find prior snapshots. */
    fun recordInitial(snapshots: List<SourceSnapshot>) {
        for (s in snapshots) lastSnapshotBySource[s.sourceId] = s
    }

    data class SourceResult(
        val sourceId: String,
        val success: Boolean,
        val errorMessage: String = "",
        val oldVersion: String = "",
        val newVersion: String = "",
        val snapshotSwapped: Boolean = false,
        val newModelVersion: String = "",
    )

    /**
     * @param sourceId empty for full refresh; otherwise the specific source id to re-load.
     * @param force when true, wait for an in-flight refresh; when false, return [SourceResult]s
     *   with `success=false` and `error_message="refresh_in_flight"` if the lock is held.
     */
    suspend fun refresh(
        sourceId: String,
        force: Boolean,
    ): List<SourceResult> {
        if (sourceId.isNotEmpty() && sourceId !in sourceIds) {
            return listOf(
                SourceResult(
                    sourceId = sourceId,
                    success = false,
                    errorMessage = "unknown_source_id: $sourceId",
                ),
            )
        }
        if (!force) {
            val acquired = mutex.tryLock()
            if (!acquired) {
                return sourceIds.map {
                    SourceResult(sourceId = it, success = false, errorMessage = "refresh_in_flight")
                }
            }
            try {
                return doRefresh(sourceId)
            } finally {
                mutex.unlock()
            }
        }
        return mutex.withLock { doRefresh(sourceId) }
    }

    private fun doRefresh(sourceId: String): List<SourceResult> {
        val priorModelVersion =
            registry
                .read()
                ?.model
                ?.version
                ?.value ?: ""
        val results = mutableListOf<SourceResult>()

        // Re-load: all when sourceId is empty; otherwise just the target — others reuse cache.
        val freshOrCached = mutableListOf<SourceSnapshot>()
        var anySuccessfulNewVersion = false
        for ((idx, src) in sources.withIndex()) {
            val id = sourceIds[idx]
            val prior = lastSnapshotBySource[id]
            val oldVersion = prior?.version ?: ""
            if (sourceId.isEmpty() || sourceId == id) {
                val loaded =
                    runCatching { src.load() }
                        .onFailure { log.warn("Source '$id' load failed: {}", it.message) }
                if (loaded.isSuccess) {
                    val snap = loaded.getOrThrow()
                    lastSnapshotBySource[id] = snap
                    freshOrCached += snap
                    if (snap.version != oldVersion) anySuccessfulNewVersion = true
                    results += SourceResult(id, success = true, oldVersion = oldVersion, newVersion = snap.version)
                } else {
                    if (prior != null) freshOrCached += prior // keep model consistent
                    results +=
                        SourceResult(
                            sourceId = id,
                            success = false,
                            errorMessage = loaded.exceptionOrNull()?.message.orEmpty(),
                            oldVersion = oldVersion,
                        )
                }
            } else {
                if (prior != null) {
                    freshOrCached += prior
                    results += SourceResult(id, success = true, oldVersion = oldVersion, newVersion = oldVersion)
                }
            }
        }

        if (!anySuccessfulNewVersion && results.all { it.success || it.errorMessage.isNotEmpty() }) {
            // No source changed; skip the reconcile + swap. Mark each successful result with
            // snapshot_swapped = false and bail out (cheaper than rebuilding the graph).
            return results
        }

        return try {
            val reconciled = reconciler.reconcile(freshOrCached)
            val graph = ModelGraph.build(reconciled.model)
            registry.swap(reconciled.model, graph, reconciled.warnings + reconciled.errors)
            // Only reached when a source actually changed (the no-change short-circuit returns
            // earlier), so this won't re-spam the issue list every quiet refresh interval.
            log.info(
                "Metadata refresh swapped model: version={} warnings={} errors={}",
                reconciled.model.version.value,
                reconciled.warnings.size,
                reconciled.errors.size,
            )
            log.logModelLoadIssues(reconciled.errors, reconciled.warnings)
            val newModelVersion = reconciled.model.version.value
            val swapped = newModelVersion != priorModelVersion
            results.mapIndexed { i, r ->
                if (r.success) {
                    r.copy(snapshotSwapped = swapped, newModelVersion = if (swapped) newModelVersion else "")
                } else {
                    r
                }
            }
        } catch (t: Throwable) {
            log.warn("Reconcile/swap during refresh failed: {}", t.message)
            results.map { it.copy(success = false, errorMessage = "reconcile_failed: ${t.message}") }
        }
    }
}
