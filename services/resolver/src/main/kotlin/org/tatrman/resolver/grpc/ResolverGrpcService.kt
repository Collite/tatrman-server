// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import org.slf4j.LoggerFactory
import shared.otel.withSpan
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.token.ResumeTokenException
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.GateResponse
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.ResolverServiceGrpcKt

/**
 * The gRPC surface for the deterministic resolver core (RG-P5). Delegates to the
 * [ResolverPipeline] (parse → extractUniversal → proposeDomainSpans → gateSpans →
 * assemble). This class holds no logic beyond the wire boundary.
 *
 * INVARIANT (RS-23): ZERO LLM. This class — and the whole module — has no
 * llm-gateway client on the classpath; `NoLlmDependencyTest` asserts it.
 */
class ResolverGrpcService(
    private val pipeline: ResolverPipeline,
    // RV-P2.4.T5 — defaulted to the noop SDK so every existing construction site (and every test)
    // keeps working and pays nothing; the service passes the real one.
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) : ResolverServiceGrpcKt.ResolverServiceCoroutineImplBase() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tracer: Tracer = openTelemetry.getTracer("org.tatrman.resolver")

    override suspend fun resolve(request: ResolveRequest): ResolveResponse {
        log.info("resolve conversation_id={}", request.conversationId)
        // A rejected/expired/mismatched resume token is a caller error, not a server
        // fault — map it to UNAUTHENTICATED carrying the RG-RES-002 reason instead of
        // letting the throw surface as an opaque UNKNOWN (RG-P6 review J).
        return try {
            pipeline.resolve(request)
        } catch (e: ResumeTokenException) {
            log.info("resume token rejected conversation_id={}: {}", request.conversationId, e.reason)
            throw StatusException(Status.UNAUTHENTICATED.withDescription(e.message).withCause(e))
        }
    }

    /**
     * RV-P2.4 — the re-gate sibling (Q-13 = B). Stateless: the caller carries the lattice.
     *
     * T5 — one span per gate call, carrying hypotheses IN and bindings OUT. Those two numbers are
     * the whole health signal for the ladder: a rung whose hypotheses stop surviving the gate is a
     * rung that has started guessing, and it is invisible in a latency graph.
     */
    override suspend fun gate(request: GateRequest): GateResponse =
        tracer.withSpan(
            "resolve.gate",
            attributes = mapOf("rv.hypotheses.in" to request.hypothesesCount.toString()),
        ) {
            val response = pipeline.gate(request)
            log.info(
                "gate hypotheses_in={} bindings_out={} gaps_open={}",
                request.hypothesesCount,
                response.gatedBindingsCount,
                response.updatedGapsCount,
            )
            response
        }
}
