package org.tatrman.kantheon.kyklop.routing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * DF-D01 / Phase 06 C3 — tracks in-flight dispatch count per worker endpoint so the dispatcher
 * can prefer the least-loaded worker for non-sticky / no-session traffic. "Load" here is just
 * the number of currently-running dispatches the dispatcher knows about — a worker-reported
 * metric (queue depth, CPU) would be richer but needs the worker side to publish it, deferred.
 *
 * Cheap to read (`load(endpoint)`) on every routing decision. Thread-safe: each endpoint has
 * its own `AtomicInteger` with no cross-key coordination.
 */
class LoadTracker(
    initial: Map<String, Int> = emptyMap(),
) {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    init {
        initial.forEach { (endpoint, n) -> counts.computeIfAbsent(endpoint) { AtomicInteger() }.set(n) }
    }

    fun incrementAndGet(endpoint: String): Int = counts.computeIfAbsent(endpoint) { AtomicInteger() }.incrementAndGet()

    fun decrement(endpoint: String) {
        counts[endpoint]?.decrementAndGet()
    }

    fun load(endpoint: String): Int = counts[endpoint]?.get() ?: 0
}
