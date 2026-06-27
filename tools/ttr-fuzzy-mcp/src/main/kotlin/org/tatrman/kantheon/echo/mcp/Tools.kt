package org.tatrman.kantheon.echo.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.fuzzy.common.FuzzyMatchResponse
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.echo.mcp.client.EchoClient
import org.tatrman.kantheon.echo.mcp.telemetry.EchoMcpTelemetry

class Tools(
    private val client: EchoClient?,
    private val telemetry: EchoMcpTelemetry,
) {
    private val logger = LoggerFactory.getLogger(Tools::class.java)

    private fun notWiredResult(): CallToolResult =
        CallToolResult(
            isError = true,
            content = listOf(TextContent(text = "echo not wired: gRPC client disabled (echo.client.host is blank)")),
        )

    private fun resultStructured(result: FuzzyMatchResponse) = Json.encodeToJsonElement(result).jsonObject

    private fun formatTextResponse(response: FuzzyMatchResponse): String =
        if (response.isError) {
            "Error: ${response.error}"
        } else if (response.matches.isEmpty()) {
            "No matches found."
        } else {
            response.matches.joinToString("\n") {
                "- Match ${it.candidateId} [${it.category}]: ${it.candidate} (score: ${it.score})"
            }
        }

    val matchTool =
        Tool(
            name = "match",
            description = "Find echo matches for a given name and category",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("category") {
                                put("type", "string")
                                put(
                                    "description",
                                    "The category of the entity to match. E.g., 'customer' for matching customer names, 'product' to match product names. Optional, if omitted, searches in all categories.",
                                )
                            }
                            putJsonObject("name") {
                                put("type", "string")
                                put(
                                    "description",
                                    "The name of the entity to be found (the string searched for).",
                                )
                            }
                            putJsonObject("algorithm") {
                                put("type", "string")
                                put(
                                    "description",
                                    "The name of the algorithm to use for the matching. Default is tatrman.",
                                )
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "The maximum number of candidates to be returned. Default is 10.",
                                )
                            }
                        },
                    required =
                        listOf(
                            "name",
                        ),
                ),
            outputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("matches") {
                                put("type", "array")
                                put("description", "List of echo match candidates")
                                put(
                                    "items",
                                    buildJsonObject {
                                        putJsonObject("candidate_id") { put("type", "string") }
                                        putJsonObject("candidate") { put("type", "string") }
                                        putJsonObject("score") { put("type", "number") }
                                        putJsonObject("category") { put("type", "string") }
                                    },
                                )
                            }
                        },
                    required = listOf("matches"),
                ),
        )

    suspend fun matchCallback(request: CallToolRequest): CallToolResult {
        if (client == null) return notWiredResult()
        val name =
            request.arguments
                ?.get("name")
                ?.jsonPrimitive
                ?.content

        if (name.isNullOrBlank()) {
            val result =
                CallToolResult(
                    isError = true,
                    content = listOf(TextContent(text = "Missing required argument: name")),
                )
            logger.info("match completed | missing name | isError={}", result.isError)
            logger.debug("match missing name result: {}", result)
            return result
        }

        val algorithm =
            request.arguments
                ?.get("algorithm")
                ?.jsonPrimitive
                ?.content ?: "TATRMAN"
        val category =
            request.arguments
                ?.get("category")
                ?.jsonPrimitive
                ?.content ?: ""
        val limit =
            request.arguments
                ?.get("limit")
                ?.jsonPrimitive
                ?.content
                ?.toInt() ?: 10

        logger.info(
            "MCP echo_match tool invocation: name='{}', category='{}', algorithm='{}', limit={}",
            name,
            category,
            algorithm,
            limit,
        )

        val result = client.match(category, name, algorithm, limit)

        if (result.isError) {
            logger.error(
                "Error executing match tool: name='{}', category='{}', error='{}'",
                name,
                category,
                result.error,
            )
            val callResult =
                CallToolResult(
                    content = listOf(TextContent(text = formatTextResponse(result))),
                    isError = true,
                    structuredContent = resultStructured(result),
                )
            logger.info(
                "match completed | error | name={} | category={} | isError={}",
                name,
                category,
                callResult.isError,
            )
            logger.debug("match error result: {}", callResult)
            return callResult
        }

        val callResult =
            CallToolResult(
                content = listOf(TextContent(text = formatTextResponse(result))),
                structuredContent = resultStructured(result),
            )
        logger.info(
            "match completed | success | name={} | category={} | matchCount={} | isError={}",
            name,
            category,
            result.matches.size,
            callResult.isError,
        )
        logger.debug("match success result: {}", callResult)
        return callResult
    }
}
