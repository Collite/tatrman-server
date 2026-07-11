package org.tatrman.dispatch.sticky

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Pairs `session_id` → Worker endpoint for sticky-routing. Only sessions
 * that explicitly opt in (non-empty session_id AND the chosen Worker
 * advertises `supports_stateful_sessions = true`) appear here.
 *
 * Eviction policies (Phase 1.7 Section C):
 *   - by-endpoint: when a Worker transitions to UNREACHABLE the registry
 *     drops every session pinned to it. Subsequent dispatches with that
 *     session_id surface a `session_lost` error to the caller, who decides
 *     whether to retry.
 *   - TTL: a sweep coroutine evicts entries idle for more than
 *     `idleTimeout`. Default 60min. Each [findSticky] call updates the
 *     entry's last-seen instant.
 *   - bound: hard cap at [maxEntries] (default 10k). Beyond that, new
 *     [recordSticky] calls drop the LRU entry.
 *
 * The implementation favours simplicity over micro-optimisation; the v1
 * default cap of 10k entries is well below contention thresholds for a
 * `ConcurrentHashMap`.
 */
class StickyRegistry(
    val idleTimeout: Duration = Duration.ofMinutes(60),
    private val maxEntries: Int = 10_000,
    private val clock: () -> Instant = Instant::now,
) {
    private val map = ConcurrentHashMap<String, Entry>()

    data class Entry(
        val sessionId: String,
        val endpoint: String,
        val lastSeen: Instant,
    )

    fun recordSticky(
        sessionId: String,
        endpoint: String,
    ) {
        if (sessionId.isEmpty()) return
        if (map.size >= maxEntries) evictLeastRecent()
        map[sessionId] = Entry(sessionId, endpoint, clock())
        log.debug("Recorded sticky session {} → {}", sessionId, endpoint)
    }

    fun findSticky(sessionId: String): String? {
        if (sessionId.isEmpty()) return null
        val entry = map[sessionId] ?: return null
        map[sessionId] = entry.copy(lastSeen = clock())
        return entry.endpoint
    }

    /** Drop a single session — used on `sticky_failover` (DF-D02) so the failed-over caller's
     *  next dispatch starts fresh against the new pod's state.
     */
    fun evictSession(sessionId: String): Boolean = map.remove(sessionId) != null

    fun evictByEndpoint(endpoint: String): Int {
        var n = 0
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.endpoint == endpoint) {
                it.remove()
                n++
            }
        }
        if (n > 0) log.info("Evicted {} sticky sessions for unreachable worker {}", n, endpoint)
        return n
    }

    fun sweepIdle(): Int {
        val cutoff = clock().minus(idleTimeout)
        var n = 0
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (it
                    .next()
                    .value.lastSeen
                    .isBefore(cutoff)
            ) {
                it.remove()
                n++
            }
        }
        if (n > 0) log.info("Swept {} idle sticky sessions (idle > {})", n, idleTimeout)
        return n
    }

    fun size(): Int = map.size

    fun activeSessionsForEndpoint(endpoint: String): Int = map.values.count { it.endpoint == endpoint }

    private fun evictLeastRecent() {
        val oldest = map.values.minByOrNull { it.lastSeen } ?: return
        map.remove(oldest.sessionId)
        log.warn("Sticky registry full; evicted LRU session {}", oldest.sessionId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(StickyRegistry::class.java)
    }
}
