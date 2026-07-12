// SPDX-License-Identifier: Apache-2.0
package shared.ktor.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

private val logger = org.slf4j.LoggerFactory.getLogger("shared.ktor.mcp.safeMcpTool")

fun Map<String, Long>.timeoutFor(
    name: String,
    default: Long,
): Long = this[name] ?: default

suspend fun safeMcpTool(
    name: String,
    timeoutMs: Long,
    block: suspend (CallToolRequest) -> CallToolResult,
): suspend (CallToolRequest) -> CallToolResult =
    { request ->
        val startNs = System.nanoTime()
        try {
            withTimeout(timeoutMs.milliseconds) {
                block(request)
            }.also { result ->
                val durationMs = (System.nanoTime() - startNs) / 1_000_000
                val errorCode = result.structuredContent?.get("errorCode")?.toString()
                if (result.isError == true) {
                    logger.warn(
                        "toolName={} durationMs={} outcome=error errorCode={}",
                        name,
                        durationMs,
                        errorCode,
                    )
                } else {
                    logger.info("toolName={} durationMs={} outcome=ok", name, durationMs)
                }
            }
        } catch (t: TimeoutCancellationException) {
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            logger.warn("toolName={} durationMs={} outcome=timeout errorCode=TIMEOUT", name, durationMs)
            CallToolResult(
                isError = true,
                content =
                    listOf(
                        TextContent(
                            text = "Tool '$name' timed out after $timeoutMs ms",
                        ),
                    ),
                structuredContent =
                    buildJsonObject {
                        put("errorCode", "TIMEOUT")
                        put("error", "Tool '$name' timed out after $timeoutMs ms")
                        put("message", "Tool '$name' timed out after $timeoutMs ms")
                        put(
                            "extras",
                            buildJsonObject {
                                put("tool", name)
                            },
                        )
                    },
            )
        } catch (t: Throwable) {
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            logger.error("toolName={} durationMs={} outcome=error errorCode=EXECUTION_ERROR", name, durationMs, t)
            CallToolResult(
                isError = true,
                content =
                    listOf(
                        TextContent(
                            text = "Tool '$name' failed: ${t.message}",
                        ),
                    ),
                structuredContent =
                    buildJsonObject {
                        put("errorCode", "EXECUTION_ERROR")
                        put("error", t.message ?: "Unknown error")
                        put("message", t.message ?: "Unknown error")
                        put(
                            "extras",
                            buildJsonObject {
                                put("tool", name)
                            },
                        )
                    },
            )
        }
    }
