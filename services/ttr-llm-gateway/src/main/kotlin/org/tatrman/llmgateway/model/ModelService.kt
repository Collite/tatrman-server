package org.tatrman.llmgateway.model

import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.tatrman.llmgateway.model.rules.RequestMetadata
import org.tatrman.llmgateway.model.rules.RuleEngine
import org.tatrman.llmgateway.observability.ObservabilityService
import org.tatrman.llmgateway.observability.PromptLog
import org.tatrman.llmgateway.web.ChatCompletionRequestApi
import org.tatrman.llmgateway.web.ChatCompletionResponseApi
import org.tatrman.llmgateway.web.ChatMessageApi
import org.tatrman.llmgateway.web.ChoiceApi
import org.tatrman.llmgateway.web.ResponseOutputApi
import org.tatrman.llmgateway.web.UsageApi
import org.tatrman.llmgateway.web.toSpringAiMessage
import java.lang.reflect.Type

// import org.springframework.ai.google.genai.GoogleGenAiChatOptions
// import org.springframework.ai.ollama.api.OllamaChatOptions

enum class AiProvider {
    OLLAMA,
    ANTHROPIC,
    GEMINI,
    AZURE,
}

@Service
class ModelService(
    private val modelRepository: ModelRepository,
    private val ruleEngine: RuleEngine,
    private val observabilityService: ObservabilityService,
    @Qualifier("azureOpenAiChatModel") azureModel: ChatModel,
    @Qualifier("anthropicChatModel") anthropicModel: ChatModel,
    // Phase 09 A3 — Redis-backed response cache. Optional dep: the bean is conditional on
    // `llm.cache.enabled=true`; in profiles without Redis configured this is null and the
    // service degrades to always-live calls (no behavioural change vs pre-A3).
    private val chatResponseCache: org.tatrman.llmgateway.cache.ChatResponseCache? = null,
    //    @Qualifier("ollamaChatModel") ollamaModel: ChatModel,
    //    @Qualifier("googleGenAiChatModel") geminiModel: ChatModel,
) {
    private val logger = LoggerFactory.getLogger(ModelService::class.java)

    private val clientMap =
        mapOf(
            AiProvider.AZURE to ChatClient.create(azureModel),
            AiProvider.ANTHROPIC to ChatClient.create(anthropicModel),
            //            AiProvider.OLLAMA to ChatClient.create(ollamaModel),
            //            AiProvider.GEMINI to ChatClient.create(geminiModel),
        )

    fun findAll(): Iterable<Model> = modelRepository.findAll()

    // TODO config this
    val defaultModel: Model = modelRepository.findByName("gpt-4.1")!!

    fun processChatRequest(request: ChatCompletionRequestApi): ChatCompletionResponseApi {
        logger.debug("Incoming chat request: {}", request)

        // Phase 09 A3 — cache lookup. On hit, override `cached = true` so consumers see the
        // attribution; preserves the original `usage.cost` so Pythia still attributes spend
        // to the cached-call's saved bill correctly.
        chatResponseCache?.lookup(request)?.let { hit ->
            logger.info("Chat request served from cache (model={})", hit.model)
            return hit.copy(cached = true)
        }

        val (model, client, options) = selectModelForChatRequest(request)
        logger.debug("Selected model: {} with options: {}", model.name, options)

        val messages =
            request.messages?.map { toSpringAiMessage(it) }
                ?: request.input?.text?.let { listOf(UserMessage(it)) } ?: emptyList()

        val prompt = Prompt(messages)

        var responseContent = ""
        var status = "SUCCESS"
        var promptTokens = 0
        var completionTokens = 0

        val start = System.currentTimeMillis()

        try {
            logger.info(
                "Calling LLM API: model={} provider={} options={} messages size={} messages: {}",
                model.name,
                model.provider,
                options,
                messages.size,
                messages,
            )
            // Call the appropriate client with the appropriate options
            val chatResponse =
                client
                    .prompt(prompt)
                    .options(options)
                    .call()
                    .chatResponse()

            logger.info("Received chat response: {}", chatResponse)

            responseContent = chatResponse?.result?.output?.text ?: ""

            // extract actual token counts from chatResponse
            promptTokens = chatResponse?.metadata?.usage?.promptTokens ?: 0
            completionTokens = chatResponse?.metadata?.usage?.completionTokens ?: 0

            val assistantMsg = chatResponse?.result?.output
            logger.info("Assistant message: {}", assistantMsg)

            val toolCallsApi =
                if (assistantMsg is org.springframework.ai.chat.messages.AssistantMessage &&
                    assistantMsg.hasToolCalls()
                ) {
                    assistantMsg.toolCalls.map { tc ->
                        org.tatrman.llmgateway.web.ToolCallApi(
                            id = tc.id(),
                            type = tc.type(),
                            function =
                                org.tatrman.llmgateway.web.FunctionCallApi(
                                    name = tc.name(),
                                    arguments = tc.arguments(),
                                ),
                        )
                    }
                } else {
                    null
                }

            logger.info("Tool calls: {}", toolCallsApi)

            val outputType = if (!toolCallsApi.isNullOrEmpty()) "tool_calls" else "text"
            val finishReason = if (!toolCallsApi.isNullOrEmpty()) "tool_calls" else "stop"

            val response =
                ChatCompletionResponseApi(
                    id =
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    `object` = "chat.completion",
                    created = System.currentTimeMillis() / 1000,
                    createdAt = System.currentTimeMillis() / 1000,
                    model = model.name,
                    status = "completed",
                    output =
                        listOf(
                            ResponseOutputApi(
                                type = outputType,
                                text = responseContent,
                                toolCalls = toolCallsApi,
                            ),
                        ),
                    choices =
                        listOf(
                            ChoiceApi(
                                index = 0,
                                message =
                                    ChatMessageApi(
                                        role = "assistant",
                                        content = responseContent,
                                        toolCalls = toolCallsApi,
                                    ),
                                finishReason = finishReason,
                            ),
                        ),
                    usage =
                        UsageApi(
                            totalTokens = promptTokens + completionTokens,
                            inputTokens = promptTokens,
                            outputTokens = completionTokens,
                            cost = computeCost(model, promptTokens, completionTokens),
                        ),
                    content = responseContent,
                    // Phase 09 A3 — explicitly mark a live (non-cached) response so the field is
                    // present on the wire and Pythia doesn't have to infer cache state from the
                    // absence of the field. The Redis-backed cache layer (DF-A3-CACHE) overrides
                    // this to `true` when it serves from the cache.
                    cached = false,
                )
            // Phase 09 A3 — store on success (best-effort; logged-only on Redis failure).
            chatResponseCache?.store(request, response)
            logger.info("Generated artifact: {}", response)
            return response
        } catch (e: Exception) {
            status = "ERROR"
            responseContent = e.message ?: "Unknown Error"
            logger.error("Failed to process chat request for model={}", model.name, e)
            throw e
        } finally {
            val duration = System.currentTimeMillis() - start
            observabilityService.recordInteraction(
                PromptLog(
                    userId = "user-placeholder", // Extract from SecurityContext
                    modelName = model.name,
                    provider = model.provider,
                    promptText = prompt.contents,
                    responseText = responseContent,
                    tokensPrompt = promptTokens,
                    tokensCompletion = completionTokens,
                    durationMs = duration,
                    status = status,
                ),
            )
        }
    }

    /**
     * Phase 09 A3 — request cost in USD. Pricing is per million tokens (industry-standard
     * convention; matches how Azure / Anthropic / OpenAI publish rates). Falls back to the
     * legacy single-value `model.cost` when the explicit input/output split isn't set;
     * returns `null` when no pricing is configured at all so consumers (Pythia) can
     * distinguish "free model" from "unpriced" if they care.
     */
    fun computeCost(
        model: Model,
        promptTokens: Int,
        completionTokens: Int,
    ): Double? {
        val input = model.inputCost ?: model.cost
        val output = model.outputCost ?: model.cost
        if (input == null && output == null) return null
        val perToken = 1_000_000.0
        val inputCost = (input ?: 0.0) * promptTokens / perToken
        val outputCost = (output ?: 0.0) * completionTokens / perToken
        return inputCost + outputCost
    }

    fun selectModelForChatRequest(
        request: ChatCompletionRequestApi,
        user: String = "",
    ): Triple<Model, ChatClient, ChatOptions> {
        var model = request.model?.let { modelRepository.findByName(it) }
        if (model == null) {
            val allModels = modelRepository.findType("chat")
            val metadata =
                RequestMetadata(
                    modelName = request.model,
                    requiredTags = request.modelTags ?: emptyList(),
                    user = user,
                )
            model = ruleEngine.selectModel(metadata, allModels)
            logger.debug("Rule engine selected model: {} for metadata: {}", model?.name, metadata)
        }
        if (model == null) {
            model = defaultModel
            logger.debug("Using default model: {}", model.name)
        }

        val provider = AiProvider.valueOf(model.provider.uppercase())

        val client =
            clientMap[provider]
                ?: throw IllegalArgumentException(
                    "Unknown provider: ${model.provider}",
                )
        val name = model.fullName ?: model.name

        // 1. Create provider-specific options with the requested model name
        val chatOptions: ChatOptions? =
            when (provider) {
                AiProvider.AZURE -> {
                    val builder = AzureOpenAiChatOptions.builder().deploymentName(name)

                    request.responseFormat?.type?.let { type ->
                        if (type == "json_object") {
                            val format =
                                org.springframework.ai.azure.openai
                                    .AzureOpenAiResponseFormat()
                            format.type =
                                org.springframework.ai.azure.openai
                                    .AzureOpenAiResponseFormat.Type.JSON_OBJECT
                            builder.responseFormat(format)
                        } else if (type == "json_schema" &&
                            request.responseFormat.jsonSchema != null
                        ) {
                            val jsApi = request.responseFormat.jsonSchema
                            val schemaBuilder =
                                org.springframework.ai.azure.openai
                                    .AzureOpenAiResponseFormat.JsonSchema
                                    .builder()
                            schemaBuilder.name(jsApi.name)
                            if (jsApi.description != null) {
                                // description builder if available, but apparently builder()
                                // doesn't have it natively in older versions. Let's check?
                                // builder() might just have name, schema, strict. Wait!
                                // AzureOpenAiResponseFormat$JsonSchema$Builder has description?
                                // The javap output earlier said: `public java.lang.String
                                // getName()`, `public java.util.Map<java.lang.String,
                                // java.lang.Object> getSchema()`, `public java.lang.Boolean
                                // getStrict()`. It does NOT have description.
                            }
                            if (jsApi.strict != null) schemaBuilder.strict(jsApi.strict)
                            if (jsApi.schema != null) {
                                val anySchema = jsonElementToAny(jsApi.schema)
                                if (anySchema is Map<*, *>) {
                                    @Suppress("UNCHECKED_CAST")
                                    schemaBuilder.schema(anySchema as Map<String, Any>)
                                }
                            }

                            val format =
                                org.springframework.ai.azure.openai
                                    .AzureOpenAiResponseFormat()
                            format.type =
                                org.springframework.ai.azure.openai
                                    .AzureOpenAiResponseFormat.Type.JSON_SCHEMA
                            format.jsonSchema = schemaBuilder.build()
                            builder.responseFormat(format)
                        }
                    }

                    if (!request.tools.isNullOrEmpty()) {
                        logger.debug("Using function tools: {}", request.tools)
                        val callbacks =
                            request.tools.mapNotNull { tool ->
                                if (tool.type == "function" && tool.function != null) {
                                    val f = tool.function
                                    val jsonStr =
                                        f.parameters?.let {
                                            kotlinx.serialization.json.Json
                                                .encodeToString(it)
                                        }
                                            ?: "{}"
                                    val inputType = String::class.java as Type
                                    logger.debug(
                                        "Function tool: {}, params {}, type {}",
                                        f,
                                        jsonStr,
                                        inputType,
                                    )
                                    org.springframework.ai.tool.function
                                        .FunctionToolCallback
                                        .builder<String, String>(
                                            f.name,
                                        ) { _ -> "" }
                                        .description(f.description ?: "")
                                        .inputSchema(jsonStr)
                                        .inputType(inputType)
                                        .build()
                                } else {
                                    null
                                }
                            }
                        if (callbacks.isNotEmpty()) {
                            builder.toolCallbacks(callbacks)
                            builder.internalToolExecutionEnabled(false)
                        }
                    }

                    builder.build()
                }

                AiProvider.ANTHROPIC ->
                    AnthropicChatOptions
                        .builder()
                        .model(name)
                        .build()

                //                AiProvider.GEMINI ->
                //                    GoogleGenAiChatOptions
                //                        .builder()
                //                        .model(name) // e.g.
                // "gemini-1.5-pro-preview-0409"
                //                        .build()
                //
                //                AiProvider.OLLAMA ->
                //                    OllamaChatOptions
                //                        .builder()
                //                        .model(name) // e.g. "llama3" or "mistral"
                //                        .build()
                else -> null
                // TODO change when changing the list of the models
            }

        // todo log selection

        // TODO see above; remove this when ready
        return Triple(model, client, chatOptions!!)
    }
}
