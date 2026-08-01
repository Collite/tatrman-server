// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.tatrman.llmgateway.config.CatalogModel
import org.tatrman.llmgateway.stream.SseFrame
import org.tatrman.llmgateway.stream.StreamObservation
import org.tatrman.llmgateway.stream.TapParser

/**
 * Tees a passthrough SSE stream to the client (byte-faithful) while driving the tap side channel (B-3):
 *
 * - every frame's raw bytes are written verbatim and flushed per-event, EXCEPT the usage chunk, which is
 *   the one frame the gateway rewrites to carry the §1.3 extension (dual names + cost + `cached`);
 * - heartbeat comment frames (`: hb`) are emitted on idle (`heartbeatMillis`);
 * - a mid-stream drop AFTER the first token (upstream EOF without `[DONE]`, or a read error) surfaces as
 *   one `data: {"error":…}` frame + `[DONE]` + close (contracts §1.4) — never a silent stall;
 * - client disconnect cancels the collect, which (structured concurrency) cancels the upstream read.
 *
 * The tap is a side channel: [tap] observations feed settlement/metrics (LG-P5); they never gate bytes.
 */
suspend fun pumpSse(
    frames: Flow<SseFrame>,
    out: ByteWriteChannel,
    tap: (StreamObservation) -> Unit,
    parser: TapParser,
    model: CatalogModel,
    heartbeatMillis: Long,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
): Unit =
    coroutineScope {
        tap(parser.opened())
        val writeMutex = Mutex()
        var lastWriteAt = nowMs()

        suspend fun write(bytes: ByteArray) =
            writeMutex.withLock {
                out.writeFully(bytes)
                out.flush()
                lastWriteAt = nowMs()
            }

        val heartbeat =
            if (heartbeatMillis > 0) {
                launch {
                    val tick = (heartbeatMillis / 4).coerceAtLeast(50)
                    while (isActive) {
                        delay(tick)
                        if (nowMs() - lastWriteAt >= heartbeatMillis) write(HEARTBEAT_FRAME)
                    }
                }
            } else {
                null
            }

        var sawDone = false
        try {
            frames.collect { frame ->
                parser.onFrame(frame).forEach(tap)
                val bytes = usageRewriteOrNull(frame, model) ?: frame.raw
                write(bytes)
                if (frame.isDone) sawDone = true
            }
            // Clean EOF. A stream that stopped after the first token without `[DONE]` is a drop (§1.4).
            if (!sawDone) {
                if (parser.firstTokenSeen) {
                    emitDrop(
                        ::write,
                        tap,
                    )
                } else {
                    write(DONE_FRAME).also { tap(StreamObservation.Done) }
                }
            }
        } catch (c: CancellationException) {
            throw c // client disconnect — let structured concurrency tear down the upstream read
        } catch (e: Exception) {
            // We already committed the 200 SSE head, so a read failure can only surface as an error frame.
            if (!sawDone) emitDrop(::write, tap)
        } finally {
            heartbeat?.cancel()
        }
    }

/** If [frame] carries a `usage` object, re-serialize it with the §1.3 extension injected; else null (pass raw). */
private fun usageRewriteOrNull(
    frame: SseFrame,
    model: CatalogModel,
): ByteArray? {
    val data = frame.data ?: return null
    if (frame.isDone) return null
    // Hot-path guard: the `usage` chunk appears once (stream end), so skip the JSON parse on every token
    // delta. Only the frame that literally contains a `usage` key is a rewrite candidate. Without this the
    // tap ALREADY parses each frame (TapParser.onFrame) and this would parse it a second time per delta.
    if (!data.contains("\"usage\"")) return null
    val json = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
    if (json["usage"] !is JsonObject) return null
    val rewritten = ResponseEnrichment.streamingUsageChunk(json, model)
    return "data: $rewritten\n\n".encodeToByteArray()
}

/** Emit the mid-stream-drop contract: one error frame (Network) + `[DONE]`, tapping both (§1.4). */
private suspend fun emitDrop(
    write: suspend (ByteArray) -> Unit,
    tap: (StreamObservation) -> Unit,
) {
    write(NETWORK_ERROR_FRAME)
    tap(
        StreamObservation.ErrorFrame(
            org.tatrman.llmgateway.wire.GatewayError
                .Network(),
        ),
    )
    write(DONE_FRAME)
    tap(StreamObservation.Done)
}

private val HEARTBEAT_FRAME = ": hb\n\n".encodeToByteArray()
private val DONE_FRAME = "data: [DONE]\n\n".encodeToByteArray()
private val NETWORK_ERROR_FRAME =
    (
        "data: {\"error\":{\"message\":\"upstream connection lost mid-stream\"," +
            "\"type\":\"server_error\",\"code\":null}}\n\n"
    ).encodeToByteArray()
