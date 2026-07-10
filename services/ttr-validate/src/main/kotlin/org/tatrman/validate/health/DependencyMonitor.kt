package org.tatrman.validate.health

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Background liveness gate for the validator's hard dependencies (metadata + sql-security).
 *
 * The validator fails *hard* on a dependency outage (see `ValidateServiceImpl` — a metadata
 * or sql-security failure propagates as gRPC `UNAVAILABLE` rather than degrading). To stop
 * K8s routing traffic to a validator that cannot serve, this monitor probes each dependency
 * on its own coroutine and exposes a readiness verdict that `/ready` reflects (→ 503 while a
 * dependency is down). It **keeps trying with exponential backoff** so the pod re-readies
 * automatically once the dependency recovers — no restart needed.
 *
 * Per-dependency loop: probe → on success mark up and sleep [pollIntervalMs]; on failure mark
 * down and sleep `min(backoffBaseMs · 2^(failures-1), backoffMaxMs)` with ±50% jitter, then
 * retry. Each dependency backs off independently so a dead one never blocks probing a healthy
 * one. Starts NOT_READY until every dependency has answered once.
 */
class DependencyMonitor(
    private val dependencies: List<Dependency>,
    private val pollIntervalMs: Long,
    private val backoffBaseMs: Long,
    private val backoffMaxMs: Long,
) {
    /** A named dependency and its liveness probe. The probe returns the dependency's own
     *  readiness flag; throwing (e.g. UNAVAILABLE / DEADLINE_EXCEEDED) counts as "down". */
    class Dependency(
        val name: String,
        val probe: suspend () -> Boolean,
    )

    private val up = ConcurrentHashMap<String, Boolean>().apply { dependencies.forEach { put(it.name, false) } }

    /** True only when every dependency's last probe succeeded. */
    fun ready(): Boolean = dependencies.all { up[it.name] == true }

    /** Names of dependencies currently considered down (for the `/ready` 503 body). */
    fun down(): List<String> = dependencies.filter { up[it.name] != true }.map { it.name }

    /** Per-dependency up/down snapshot (for `/status`). */
    fun statuses(): Map<String, Boolean> = dependencies.associate { it.name to (up[it.name] == true) }

    /** Launch one probing coroutine per dependency in [scope]. Cancel the scope to stop. */
    fun start(scope: CoroutineScope) {
        dependencies.forEach { dep -> scope.launch { loop(dep) } }
    }

    private suspend fun loop(dep: Dependency) {
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            val ok =
                try {
                    dep.probe()
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    log.debug("Probe for '{}' failed: {}", dep.name, e.message)
                    false
                }
            if (ok) {
                if (up[dep.name] != true) log.info("Dependency '{}' is UP", dep.name)
                up[dep.name] = true
                consecutiveFailures = 0
                delay(pollIntervalMs)
            } else {
                if (up[dep.name] != false) log.warn("Dependency '{}' is DOWN — gating readiness", dep.name)
                up[dep.name] = false
                consecutiveFailures++
                delay(backoffDelayMs(consecutiveFailures))
            }
        }
    }

    /** Exponential backoff capped at [backoffMaxMs], with ±50% jitter to avoid thundering herd. */
    private fun backoffDelayMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 30)
        val raw = (backoffBaseMs shl shift).coerceAtMost(backoffMaxMs)
        val half = raw / 2
        return half + Random.nextLong(half + 1)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DependencyMonitor::class.java)
    }
}
