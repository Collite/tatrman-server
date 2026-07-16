// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.obs

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter

/**
 * A8.7 observability for geo grounding. One counter keyed by (outcome, source) and one latency
 * histogram over the same attributes, so a dashboard can slice OK/AWAITING_CLARIFICATION/UNGROUNDABLE
 * by RULES vs LLM vs the deterministic no-result paths.
 *
 * Latency is measured with `System.nanoTime()` (a monotonic duration timer) — NOT a wall clock, so
 * it does not violate the "geo grounding" rule (that guards `reference_datetime`).
 */
class GeoMetrics(
    meter: Meter,
) {
    private val groundCounter =
        meter
            .counterBuilder("geo_ground_total")
            .setDescription("Total geo Ground calls, by outcome and source")
            .setUnit("requests")
            .build()

    private val latencyHistogram =
        meter
            .histogramBuilder("geo_ground_latency_ms")
            .setDescription("geo Ground wall-time latency, by outcome and source")
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
        fun noop(): GeoMetrics = GeoMetrics(OpenTelemetry.noop().getMeter("geo"))
    }
}
