// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.engine

import org.tatrman.llmgateway.config.CircuitConfig
import java.util.concurrent.ConcurrentHashMap

/** A provider's circuit snapshot for `/health/providers` (F-1 honest health). */
data class ProviderHealth(
    val state: String, // "closed" | "open" | "half-open"
    val consecutiveFailures: Int,
    val lastErrorClass: String?,
)

/**
 * Circuit-breaker-lite (design §3.2 C-4, architecture §5). **Per-instance, in-memory, deliberately not
 * shared** — replicas each keep their own counters (a shared breaker would couple failure domains; the
 * chain only ever *skips* open entries, never reorders). After [CircuitConfig.failureThreshold]
 * consecutive failures a provider opens; after [CircuitConfig.cooldownMs] it half-opens to admit a probe;
 * a success closes it, a failed probe re-arms the cooldown. Half-open admission is best-effort "one probe"
 * (lite — concurrent probes during the half-open window are not serialized).
 */
class CircuitBreaker(
    private val config: CircuitConfig,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private class State {
        var consecutiveFailures = 0
        var openedAtMs = 0L
        var lastErrorClass: String? = null
    }

    private val byProvider = ConcurrentHashMap<String, State>()

    /** True ⇒ the chain skips this provider's entries this pass (open and still cooling down). */
    fun shouldSkip(provider: String): Boolean {
        val s = byProvider[provider] ?: return false
        synchronized(s) {
            if (s.consecutiveFailures < config.failureThreshold) return false
            // open → skip until cooldown elapses; then half-open (admit a probe, don't skip)
            return nowMs() - s.openedAtMs < config.cooldownMs
        }
    }

    fun recordSuccess(provider: String) {
        val s = byProvider.getOrPut(provider) { State() }
        synchronized(s) {
            s.consecutiveFailures = 0
            s.openedAtMs = 0
            s.lastErrorClass = null
        }
    }

    fun recordFailure(
        provider: String,
        errorClass: String,
    ) {
        val s = byProvider.getOrPut(provider) { State() }
        synchronized(s) {
            s.consecutiveFailures++
            s.lastErrorClass = errorClass
            // At/over threshold, (re)stamp the open time — a failed half-open probe re-arms the cooldown.
            if (s.consecutiveFailures >= config.failureThreshold) s.openedAtMs = nowMs()
        }
    }

    fun state(provider: String): String {
        val s = byProvider[provider] ?: return "closed"
        synchronized(s) {
            if (s.consecutiveFailures < config.failureThreshold) return "closed"
            return if (nowMs() - s.openedAtMs < config.cooldownMs) "open" else "half-open"
        }
    }

    /** A snapshot for `/health/providers` — every provider the breaker has observed. */
    fun snapshot(): Map<String, ProviderHealth> =
        byProvider.mapValues { (provider, s) ->
            synchronized(s) { ProviderHealth(state(provider), s.consecutiveFailures, s.lastErrorClass) }
        }
}
