// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.cache

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.llmgateway.auth.sha256Hex
import org.tatrman.llmgateway.wire.ChatRequest

/**
 * The exact-match cache key (contracts §4, E-1). SHA-256 of a **canonical** JSON projection of the B-T2
 * request with:
 *  - volatile fields excluded ([VOLATILE]) — `stream`/`stream_options`/`user`/`metadata`/`model_tags` and,
 *    by nature, the attribution headers (they never enter the body), so shape-only differences share a key;
 *  - the **logical (resolved) model name** substituted for `model`, so `haiku` and `claude-haiku-4-5` that
 *    resolve to the same catalog entry — and a fallback-served response — cache under the caller's name.
 *
 * Canonicalization sorts object keys recursively so key order is non-semantic (RFC 8259 §4). The projection
 * shape is pinned by `CacheKeySpec` exactly as the 1.x `ChatResponseCache.keyFor` was.
 */
object CacheKey {
    private val VOLATILE = setOf("stream", "stream_options", "user", "metadata", "model_tags")

    fun of(
        prefix: String,
        logicalModel: String,
        req: ChatRequest,
    ): String {
        val projected =
            buildMap {
                req.asJsonObject().forEach { (k, v) -> if (k !in VOLATILE) put(k, v) }
                put("model", JsonPrimitive(logicalModel)) // logical name — fallback-served caches under caller name
            }
        return prefix + sha256Hex(canonical(JsonObject(projected)))
    }

    private fun canonical(el: JsonElement): String =
        when (el) {
            is JsonObject ->
                el.entries
                    .sortedBy { it.key }
                    .joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonical(it.value)}" }
            is JsonArray -> el.joinToString(",", "[", "]") { canonical(it) }
            is JsonPrimitive -> el.toString() // strings keep their quotes; numbers/bools/null render literally
        }
}
