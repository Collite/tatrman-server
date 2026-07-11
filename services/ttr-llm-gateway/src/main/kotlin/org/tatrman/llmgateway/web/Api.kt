package org.tatrman.llmgateway.web

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage

// Simple DTOs to mirror OpenAI structure roughly
@Serializable
data class ChatCompletionRequestApi(
    val model: String? = null,
    val messages: List<ChatMessageApi>? = null,
    val conversation: String? = null,
    val input: ResponseInputApi? = null,
    val instructions: String? = null,
    val background: String? = null,
    val temperature: Float? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    @SerialName("max_tools_calls") val maxToolsCalls: Int? = null,
    val reasoning: ReasoningConfigApi? = null,
    val tools: List<ToolApi>? = null,
    @SerialName("model_tags") val modelTags: List<String>? = null,
    @SerialName("response_format") val responseFormat: ResponseFormatApi? = null,
)

@Serializable
data class ResponseFormatApi(
    val type: String,
    @SerialName("json_schema") val jsonSchema: JsonSchemaApi? = null,
)

@Serializable
data class JsonSchemaApi(
    val name: String,
    val description: String? = null,
    val schema: JsonObject? = null,
    val strict: Boolean? = null,
)

@Serializable
data class ResponseInputApi(
    val text: String? = null,
)

@Serializable
data class ReasoningConfigApi(
    val effort: String? = null,
)

@Serializable
data class ToolApi(
    val type: String? = null,
    val function: FunctionApi? = null,
)

@Serializable
data class FunctionApi(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null,
    val strict: Boolean? = null,
)

@Serializable
data class ChatMessageApi(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallApi>? = null,
)

@Serializable
data class ChoiceApi(
    val index: Int,
    val message: ChatMessageApi,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatCompletionResponseApi(
    val id: String? = null,
    val `object`: String? = null,
    val created: Long? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    val model: String? = null,
    val status: String? = null,
    val output: List<ResponseOutputApi>? = null,
    val choices: List<ChoiceApi>? = null,
    val usage: UsageApi? = null,
    val reasoning: ResponseReasoningApi? = null,
    val content: String? = null,
    // Phase 09 A3 / DF-X — `cached` is true when the response was served from the Redis cache
    // instead of the live provider. Pythia's Budget Tracker reads this together with
    // `usage.cost` to attribute spend correctly.
    val cached: Boolean? = null,
)

@Serializable
data class ResponseOutputApi(
    val type: String,
    val text: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallApi>? = null,
)

@Serializable
data class ToolCallApi(
    val id: String,
    val type: String,
    val function: FunctionCallApi,
)

@Serializable
data class FunctionCallApi(
    val name: String,
    val arguments: String,
)

@Serializable
data class UsageApi(
    @SerialName("total_tokens") val totalTokens: Int,
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
    /**
     * Phase 09 A3 — request cost in USD. Computed from the model's per-million-token pricing:
     * `(inputTokens × inputCost + outputTokens × outputCost) / 1_000_000`. `null` when the
     * model has no price configured (legacy entries, embeddings before pricing was wired).
     */
    val cost: Double? = null,
)

@Serializable
data class ResponseReasoningApi(
    val summary: String? = null,
)

@Serializable
data class ConversationApi(
    val id: String,
    val `object`: String,
    @SerialName("created_at") val createdAt: Long,
    val metadata: Map<String, String>? = null,
)

@Serializable
data class DeleteConversationResponseApi(
    val id: String,
    val `object`: String,
    val deleted: Boolean,
)

// ----- Phase 09 A3 / DF-X — Embeddings API ------------------------------------------------
//
// Mirrors OpenAI's /v1/embeddings shape so existing clients work unchanged. `input` is a single
// string OR a list of strings; the response carries one [EmbeddingItemApi] per input. `cost` is
// computed by the service from the model's `inputCost` (embeddings have no output tokens).

@Serializable
data class EmbeddingRequestApi(
    val model: String? = null,
    val input: EmbeddingInputApi,
    @SerialName("encoding_format") val encodingFormat: String? = null,
    val dimensions: Int? = null,
    val user: String? = null,
)

/**
 * Polymorphic-shaped input: a single string OR a list of strings, like OpenAI's API. Modeled as
 * two nullable fields plus a small construction helper so kotlinx.serialization handles either
 * shape. (A custom serializer would be tidier but adds a 30-line surface for a niche case;
 * the caller's wire shape is unchanged.)
 */
@Serializable
data class EmbeddingInputApi(
    val text: String? = null,
    val texts: List<String>? = null,
) {
    fun asList(): List<String> = texts ?: text?.let { listOf(it) } ?: emptyList()
}

@Serializable
data class EmbeddingResponseApi(
    val `object`: String = "list",
    val data: List<EmbeddingItemApi>,
    val model: String,
    val usage: UsageApi,
    val cached: Boolean? = false,
)

@Serializable
data class EmbeddingItemApi(
    val `object`: String = "embedding",
    val index: Int,
    val embedding: List<Float>,
)

fun toSpringAiMessage(chatMessage: ChatMessageApi): Message {
    // TODO check and sanitize ! prompt injection sanitizer

    return when (chatMessage.role.lowercase()) {
        "user" -> UserMessage(chatMessage.content)
        "system" -> SystemMessage(chatMessage.content)
        "assistant" -> {
            val builder = AssistantMessage.builder()
            chatMessage.content?.let { builder.content(it) }
            chatMessage.toolCalls?.let { builder.toolCalls(toToolCalls(it)) }
            builder.build()
        }
        else -> UserMessage(chatMessage.content) // Fallback
    }
}

fun toToolCalls(toolCalls: List<ToolCallApi>): List<AssistantMessage.ToolCall> = toolCalls.map { toToolCall(it) }

fun toToolCall(toolCall: ToolCallApi): AssistantMessage.ToolCall =
    AssistantMessage.ToolCall(
        toolCall.id,
        toolCall.type,
        toolCall.function.name,
        toolCall.function.arguments,
    )
