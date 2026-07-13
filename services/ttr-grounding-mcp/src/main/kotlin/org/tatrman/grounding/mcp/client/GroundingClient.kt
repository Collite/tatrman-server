package org.tatrman.grounding.mcp.client

import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import java.io.Closeable

/**
 * Transport seam onto ONE deterministic grounding service (chrono / geo / money — all speak the same
 * `GroundingService` proto). The MCP wrapper holds three of these, one per service. Kept minimal:
 * the MCP does no grounding logic (service-vs-MCP rule), just request/response passthrough.
 */
interface GroundingClient : Closeable {
    /** The wrapped service's name ("chrono" | "geo" | "money"), for logs/telemetry. */
    val serviceName: String

    suspend fun ground(request: GroundRequest): GroundResponse
}
