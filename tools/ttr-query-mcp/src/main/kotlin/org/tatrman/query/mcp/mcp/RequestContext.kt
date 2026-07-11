package org.tatrman.query.mcp.mcp

/**
 * Per-request ThreadLocal stash for inbound headers, populated by a Ktor
 * interceptor before the MCP transport sees the call. The MCP SDK's
 * tool-handler lambda runs on the request's coroutine but exposes only the
 * decoded [io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest], so we
 * hop through this stash to surface the headers.
 */
class RequestContext {
    val authHeader: ThreadLocal<String?> = ThreadLocal()
    val userIdHeader: ThreadLocal<String?> = ThreadLocal()

    /** Snapshot the current values into a no-arg holder for testability. */
    fun snapshot(): Snapshot = Snapshot(authHeader.get(), userIdHeader.get())

    data class Snapshot(
        val authHeader: String?,
        val userIdHeader: String?,
    )
}
