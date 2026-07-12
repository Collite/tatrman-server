// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.mcp.client

import org.fuzzy.common.FuzzyMatchResponse
import org.tatrman.fuzzy.mcp.telemetry.FuzzyMcpTelemetry
import java.io.Closeable

interface FuzzyClient : Closeable {
    suspend fun match(
        category: String,
        name: String,
        algorithm: String,
        limit: Int = 10,
    ): FuzzyMatchResponse

    fun getTelemetry(): FuzzyMcpTelemetry?
}
