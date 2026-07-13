package org.tatrman.money.obs

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter

/**
 * A10.6 observability for money grounding. One counter keyed by (outcome, source) and one latency
 * histogram over the same attributes, so a dashboard can slice OK/AWAITING_CLARIFICATION/UNGROUNDABLE
 * by RULES vs LLM vs the deterministic no-result paths. Latency is a monotonic `System.nanoTime()`
 * duration, not a wall clock.
 */
class MoneyMetrics(
    meter: Meter,
) {
    private val groundCounter =
        meter
            .counterBuilder("money_ground_total")
            .setDescription("Total money Ground calls, by outcome and source")
            .setUnit("requests")
            .build()

    private val latencyHistogram =
        meter
            .histogramBuilder("money_ground_latency_ms")
            .setDescription("money Ground wall-time latency, by outcome and source")
            .setUnit("ms")
            .build()

    /** Record one Ground call. [outcome] = GroundResponse.Status name; [source] = RULES | LLM | NONE. */
    fun recordGround(
        outcome: String,
        source: String,
        latencyMs: Double,
    ) {
        val attrs = Attributes.of(OUTCOME, outcome, SOURCE, source)
        groundCounter.add(1, attrs)
        latencyHistogram.record(latencyMs, attrs)
    }

    companion object {
        private val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
        private val SOURCE: AttributeKey<String> = AttributeKey.stringKey("source")

        /** No-op metrics for tests / fixture boots — records into the noop SDK. */
        fun noop(): MoneyMetrics = MoneyMetrics(OpenTelemetry.noop().getMeter("money"))
    }
}
