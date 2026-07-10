package org.tatrman.fuzzy.mcp.client

import org.fuzzy.common.FuzzyMatchResponse
import org.tatrman.fuzzy.mcp.telemetry.EchoMcpTelemetry
import java.io.Closeable

interface EchoClient : Closeable {
    suspend fun match(
        category: String,
        name: String,
        algorithm: String,
        limit: Int = 10,
    ): FuzzyMatchResponse

    fun getTelemetry(): EchoMcpTelemetry?
}
