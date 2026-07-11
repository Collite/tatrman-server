package org.tatrman.llmgateway.grpc

import io.grpc.stub.StreamObserver
import org.springframework.stereotype.Service
import org.tatrman.llm.v1.*
import org.tatrman.llmgateway.web.*

@Service
class GrpcChatService(
    private val chatController: ChatController,
) : LlmGatewayServiceGrpc.LlmGatewayServiceImplBase() {
    override fun createResponse(
        request: CreateResponseRequest,
        responseObserver: StreamObserver<Response>,
    ) {
        try {
            val dto =
                ChatCompletionRequestApi(
                    conversation = request.conversation,
                    model = request.model,
                    input =
                        if (request.hasInput()) {
                            ResponseInputApi(text = request.input.text)
                        } else {
                            null
                        },
                    instructions = request.instructions,
                    background = request.background,
                    temperature =
                        if (request.hasTemperature()) request.temperature else null,
                    maxOutputTokens =
                        if (request.hasMaxOutputTokens()) {
                            request.maxOutputTokens
                        } else {
                            null
                        },
                    maxToolsCalls =
                        if (request.hasMaxToolsCalls()) request.maxToolsCalls else null,
                    responseFormat =
                        if (request.hasResponseFormat()) {
                            val jsonSchemaOpt =
                                if (request.responseFormat.hasJsonSchema()) {
                                    val js = request.responseFormat.jsonSchema
                                    val schemaObj =
                                        if (js.hasSchema()) {
                                            structToJsonObject(js.schema)
                                        } else {
                                            null
                                        }
                                    JsonSchemaApi(
                                        name = js.name,
                                        description =
                                            if (js.hasDescription()) {
                                                js.description
                                            } else {
                                                null
                                            },
                                        schema = schemaObj,
                                        strict =
                                            if (js.hasStrict()) {
                                                js.strict
                                            } else {
                                                null
                                            },
                                    )
                                } else {
                                    null
                                }
                            ResponseFormatApi(
                                type = request.responseFormat.type,
                                jsonSchema = jsonSchemaOpt,
                            )
                        } else {
                            null
                        },
                    tools =
                        if (request.toolsCount > 0) {
                            request.toolsList.map { toolProto ->
                                ToolApi(
                                    type = toolProto.type.name.lowercase(),
                                    function =
                                        if (toolProto.hasFunction()) {
                                            val f = toolProto.function
                                            FunctionApi(
                                                name = f.name,
                                                description =
                                                    if (f.hasDescription()
                                                    ) {
                                                        f.description
                                                    } else {
                                                        null
                                                    },
                                                parameters =
                                                    if (f.hasParameters()
                                                    ) {
                                                        structToJsonObject(
                                                            f.parameters,
                                                        )
                                                    } else {
                                                        null
                                                    },
                                                strict =
                                                    if (f.hasStrict()) {
                                                        f.strict
                                                    } else {
                                                        null
                                                    },
                                            )
                                        } else {
                                            null
                                        },
                                )
                            }
                        } else {
                            null
                        },
                )

            val result = chatController.createResponse(dto)

            val responseBuilder =
                Response
                    .newBuilder()
                    .setId(result.id ?: "")
                    .setObject(result.`object` ?: "response")
                    .setCreatedAt(result.createdAt ?: 0L)
                    .setConversationId(result.conversationId ?: "")
                    .setModel(result.model ?: "")
                    .setStatus(result.status ?: "")

            result.output?.forEach { out ->
                val outBuilder =
                    ResponseOutput.newBuilder().setType(out.type).setText(out.text ?: "")

                if (!out.toolCalls.isNullOrEmpty()) {
                    val protoToolCalls =
                        out.toolCalls.map { tc ->
                            org.tatrman.llm.v1.ToolCall
                                .newBuilder()
                                .setId(tc.id)
                                .setType(tc.type)
                                .setFunction(
                                    org.tatrman.llm.v1.FunctionCall
                                        .newBuilder()
                                        .setName(tc.function.name)
                                        .setArguments(tc.function.arguments)
                                        .build(),
                                ).build()
                        }
                    outBuilder.addAllToolCalls(protoToolCalls)
                }

                responseBuilder.addOutput(outBuilder.build())
            }

            if (result.usage != null) {
                responseBuilder.setUsage(
                    Usage
                        .newBuilder()
                        .setTotalTokens(result.usage.totalTokens)
                        .setInputTokens(result.usage.inputTokens)
                        .setOutputTokens(result.usage.outputTokens)
                        .build(),
                )
            }

            responseObserver.onNext(responseBuilder.build())
            responseObserver.onCompleted()
        } catch (e: Exception) {
            responseObserver.onError(e)
        }
    }

    override fun getResponse(
        request: GetResponseRequest,
        responseObserver: StreamObserver<Response>,
    ) {
        try {
            val result = chatController.getResponse(request.responseId)
            val response =
                Response
                    .newBuilder()
                    .setId(result.id ?: "")
                    .setStatus(result.status ?: "")
                    .build()
            responseObserver.onNext(response)
            responseObserver.onCompleted()
        } catch (e: Exception) {
            responseObserver.onError(e)
        }
    }

    override fun createConversation(
        request: CreateConversationRequest,
        responseObserver: StreamObserver<Conversation>,
    ) {
        try {
            // metadata mapping could be complex with Struct, using empty map for now
            val result = chatController.createConversation(null)
            val conversation =
                Conversation
                    .newBuilder()
                    .setId(result.id)
                    .setObject(result.`object`)
                    .setCreatedAt(result.createdAt)
                    .build()
            responseObserver.onNext(conversation)
            responseObserver.onCompleted()
        } catch (e: Exception) {
            responseObserver.onError(e)
        }
    }

    override fun getConversation(
        request: GetConversationRequest,
        responseObserver: StreamObserver<Conversation>,
    ) {
        try {
            val result = chatController.getConversation(request.conversationId)
            val conversation =
                Conversation
                    .newBuilder()
                    .setId(result.id)
                    .setObject(result.`object`)
                    .setCreatedAt(result.createdAt)
                    .build()
            responseObserver.onNext(conversation)
            responseObserver.onCompleted()
        } catch (e: Exception) {
            responseObserver.onError(e)
        }
    }

    override fun deleteConversation(
        request: DeleteConversationRequest,
        responseObserver: StreamObserver<DeleteConversationResponse>,
    ) {
        try {
            val result = chatController.deleteConversation(request.conversationId)
            val response =
                DeleteConversationResponse
                    .newBuilder()
                    .setId(result.id)
                    .setObject(result.`object`)
                    .setDeleted(result.deleted)
                    .build()
            responseObserver.onNext(response)
            responseObserver.onCompleted()
        } catch (e: Exception) {
            responseObserver.onError(e)
        }
    }

    override fun chat(
        request: ChatRequest,
        responseObserver: StreamObserver<ChatResponse>,
    ) {
        try {
            // Convert Proto -> DTO
            val dto =
                ChatCompletionRequestApi(
                    model = request.model,
                    messages =
                        request.messagesList.map {
                            ChatMessageApi(it.role, it.content)
                        },
                )

            // Call Logic
            val result = chatController.chat(dto)

            // Convert DTO -> Proto
            val response =
                ChatResponse
                    .newBuilder()
                    .setContent(result.content ?: "")
                    .setModel(request.model) // Fuzzy back
                    .build()

            responseObserver.onNext(response)
            responseObserver.onCompleted()
        } catch (e: Exception) {
            responseObserver.onError(e)
        }
    }

    private fun structToJsonObject(struct: com.google.protobuf.Struct): kotlinx.serialization.json.JsonObject {
        val map = struct.fieldsMap.mapValues { (_, value) -> protoValueToJsonElement(value) }
        return kotlinx.serialization.json.JsonObject(map)
    }

    private fun protoValueToJsonElement(value: com.google.protobuf.Value): kotlinx.serialization.json.JsonElement =
        when (value.kindCase) {
            com.google.protobuf.Value.KindCase.NULL_VALUE -> kotlinx.serialization.json.JsonNull
            com.google.protobuf.Value.KindCase.NUMBER_VALUE ->
                kotlinx.serialization.json.JsonPrimitive(value.numberValue)
            com.google.protobuf.Value.KindCase.STRING_VALUE ->
                kotlinx.serialization.json.JsonPrimitive(value.stringValue)
            com.google.protobuf.Value.KindCase.BOOL_VALUE ->
                kotlinx.serialization.json.JsonPrimitive(value.boolValue)
            com.google.protobuf.Value.KindCase.STRUCT_VALUE ->
                structToJsonObject(value.structValue)
            com.google.protobuf.Value.KindCase.LIST_VALUE -> {
                val list = value.listValue.valuesList.map { protoValueToJsonElement(it) }
                kotlinx.serialization.json.JsonArray(list)
            }
            else -> kotlinx.serialization.json.JsonNull
        }
}
