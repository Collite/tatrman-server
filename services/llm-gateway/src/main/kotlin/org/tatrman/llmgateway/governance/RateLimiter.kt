// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import io.lettuce.core.ScriptOutputType
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.tatrman.llmgateway.store.RedisConn

/**
 * Per-key rolling-window rate limiter (D-4, contracts §4). The window is **two adjacent minute buckets**
 * (`llm-gateway:rl:{keyId}:{epochMinute}`) so the count survives across a minute boundary; INCR + EXPIRE
 * run in **one Lua script** so they can never split (the atomicity the read-modify-write anti-pattern
 * FI-6 warns about). State is in Redis, so the limit holds across replicas — two pods share one counter.
 *
 * **Fail-open on a Redis outage** (⚑ Bora, one boolean [failOpen]): a down Redis must not take the data
 * plane down — the request is allowed, logged, and counted on a metric; readiness already reports Redis
 * DOWN (LG-P1). Storeless boots (no Redis) simply don't rate-limit.
 */
class RateLimiter(
    private val redis: RedisConn?,
    private val metrics: MeterRegistry? = null,
    private val failOpen: Boolean = true,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    data class Decision(
        val allowed: Boolean,
        val retryAfterSeconds: Long,
    )

    /** effectiveLimit = min(team rpm, per-key override) — min-wins (D-3); null/≤0 ⇒ unlimited. */
    fun check(
        keyId: String,
        teamRpm: Int?,
        keyRpmOverride: Int?,
    ): Decision {
        val limit = listOfNotNull(teamRpm, keyRpmOverride).minOrNull() ?: return ALLOW
        if (limit <= 0) return ALLOW
        val r = redis ?: return ALLOW // storeless dev boot — no limiting

        val epochMin = nowMs() / 60_000
        val current = "$PREFIX$keyId:$epochMin"
        val previous = "$PREFIX$keyId:${epochMin - 1}"
        val total =
            try {
                r.sync().eval<Long>(LUA, ScriptOutputType.INTEGER, current, previous)
            } catch (e: Exception) {
                log.warn("rate-limit Redis error for key {} — failing {}", keyId, if (failOpen) "open" else "closed", e)
                metrics?.counter("llm_gateway_ratelimit_redis_error_total")?.increment()
                return if (failOpen) ALLOW else Decision(false, retryAfterSeconds())
            }
        return if (total > limit) Decision(false, retryAfterSeconds()) else ALLOW
    }

    // Seconds until the current minute bucket rolls (when the oldest counted requests start to age out).
    private fun retryAfterSeconds(): Long = 60 - (nowMs() / 1000 % 60)

    private companion object {
        val log = LoggerFactory.getLogger(RateLimiter::class.java)
        const val PREFIX = "llm-gateway:rl:"
        val ALLOW = Decision(true, 0)

        // INCR the current bucket (+120 s TTL so both adjacent buckets outlive the window), add the
        // previous bucket's count, return the rolling total — one round trip, indivisible.
        val LUA =
            """
            local cur = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], 120)
            local prev = tonumber(redis.call('GET', KEYS[2]) or '0')
            return cur + prev
            """.trimIndent()
    }
}
