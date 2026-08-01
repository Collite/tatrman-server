// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * BPE token estimator (jtokkit) — the **flagged last resort** for budget usage when neither a streamed
 * `UsageChunk` nor a non-stream `usage` field is available (D-4). Approximation is acceptable by design:
 * a settle built from this is marked `estimated=true`. Unknown models fall back to cl100k_base.
 */
object TokenEstimator {
    private val registry: EncodingRegistry = Encodings.newDefaultEncodingRegistry()
    private val fallback: Encoding = registry.getEncoding(EncodingType.CL100K_BASE)

    /** Rough prompt-token count over the chat `messages` array (per-message framing overhead included). */
    fun estimatePromptTokens(
        messages: JsonArray,
        model: String,
    ): Long {
        val enc = registry.getEncodingForModel(model).orElse(fallback)
        var tokens = 0L
        for (m in messages) {
            val obj = m as? JsonObject ?: continue
            tokens += 4 // OpenAI's rough per-message framing constant (role + delimiters)
            obj["role"]?.jsonPrimitive?.contentOrNull?.let { tokens += enc.countTokens(it) }
            obj["content"]?.let { tokens += enc.countTokens(contentText(it)) }
        }
        return tokens + 3 // reply priming
    }

    private fun contentText(el: JsonElement): String =
        when (el) {
            is JsonPrimitive -> el.contentOrNull ?: ""
            is JsonArray ->
                el
                    .mapNotNull {
                        (it as? JsonObject)
                            ?.get(
                                "text",
                            )?.jsonPrimitive
                            ?.contentOrNull
                    }.joinToString(" ")
            else -> ""
        }
}
