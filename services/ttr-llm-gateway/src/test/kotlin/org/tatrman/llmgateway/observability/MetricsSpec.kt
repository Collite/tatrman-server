// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.tatrman.llmgateway.config.CircuitConfig
import org.tatrman.llmgateway.engine.CircuitBreaker

/**
 * LG-P5·S2·T1/T5 — the contracts §6 metric names + tags are pinned here (Grafana dashboards are written
 * against them; renames are contract breaks). The set moves under a scripted sequence, and the
 * `circuit_state` gauge tracks the breaker.
 */
class MetricsSpec :
    StringSpec({

        "the §6 counter set records and is queryable by exact name + tags" {
            val reg = SimpleMeterRegistry()
            val m = GatewayMetrics(reg)

            m.tokens("golem", "azure", "gpt-4o", "input", 10)
            m.tokens("golem", "azure", "gpt-4o", "output", 5)
            m.cost("golem", "azure", "gpt-4o", 0.0012)
            m.retry("azure", "RateLimit")
            m.fallback("gpt-4o", "claude-sonnet-4-6")
            m.cacheHit()
            m.cacheMiss()
            m.cacheMiss()

            reg
                .counter(
                    "llm_gateway_tokens_total",
                    "team",
                    "golem",
                    "provider",
                    "azure",
                    "model",
                    "gpt-4o",
                    "direction",
                    "input",
                ).count() shouldBeExactly 10.0
            reg
                .counter(
                    "llm_gateway_tokens_total",
                    "team",
                    "golem",
                    "provider",
                    "azure",
                    "model",
                    "gpt-4o",
                    "direction",
                    "output",
                ).count() shouldBeExactly 5.0
            reg
                .counter("llm_gateway_cost_usd_total", "team", "golem", "provider", "azure", "model", "gpt-4o")
                .count() shouldBeExactly 0.0012
            reg.counter("llm_gateway_retries_total", "provider", "azure", "reason", "RateLimit").count() shouldBeExactly
                1.0
            reg
                .counter(
                    "llm_gateway_fallbacks_total",
                    "from",
                    "gpt-4o",
                    "to",
                    "claude-sonnet-4-6",
                ).count() shouldBeExactly
                1.0
            reg.counter("llm_gateway_cache_hits_total").count() shouldBeExactly 1.0
            reg.counter("llm_gateway_cache_misses_total").count() shouldBeExactly 2.0
        }

        "circuit_state gauge reflects the breaker (closed=0, open=1)" {
            val reg = SimpleMeterRegistry()
            val cb = CircuitBreaker(CircuitConfig(failureThreshold = 1, cooldownMs = 60_000)) { 0L }
            GatewayMetrics(reg).registerCircuitGauges(listOf("azure"), cb)

            reg
                .get("llm_gateway_circuit_state")
                .tag("provider", "azure")
                .gauge()
                .value() shouldBeExactly 0.0 // closed
            cb.recordFailure("azure", "Provider5xx") // threshold=1 → open
            reg
                .get("llm_gateway_circuit_state")
                .tag("provider", "azure")
                .gauge()
                .value() shouldBeExactly 1.0 // open
        }
    })
