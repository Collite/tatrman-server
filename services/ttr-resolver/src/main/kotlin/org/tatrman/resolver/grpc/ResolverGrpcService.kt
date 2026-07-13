// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.grpc

import org.slf4j.LoggerFactory
import org.tatrman.resolver.v1.Capabilities
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.Resolution
import org.tatrman.resolver.v1.ResolverServiceGrpcKt

/**
 * The gRPC surface for the deterministic resolver core (RG-P5). The pipeline
 * (parse → extractUniversal → proposeDomainSpans → gateSpans → assemble) plugs in
 * at [resolve] in S1.T5; this skeleton returns an empty [Resolution] so the wire +
 * server boot are exercisable before the core lands.
 *
 * INVARIANT (RS-23): ZERO LLM. This class — and the whole module — has no
 * llm-gateway client on the classpath; `NoLlmDependencyTest` asserts it.
 */
class ResolverGrpcService : ResolverServiceGrpcKt.ResolverServiceCoroutineImplBase() {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun resolve(request: ResolveRequest): ResolveResponse {
        log.info("resolve conversation_id={} (skeleton — deterministic core lands at S1.T5)", request.conversationId)
        return ResolveResponse
            .newBuilder()
            .setResolution(Resolution.newBuilder().setConfidence(0.0))
            .setTraceId(request.conversationId)
            .setCapabilities(Capabilities.getDefaultInstance())
            .build()
    }
}
