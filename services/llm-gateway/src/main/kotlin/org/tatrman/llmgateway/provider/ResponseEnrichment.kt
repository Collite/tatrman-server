// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.tatrman.llmgateway.config.CatalogModel

/**
 * Injects the gateway `usage` extension block (contracts §1.3) into a passthrough response body: dual
 * usage-field names (migration), `cost` (ported 1.x formula), `estimated`, and top-level `cached`. The
 * REST of the upstream body is carried through byte-stable — only `usage`/`cached` change.
 */
object ResponseEnrichment {
    fun chat(
        result: ProviderResult,
        model: CatalogModel,
    ): JsonObject {
        val prompt = result.usage?.promptTokens ?: 0
        val completion = result.usage?.completionTokens ?: 0
        val usage =
            buildJsonObject {
                (result.body["usage"] as? JsonObject)?.forEach { (k, v) -> put(k, v) } // keep upstream fields
                put("prompt_tokens", prompt)
                put("completion_tokens", completion)
                put("total_tokens", prompt + completion)
                put("input_tokens", prompt) // dual, migration (A-3)
                put("output_tokens", completion)
                put("cost", computeCost(model, prompt, completion))
                put("estimated", result.usage == null)
            }
        return JsonObject(
            result.body.toMutableMap().apply {
                put("usage", usage)
                put("cached", JsonPrimitive(false))
            },
        )
    }

    fun embeddings(
        result: ProviderResult,
        model: CatalogModel,
    ): JsonObject {
        val input =
            result.usage?.promptTokens
                ?: (result.body["usage"] as? JsonObject)?.get("prompt_tokens")?.jsonPrimitive?.longOrNull
                ?: 0
        val usage =
            buildJsonObject {
                (result.body["usage"] as? JsonObject)?.forEach { (k, v) -> put(k, v) }
                put("prompt_tokens", input)
                put("total_tokens", input)
                put("input_tokens", input)
                put("cost", (input * (model.pricing?.input ?: 0.0)) / 1_000_000.0) // input-only cost (B-T5)
                put("estimated", false)
            }
        return JsonObject(
            result.body.toMutableMap().apply {
                put("usage", usage)
                put("cached", JsonPrimitive(false))
            },
        )
    }

    /**
     * The streaming counterpart of [chat]: rewrite the final `usage` chunk of an SSE stream with the
     * §1.3 extension (dual usage names + `cost` + `estimated`) and top-level `cached:false`. Upstream
     * usage fields (incl. `prompt_tokens_details.cached_tokens`) are preserved; only the extension is
     * added. This is the ONE frame the passthrough writer rewrites — every other frame is byte-stable.
     */
    fun streamingUsageChunk(
        chunk: JsonObject,
        model: CatalogModel,
    ): JsonObject {
        val upstreamUsage = chunk["usage"] as? JsonObject ?: return chunk
        val prompt = upstreamUsage["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: 0
        val completion = upstreamUsage["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0
        val usage =
            buildJsonObject {
                upstreamUsage.forEach { (k, v) -> put(k, v) } // keep upstream fields (incl. prompt_tokens_details)
                put("input_tokens", prompt) // dual, migration (A-3)
                put("output_tokens", completion)
                put("cost", computeCost(model, prompt, completion))
                put("estimated", false)
            }
        return JsonObject(
            chunk.toMutableMap().apply {
                put("usage", usage)
                put("cached", JsonPrimitive(false))
            },
        )
    }

    /** Ported from 1.x `ModelService.computeCost`: (in×inputCost + out×outputCost)/1e6, USD per 1M tokens. */
    fun computeCost(
        model: CatalogModel,
        promptTokens: Long,
        completionTokens: Long,
    ): Double {
        val pricing = model.pricing ?: return 0.0
        val output = pricing.output ?: pricing.input
        return (promptTokens * pricing.input + completionTokens * output) / 1_000_000.0
    }
}
