// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.mcp

import io.ktor.server.application.Application
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.slf4j.LoggerFactory
import org.tatrman.mcp.identity.RequestContext
import shared.ktor.mcp.safeMcpTool

/**
 * Mounts the `resolve.bind:v1` StreamableHTTP MCP door on this Ktor application
 * (RG-P6.S1.T2). The per-call [requestContext] is populated by an upstream Ktor
 * interceptor with `Authorization` / `X-User-Id`; the tool body reads it back, runs
 * the fail-closed OBO gate ([ResolveDoorHandler]), and — only on allow — resolves.
 * Every call is `safeMcpTool`-wrapped so a timeout / thrown exception becomes a
 * `CallToolResult(isError=true)` rather than a broken stream.
 */
fun Application.installResolveDoor(
    door: ResolveDoor,
    handler: ResolveDoorHandler,
    requestContext: RequestContext,
    toolTimeoutMs: Long = 20_000L,
) {
    val logger = LoggerFactory.getLogger("ttr-resolver.door")
    mcpStreamableHttp {
        Server(
            serverInfo = Implementation(name = "ttr-resolver", version = "0.1.0"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                ),
        ).also { server ->
            server.addTool(
                name = door.tool.name,
                description = door.tool.description ?: "",
                inputSchema = door.tool.inputSchema,
            ) { request ->
                // Snapshot the per-request headers HERE — this lambda runs on the
                // request's interceptor thread, before `safeMcpTool`'s withTimeout can
                // dispatch the body onto another pool thread where the ThreadLocal would
                // be null or (worse) hold a prior request's identity (RG-P6 review 3).
                val snapshot = requestContext.snapshot()
                safeMcpTool(RESOLVE_TOOL_NAME, toolTimeoutMs) { req ->
                    handler.handle(req.arguments, snapshot.authHeader, snapshot.userIdHeader)
                }(request)
            }
            logger.info("resolve door bound: tool '{}' (streamable HTTP)", door.tool.name)
        }
    }
}
