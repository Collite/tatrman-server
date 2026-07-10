package org.tatrman.llmgateway.web

import org.springframework.web.bind.annotation.*
import org.tatrman.llmgateway.model.ModelService
import org.tatrman.llmgateway.observability.ObservabilityService

@RestController
// `/api/v1/chat` is the native path; `/v1/chat` is an alias so the constellation's shared
// LlmGatewayClient (Themis, Golem) — which posts to `/v1/chat/completions` (OpenAI convention) —
// reaches the gateway without a 404. Both resolve to the same handlers.
@RequestMapping("/api/v1/chat", "/v1/chat")
class ChatController(
    private val modelService: ModelService,
    private val observabilityService: ObservabilityService,
) {
    @PostMapping("/completions")
    fun chat(
        @RequestBody request: ChatCompletionRequestApi,
    ): ChatCompletionResponseApi = createResponse(request)

    @PostMapping("/responses")
    fun createResponse(
        @RequestBody request: ChatCompletionRequestApi,
    ): ChatCompletionResponseApi = modelService.processChatRequest(request)

    @PostMapping("/conversations")
    fun createConversation(
        @RequestBody metadata: Map<String, String>?,
    ): ConversationApi =
        ConversationApi(
            id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
            `object` = "conversation",
            createdAt = System.currentTimeMillis() / 1000,
            metadata = metadata,
        )

    @GetMapping("/responses/{id}")
    fun getResponse(
        @PathVariable id: String,
    ): ChatCompletionResponseApi {
        // Dummy implementation
        return ChatCompletionResponseApi(id = id, status = "completed")
    }

    @GetMapping("/conversations/{id}")
    fun getConversation(
        @PathVariable id: String,
    ): ConversationApi {
        // Dummy implementation
        return ConversationApi(
            id = id,
            `object` = "conversation",
            createdAt = System.currentTimeMillis() / 1000,
        )
    }

    @DeleteMapping("/conversations/{id}")
    fun deleteConversation(
        @PathVariable id: String,
    ): DeleteConversationResponseApi =
        DeleteConversationResponseApi(
            id = id,
            `object` = "conversation.deleted",
            deleted = true,
        )
}
