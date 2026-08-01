// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The cached-response envelope (contracts §4). Holds the enriched **non-stream** client body (its `cached`
 * flag is re-applied on read, stripped-as-false on store), the ORIGINAL serving provider/model (attribution
 * truth on a hit, P-2), the usage counters + saved cost (echoed on a hit so Pythia still books it, GI-3),
 * and a store timestamp. Entry format is new to 2.0 → Redis flush at cutover (G-1).
 */
@Serializable
data class CacheEnvelope(
    val body: JsonObject,
    val servedProvider: String,
    val servedModel: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val costUsd: Double,
    val storedAtMs: Long,
)
