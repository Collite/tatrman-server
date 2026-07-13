// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec

/**
 * RG-P5 — the phase's ONE rule made mechanical (RS-23): the resolver module has
 * ZERO LLM. The llm-gateway CLIENT (`:shared:libs:kotlin:ttr-llm-client`) must not
 * be on the runtime classpath — the deterministic core never calls an LLM; the
 * escalation ladder is the kantheon Resolving Agent's job.
 *
 * NB the shared `org.tatrman.llm.v1.*` gRPC STUB rides in via `:shared:proto` and
 * is harmless (a generated message type, not a caller); the boundary this test
 * guards is the *client* that actually makes LLM calls.
 */
class NoLlmDependencyTest :
    StringSpec({

        "the llm-gateway client is NOT on the resolver classpath" {
            for (cls in listOf(
                "org.tatrman.llm.client.LlmGatewayClient",
                "org.tatrman.llm.client.LlmGatewayPromptExecutor",
            )) {
                shouldThrow<ClassNotFoundException> { Class.forName(cls) }
            }
        }
    })
