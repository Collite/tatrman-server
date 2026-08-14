// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import org.tatrman.resolver.telemetry.resolverTelemetry

/**
 * TG-P0-F1 — the resolver's OTel SDK, which until 2026-08-14 was never built.
 *
 * The flag matters in **both** directions and each has a real failure behind it:
 * - enabled-when-off would spam "Failed to export" against a collector that is not there every
 *   batch interval (the trap `FuzzyTelemetry` documents, and dispatch still has — it omits
 *   `enabled` entirely, so `createOpenTelemetrySdk` defaults it to true);
 * - off-when-enabled is the defect this fixes — a noop tracer, no `resolve.gate` span, and no
 *   logs over OTLP, on the one service that witnesses the lattice first-hand.
 */
class ResolverTelemetryTest :
    StringSpec({

        "telemetry disabled ⇒ the noop SDK, so nothing is exported and no appender is installed" {
            val config = ConfigFactory.parseString("telemetry { enabled = false }")
            resolverTelemetry(config) shouldBe OpenTelemetry.noop()
        }

        "telemetry absent ⇒ noop, NOT the library default" {
            // `createOpenTelemetrySdk`'s own `enabled` parameter defaults to TRUE. A service that
            // forwards a missing config path unguarded therefore builds exporters it never wanted.
            // The resolver must read an absent `telemetry.enabled` as off.
            resolverTelemetry(ConfigFactory.empty()) shouldBe OpenTelemetry.noop()
        }

        "telemetry enabled ⇒ a real SDK whose tracer records" {
            val config = ConfigFactory.parseString("telemetry { enabled = true }")
            val otel = resolverTelemetry(config)
            try {
                otel shouldNotBe OpenTelemetry.noop()

                // The discriminator that matters is not the type but the behaviour: a noop tracer
                // hands back an invalid, non-recording span context, so `resolve.gate` would be
                // silently dropped rather than exported.
                val span = otel.getTracer("org.tatrman.resolver").spanBuilder("resolve.gate").startSpan()
                try {
                    span.spanContext.isValid shouldBe true
                    span.isRecording shouldBe true
                } finally {
                    span.end()
                }
            } finally {
                // Shut the exporters down; otherwise the batch processors keep retrying against
                // localhost:4317 for the rest of the test JVM's life.
                (otel as? OpenTelemetrySdk)?.close()
            }
        }
    })
