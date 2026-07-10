package org.tatrman.llmgateway.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class ObservabilityService(
    private val promptLogRepository: PromptLogRepository,
    private val meterRegistry: MeterRegistry,
) {
    // Record Log & Metrics asynchronously to avoid blocking response
    // (Though for metrics, usually you want synchronous recording, logs can be async)
    private val logger = org.slf4j.LoggerFactory.getLogger(ObservabilityService::class.java)

    @Async
    fun recordInteraction(logEntry: PromptLog) {
        // 1. Save Log
        try {
            promptLogRepository.save(logEntry)
        } catch (e: Exception) {
            logger.error("Failed to save prompt log", e)
        }

        // 2. Metrics
        try {
            meterRegistry
                .counter(
                    "llm.requests.count",
                    "model",
                    logEntry.modelName ?: "unknown",
                    "provider",
                    logEntry.provider ?: "unknown",
                    "status",
                    logEntry.status,
                ).increment()

            if (logEntry.durationMs != null) {
                meterRegistry
                    .timer(
                        "llm.requests.latency",
                        "model",
                        logEntry.modelName ?: "unknown",
                    ).record(logEntry.durationMs, TimeUnit.MILLISECONDS)
            }

            if (logEntry.tokensPrompt != null) {
                meterRegistry
                    .summary(
                        "llm.tokens.prompt",
                        "model",
                        logEntry.modelName ?: "unknown",
                    ).record(logEntry.tokensPrompt.toDouble())
            }
            if (logEntry.tokensCompletion != null) {
                meterRegistry
                    .summary(
                        "llm.tokens.completion",
                        "model",
                        logEntry.modelName ?: "unknown",
                    ).record(logEntry.tokensCompletion.toDouble())
            }
        } catch (e: Exception) {
            logger.error("Failed to record metrics for prompt log", e)
        }
        // 3. Structured Logging for Loki/Alloy
        // Logging as JSON-friendly format or MDC could be used, but here we just log key
        // fields
        // which OTel collector can parse if configured properly.
        logger.info(
            "LLM Interaction: model={} provider={} status={} duration={} tokens_prompt={} tokens_completion={}",
            logEntry.modelName,
            logEntry.provider,
            logEntry.status,
            logEntry.durationMs,
            logEntry.tokensPrompt,
            logEntry.tokensCompletion,
        )
    }
}
