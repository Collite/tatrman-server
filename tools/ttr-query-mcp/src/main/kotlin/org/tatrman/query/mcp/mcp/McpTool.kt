// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import org.tatrman.mcp.identity.UserIdentity

/**
 * Adapter shape for an MCP tool registered with this service. Each concrete
 * tool (`query`, `compile`) returns one of these from a factory and is
 * plugged into the StreamableHTTP server in [installQueryMcp].
 *
 * The `execute` lambda receives the resolved [UserIdentity] (possibly null
 * when `security.require-identity = false`) so tool implementations need
 * not duplicate header parsing.
 */
interface McpTool {
    val name: String
    val description: String
    val inputSchema: ToolSchema
    val outputSchema: ToolSchema?
        get() = null

    suspend fun execute(
        request: CallToolRequest,
        identity: UserIdentity?,
    ): CallToolResult
}

/**
 * In-memory immutable registry over the tools wired at boot time. Kept
 * separate from the SDK Server so [Application] can express tools list-up
 * declaratively and tests can exercise a registry directly.
 */
class ToolRegistry(
    private val tools: List<McpTool>,
) {
    init {
        val dupes =
            tools
                .groupingBy { it.name }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(dupes.isEmpty()) { "Duplicate tool names registered: $dupes" }
    }

    fun all(): List<McpTool> = tools

    fun get(name: String): McpTool? = tools.firstOrNull { it.name == name }
}
