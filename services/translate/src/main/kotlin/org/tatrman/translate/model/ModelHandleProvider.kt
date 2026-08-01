// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.model

import org.tatrman.translator.framework.ModelHandle

/**
 * Source of [ModelHandle] snapshots for the translator service.
 *
 * Two implementations:
 *   - [StaticModelHandleProvider]: hands out a single fixed handle. Used by
 *     test contexts and as the v1.4 stand-in until the metadata gRPC client
 *     (Phase 1.4 Section B follow-up) is wired up.
 *   - `MetadataServiceModelHandleProvider`: fetches snapshots from the
 *     metadata gRPC service with periodic refresh + ETag-based skipping.
 *     Lands when Phase 1.2 (metadata service) ships.
 *
 * Each gRPC RPC captures the current snapshot once at entry and holds the
 * reference for the duration of the call. This avoids races between in-flight
 * requests and a refresh mid-call (the architecture's per-request snapshot
 * capture rule).
 */
fun interface ModelHandleProvider {
    /**
     * Hand back the current snapshot. Implementations must return the same
     * reference for repeated calls within a single refresh cycle.
     */
    fun current(): ModelHandle
}

/** Hands out one fixed handle. Construction-time snapshot; never refreshes. */
class StaticModelHandleProvider(
    private val handle: ModelHandle,
) : ModelHandleProvider {
    override fun current(): ModelHandle = handle
}
