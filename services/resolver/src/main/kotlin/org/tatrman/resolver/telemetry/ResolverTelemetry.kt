// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.telemetry

import com.typesafe.config.Config
import io.opentelemetry.api.OpenTelemetry
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

/**
 * The resolver's OpenTelemetry SDK.
 *
 * **Why this file exists (TG-P0-F1, 2026-08-14).** It did not, and that was the defect: the
 * resolver was the only service in the constellation that never called [createOpenTelemetrySdk].
 * `ResolverGrpcService`'s KDoc has claimed since RV-P2.4.T5 that *"the service passes the real
 * one"* — it passed nothing, so the tracer defaulted to noop and the `resolve.gate` span
 * described there **had never been emitted from any cluster**. The cost landed on 2026-08-13:
 * the resolver was the only first-hand witness of the lattice and it was mute, so a live debug
 * ended in two hypotheses it could not separate.
 *
 * Two consequences, both fixed by wiring this in:
 * 1. **spans** — `resolve.gate` carries hypotheses-in / bindings-out, which the KDoc rightly calls
 *    the ladder's whole health signal: *"a rung whose hypotheses stop surviving the gate is a rung
 *    that has started guessing, and it is invisible in a latency graph."*
 * 2. **logs** — [createOpenTelemetrySdk] installs the Logback `OpenTelemetryAppender`, so SLF4J
 *    records ship over OTLP. On an estate whose collector only receives OTLP (hartland's Alloy has
 *    no pod tailing at all), that is the *only* way the resolver reaches Loki. Before this, its
 *    logs existed solely in `kubectl logs`.
 *
 * ⚠ **This does not yet correlate with the turn's trace, and that is not a bug here.** The SDK
 * built by [createOpenTelemetrySdk] sets no propagators, and — decisively — golem's
 * `ResolutionCoreClient` builds its channel with no client interceptor, so no `traceparent` ever
 * reaches this service to be extracted. Adding propagators on this side alone would be another
 * knob that does nothing. The two-sided fix is kantheon#34; until it lands, expect resolver spans
 * and logs to carry their **own** trace, findable by `service_name`, not joined to the turn.
 */
fun resolverTelemetry(config: Config): OpenTelemetry {
    // ⚑ `enabled` is NOT optional. `createOpenTelemetrySdk` defaults it to true, and an SDK built
    // against an absent collector spams "Failed to export" every batch interval against
    // localhost:4317 — the trap FuzzyTelemetry documents and dispatch still has.
    val enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled")
    return createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "resolver",
            protocol = System.getenv("RESOLVER_OTEL_PROTOCOL") ?: "grpc",
        ),
        enabled = enabled,
    )
}
