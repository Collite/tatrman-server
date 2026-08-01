// SPDX-License-Identifier: Apache-2.0
package org.tatrman.dispatch.registry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.tatrman.dispatch.client.WorkerClient
import kotlin.time.Duration.Companion.seconds

/**
 * Background coroutine that polls every Worker's `GetCapabilities` on a
 * fixed schedule, feeding results into [WorkerRegistry].
 *
 * One coroutine per endpoint. First poll is staggered by `index * 1s` to
 * avoid a startup storm (Phase 1.7 risks section). Subsequent polls run on
 * the configured interval.
 */
class CapabilityPoller(
    private val registry: WorkerRegistry,
    private val clients: Map<String, WorkerClient>,
    private val intervalSeconds: Long,
    private val scope: CoroutineScope,
) {
    private val jobs = mutableListOf<Job>()

    fun start() {
        clients.entries.forEachIndexed { index, (endpoint, client) ->
            val staggerMillis = index * 1_000L
            jobs.add(scope.launch(Dispatchers.IO) { runLoop(endpoint, client, staggerMillis) })
        }
        log.info("Capability poller started for {} workers, interval={}s", clients.size, intervalSeconds)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun runLoop(
        endpoint: String,
        client: WorkerClient,
        staggerMillis: Long,
    ) {
        if (staggerMillis > 0) delay(staggerMillis)
        while (scope.isActive) {
            try {
                val caps = client.getCapabilities()
                registry.recordSuccess(endpoint, caps)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                registry.recordFailure(endpoint, t)
            }
            delay(intervalSeconds.seconds)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CapabilityPoller::class.java)
    }
}
