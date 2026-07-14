// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.grpc

import org.slf4j.LoggerFactory
import org.tatrman.resolver.pipeline.ResolverPipeline
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
) : ResolverServiceGrpcKt.ResolverServiceCoroutineImplBase() {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun resolve(request: ResolveRequest): ResolveResponse {
        log.info("resolve conversation_id={}", request.conversationId)
        return pipeline.resolve(request)
    }
}
