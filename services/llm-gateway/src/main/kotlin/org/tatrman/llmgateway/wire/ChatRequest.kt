// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The **B-T2** parsed request model (design.md §3.1, contracts.md §5.1), realized per **LG-D1**
 * (spike Approach B): the parsed [JsonObject] is the source of truth and is never lowered to native
 * Kotlin types, so every field — typed, unmodeled, nested, any number literal, explicit `null` vs
 * absent — forwards byte-faithfully. Typed fields are a read-only view for routing/governance/logging;
 * the only pre-forward mutation is the resolved-model substitution ([withModel]), done copy-with-patch.
 *
 * Proven by `UnknownFieldRoundTripSpec` (the graduated spike corpus) — the regression suite for this
 * invariant. See control room §6 / plan.md LG-D1 for why the typed-`data class` alternative was rejected.
 */
class ChatRequest private constructor(
    private val raw: JsonObject,
) {
    // ── typed read-only view (contracts §1.2 minimum set; extend as engine stages need) ──
    val model: String? get() = raw["model"]?.jsonPrimitive?.contentOrNull
    val stream: Boolean get() = raw["stream"]?.jsonPrimitive?.booleanOrNull ?: false
    val messages: JsonArray get() = raw["messages"] as? JsonArray ?: EMPTY

    /**
     * `model_tags` — a gateway-only tier-routing hint (contracts §1.2, 1.x carry-over). Read for
     * tag-tier resolution (C-1 tier 3) and **stripped before the request reaches an upstream**
     * ([toUpstreamJson]) — it is never a provider-visible field.
     */
    val modelTags: List<String>
        get() = (raw["model_tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    /**
     * The request as it must be forwarded upstream: byte-faithful to the parsed body, minus the
     * gateway-only `model_tags` hint. Everything else — typed and unmodeled — is carried verbatim.
     */
    fun toUpstreamJson(): JsonObject =
        if (raw.containsKey("model_tags")) {
            JsonObject(raw.toMutableMap().apply { remove("model_tags") })
        } else {
            raw
        }

    /** Copy-with-patch: substitute the resolved upstream model name (C-1); nothing else changes. */
    fun withModel(resolvedUpstreamModel: String): ChatRequest =
        ChatRequest(JsonObject(raw.toMutableMap().apply { put("model", JsonPrimitive(resolvedUpstreamModel)) }))

    /**
     * The parsed body as the read-only source of truth — for converters that must read arbitrary fields
     * (the Anthropic converter walks messages/tools/tool_choice, LG-P2·S3). Never mutate the result.
     */
    fun asJsonObject(): JsonObject = raw

    companion object {
        private val EMPTY = JsonArray(emptyList())

        /**
         * Parse an OpenAI chat-completions request body. Throws [kotlinx.serialization.SerializationException]
         * on non-JSON or a non-object root — the transport maps that to a 400 `invalid_request_error`.
         */
        fun parse(body: String): ChatRequest = ChatRequest(Json.parseToJsonElement(body).jsonObject)
    }
}
