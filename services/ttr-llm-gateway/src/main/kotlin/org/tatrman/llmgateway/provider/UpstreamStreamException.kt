// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider

import org.tatrman.llmgateway.wire.GatewayError

/**
 * Thrown from a [ProviderHandler.stream] flow when the upstream fails **before emitting any frame** — a
 * non-2xx status, or a connection error before the first byte of the body. It carries the typed
 * [GatewayError] so the LG-P3·S2 engine can decide retry/fallback *before the SSE response attaches*
 * (the before-first-token rule). After the first frame, failures are NOT this exception — they surface as
 * the upstream channel closing and become the mid-stream error-frame contract in `pumpSse` (§1.4).
 */
class UpstreamStreamException(
    val error: GatewayError,
) : RuntimeException(error::class.simpleName)
