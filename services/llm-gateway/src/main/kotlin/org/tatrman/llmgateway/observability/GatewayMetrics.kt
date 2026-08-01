// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.tatrman.llmgateway.engine.CircuitBreaker

/**
 * The gateway metric set (contracts §6, F-1). **Names are a contract** — Grafana dashboards are written
 * against them, so renames are breaking changes. Budget metrics (`llm_gateway_budget_used_ratio` /
 * `_breach_total`) are owned by `BudgetService` (LG-P4·S2); this covers tokens, cost, retries, fallbacks,
 * circuit state, and cache hits/misses.
 */
class GatewayMetrics(
    private val registry: MeterRegistry,
) {
    fun tokens(
        team: String,
        provider: String,
        model: String,
        direction: String, // "input" | "output"
        n: Long,
    ) {
        if (n <= 0) return
        registry
            .counter(
                "llm_gateway_tokens_total",
                "team",
                team,
                "provider",
                provider,
                "model",
                model,
                "direction",
                direction,
            ).increment(n.toDouble())
    }

    fun cost(
        team: String,
        provider: String,
        model: String,
        usd: Double,
    ) {
        if (usd <= 0.0) return
        registry
            .counter(
                "llm_gateway_cost_usd_total",
                "team",
                team,
                "provider",
                provider,
                "model",
                model,
            ).increment(usd)
    }

    fun retry(
        provider: String,
        reason: String,
    ) = registry.counter("llm_gateway_retries_total", "provider", provider, "reason", reason).increment()

    fun fallback(
        from: String,
        to: String,
    ) = registry.counter("llm_gateway_fallbacks_total", "from", from, "to", to).increment()

    fun cacheHit() = registry.counter("llm_gateway_cache_hits_total").increment()

    fun cacheMiss() = registry.counter("llm_gateway_cache_misses_total").increment()

    /** Register a `llm_gateway_circuit_state{provider}` gauge per provider (closed=0, open=1, half-open=2). */
    fun registerCircuitGauges(
        providers: Collection<String>,
        circuit: CircuitBreaker,
    ) {
        providers.toSet().forEach { p ->
            Gauge
                .builder("llm_gateway_circuit_state") {
                    when (circuit.state(p)) {
                        "open" -> 1.0
                        "half-open" -> 2.0
                        else -> 0.0
                    }
                }.tag("provider", p)
                .register(registry)
        }
    }
}
