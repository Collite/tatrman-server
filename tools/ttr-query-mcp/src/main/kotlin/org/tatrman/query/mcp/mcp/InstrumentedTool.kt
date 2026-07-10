package org.tatrman.query.mcp.mcp

import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import org.tatrman.query.mcp.identity.UserIdentity
import shared.otel.withSpan
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decorator that adds active-request counting, Prometheus call counters,
 * and per-tool durations around an underlying [McpTool].
 *
 * Metric names:
 *   * `theseus_mcp_tool_calls_total{tool, outcome}` — outcome ∈ ok | error
 *   * `theseus_mcp_tool_call_duration_seconds{tool}` — timer
 *   * `theseus_mcp_tool_errors_total{tool, code}` — error-code breakdown
 */
class InstrumentedTool(
    private val delegate: McpTool,
    private val activeRequests: AtomicInteger,
    private val metrics: MeterRegistry,
    openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get(),
) : McpTool by delegate {
    private val logger = LoggerFactory.getLogger("theseus-mcp.tool.${delegate.name}")

    // Root span for the tool call — the head of the in-process run_query trace.
    // Theseus's orchestration spans (theseus.run → parse/validate/dispatch) nest
    // under it via OTel context propagation; cross-pod they nest via gRPC
    // auto-instrumentation (Stage 4.1 T3).
    private val tracer = openTelemetry.getTracer("theseus-mcp")

    // Pre-register name-bound counters / timers; tag values may differ.
    override val name: String get() = delegate.name
    override val description: String get() = delegate.description
    override val inputSchema: ToolSchema get() = delegate.inputSchema
    override val outputSchema: ToolSchema? get() = delegate.outputSchema

    override suspend fun execute(
        request: CallToolRequest,
        identity: UserIdentity?,
    ): CallToolResult =
        tracer.withSpan("mcp.tool.${delegate.name}", SpanKind.SERVER) {
            activeRequests.incrementAndGet()
            val start = System.nanoTime()
            var outcome = "error"
            var errorCode: String? = "unknown"
            try {
                val result = delegate.execute(request, identity)
                outcome = if (result.isError == true) "error" else "ok"
                errorCode = if (outcome == "error") extractFirstErrorCode(result) else null
                result
            } catch (t: Throwable) {
                errorCode = "unhandled_exception"
                logger.error("Tool {} threw an unhandled exception", delegate.name, t)
                throw t
            } finally {
                val durationNanos = System.nanoTime() - start
                metrics
                    .timer(
                        "theseus_mcp_tool_call_duration_seconds",
                        "tool",
                        delegate.name,
                    ).record(durationNanos, TimeUnit.NANOSECONDS)
                metrics
                    .counter(
                        "theseus_mcp_tool_calls_total",
                        "tool",
                        delegate.name,
                        "outcome",
                        outcome,
                    ).increment()
                if (outcome == "error" && errorCode != null) {
                    metrics
                        .counter(
                            "theseus_mcp_tool_errors_total",
                            "tool",
                            delegate.name,
                            "code",
                            errorCode,
                        ).increment()
                }
                activeRequests.decrementAndGet()
            }
        }

    private fun extractFirstErrorCode(result: CallToolResult): String? {
        val structured = result.structuredContent ?: return null
        val messages = structured["messages"] as? JsonArray ?: return null
        val first = messages.firstOrNull() as? JsonObject ?: return null
        return (first["code"] as? JsonPrimitive)?.content
    }
}
