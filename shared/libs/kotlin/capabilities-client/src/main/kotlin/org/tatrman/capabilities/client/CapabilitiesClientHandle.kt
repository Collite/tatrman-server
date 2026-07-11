package org.tatrman.capabilities.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * Status handle returned from [CapabilitiesClient.startupRegister]. Inspect
 * [registrationId] / [lastHeartbeatStatus] from a `/health` endpoint, or call
 * [shutdown] in `ApplicationStopped` to stop the heartbeat coroutine.
 */
class CapabilitiesClientHandle internal constructor(
    private val scope: CoroutineScope,
    private val ownsScope: Boolean,
    @Volatile internal var jobs: List<Job> = emptyList(),
) {
    @Volatile
    var registrationId: String? = null
        internal set

    @Volatile
    var lastHeartbeatStatus: HeartbeatStatus = HeartbeatStatus.NEVER_REGISTERED
        internal set

    fun shutdown() {
        jobs.forEach { it.cancel() }
        if (ownsScope) {
            scope.cancel()
        }
    }
}
