// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.llmgateway.config.CircuitConfig

/**
 * LG-P3·S2·T2 — circuit-breaker-lite (design §3.2 C-4). Deterministic with an injected clock: threshold
 * consecutive failures open a provider; after cooldown it half-opens for a probe; success closes it, a
 * failed probe re-arms the cooldown. Per-provider, independent, per-instance.
 */
class CircuitBreakerSpec :
    StringSpec({

        val config = CircuitConfig(failureThreshold = 3, cooldownMs = 1_000)

        "unknown provider is closed and never skipped" {
            val cb = CircuitBreaker(config) { 0 }
            cb.shouldSkip("azure") shouldBe false
            cb.state("azure") shouldBe "closed"
        }

        "opens after `failureThreshold` consecutive failures; entries skipped while cooling down" {
            var now = 0L
            val cb = CircuitBreaker(config) { now }

            cb.recordFailure("azure", "Provider5xx")
            cb.shouldSkip("azure") shouldBe false // 1 < 3
            cb.recordFailure("azure", "Provider5xx")
            cb.shouldSkip("azure") shouldBe false // 2 < 3
            cb.recordFailure("azure", "Provider5xx")
            cb.state("azure") shouldBe "open" // 3 == threshold
            cb.shouldSkip("azure") shouldBe true
        }

        "half-opens after cooldown (admits a probe); a failed probe re-arms the cooldown" {
            var now = 0L
            val cb = CircuitBreaker(config) { now }
            repeat(3) { cb.recordFailure("azure", "Provider5xx") } // open at t=0

            now = 999
            cb.shouldSkip("azure") shouldBe true // still cooling
            now = 1_000
            cb.state("azure") shouldBe "half-open"
            cb.shouldSkip("azure") shouldBe false // admit the probe

            cb.recordFailure("azure", "Timeout") // probe fails → re-arm at t=1000
            cb.shouldSkip("azure") shouldBe true
            now = 2_000
            cb.shouldSkip("azure") shouldBe false // half-open again
        }

        "a success closes the circuit and resets the counter" {
            var now = 0L
            val cb = CircuitBreaker(config) { now }
            repeat(3) { cb.recordFailure("azure", "Provider5xx") }
            cb.state("azure") shouldBe "open"

            cb.recordSuccess("azure")
            cb.state("azure") shouldBe "closed"
            cb.shouldSkip("azure") shouldBe false
            // and it takes a fresh full threshold to open again
            cb.recordFailure("azure", "Network")
            cb.recordFailure("azure", "Network")
            cb.shouldSkip("azure") shouldBe false // only 2
        }

        "providers are independent" {
            val cb = CircuitBreaker(config) { 0 }
            repeat(3) { cb.recordFailure("azure", "Provider5xx") }
            cb.shouldSkip("azure") shouldBe true
            cb.shouldSkip("anthropic") shouldBe false // untouched
        }

        "snapshot reports state, consecutive failures, and last error class (F-1 health)" {
            val cb = CircuitBreaker(config) { 0 }
            repeat(3) { cb.recordFailure("azure", "Provider5xx") }
            cb.recordFailure("anthropic", "RateLimit") // one, still closed

            val snap = cb.snapshot()
            snap.getValue("azure").state shouldBe "open"
            snap.getValue("azure").consecutiveFailures shouldBe 3
            snap.getValue("azure").lastErrorClass shouldBe "Provider5xx"
            snap.getValue("anthropic").state shouldBe "closed"
            snap.getValue("anthropic").consecutiveFailures shouldBe 1
        }
    })
