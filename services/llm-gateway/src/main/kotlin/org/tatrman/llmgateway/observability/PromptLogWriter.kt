// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import org.tatrman.llmgateway.governance.Settle
import shared.libs.db.common.DatabaseConnection
import java.math.BigDecimal

/** One prompt-log row: the [Settle] facts (§3 attribution columns) plus the prompt/response text (V1 columns). */
data class PromptLogRecord(
    val settle: Settle,
    val promptText: String,
    val responseText: String,
    val status: String, // SUCCESS | ERROR (1.x column)
)

/**
 * PG prompt-log sink (F-1, contracts §3), consuming the [Settle] record — the second of its three sinks
 * (budget, prompt-log, metrics). **Async write-behind**: rows are offered to a bounded channel and drained
 * by a single writer coroutine, so a request NEVER waits on PG. On a full queue the row is **dropped + a
 * metric ticks** (F-1/P-2 tension: log loss is survivable, blocking the data plane is not). The V1 TSVECTOR
 * trigger fires on these inserts, so FTS keeps working on the new-schema rows.
 */
class PromptLogWriter(
    private val db: DatabaseConnection,
    scope: CoroutineScope,
    private val metrics: MeterRegistry? = null,
    capacity: Int = 1024,
) {
    private val channel = Channel<PromptLogRecord>(capacity)

    // Drain on Dispatchers.IO: the writer runs blocking Exposed/JDBC per row, which must not sit on the
    // request-serving dispatcher (it would park a data-plane thread for the length of each insert).
    private val writer =
        scope.launch(Dispatchers.IO) {
            for (rec in channel) {
                runCatching { insert(rec) }.onFailure {
                    log.warn("prompt-log write failed", it)
                    metrics?.counter("llm_gateway_promptlog_write_error_total")?.increment()
                }
            }
        }

    /** Non-blocking offer. A full queue drops the row (never blocks the caller). */
    fun enqueue(rec: PromptLogRecord) {
        if (!channel.trySend(rec).isSuccess) {
            metrics?.counter("llm_gateway_promptlog_dropped_total")?.increment()
            log.warn(
                "prompt-log queue full — dropping a row (F-1: log loss survivable, blocking the data plane is not)",
            )
        }
    }

    /**
     * Close the queue and briefly wait for the writer to drain already-queued rows, so a graceful stop
     * flushes in-flight logs BEFORE the caller closes the PG pool. Bounded, so a stuck insert can't hang
     * shutdown — anything past the deadline is abandoned (log loss is survivable, F-1).
     */
    fun close() {
        channel.close()
        runBlocking { withTimeoutOrNull(DRAIN_TIMEOUT_MS) { writer.join() } }
    }

    private fun insert(r: PromptLogRecord) {
        val s = r.settle
        val strippedJson = JsonArray(s.strippedParams.map { JsonPrimitive(it) }).toString()
        transaction {
            exec(
                INSERT_SQL,
                listOf(
                    TextColumnType() to s.keyId, // user_id (1.x) = the key id
                    TextColumnType() to s.servedModel, // model_name (1.x)
                    TextColumnType() to s.servedProvider, // provider (1.x)
                    TextColumnType() to r.promptText,
                    TextColumnType() to r.responseText,
                    IntegerColumnType() to s.usage.promptTokens.toInt(),
                    IntegerColumnType() to s.usage.completionTokens.toInt(),
                    LongColumnType() to s.durationMs,
                    TextColumnType() to r.status,
                    TextColumnType() to s.keyId,
                    TextColumnType() to s.teamId,
                    TextColumnType() to s.costCenter,
                    TextColumnType() to s.turnRef,
                    TextColumnType() to s.requestedModel,
                    TextColumnType() to s.servedProvider,
                    TextColumnType() to s.servedModel,
                    TextColumnType() to s.fallbackFrom,
                    TextColumnType() to strippedJson, // stripped_params — bound as text, cast ?::jsonb in SQL
                    BooleanColumnType() to s.estimated,
                    BooleanColumnType() to s.cached,
                    DecimalColumnType(12, 6) to BigDecimal.valueOf(s.costUsd),
                    LongColumnType() to s.ttfbMs,
                    TextColumnType() to s.traceId,
                ),
            )
        }
    }

    private companion object {
        const val DRAIN_TIMEOUT_MS = 5_000L
        val log = LoggerFactory.getLogger(PromptLogWriter::class.java)
        val INSERT_SQL =
            """
            INSERT INTO prompt_logs
              (user_id, model_name, provider, prompt_text, response_text, tokens_prompt, tokens_completion,
               duration_ms, status, key_id, team_id, cost_center, turn_ref, requested_model, served_provider,
               served_model, fallback_from, stripped_params, estimated, cached, cost_usd, ttfb_ms, trace_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """.trimIndent()
    }
}
