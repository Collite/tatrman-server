// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.stream

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream

/**
 * One SSE event block (the bytes between two blank lines). Carries the parsed [fields] (ordered
 * `name → value`), any leading `:` [comments], AND the exact [raw] bytes that produced it — the raw
 * bytes are what the passthrough writer re-emits, so a non-rewritten frame reaches the client
 * byte-identical (dual representation, B-3).
 */
class SseFrame(
    val fields: List<Pair<String, String>>,
    val comments: List<String>,
    val raw: ByteArray,
) {
    /** Concatenated `data:` values (SSE joins multiple data lines with `\n`); null if the block has none. */
    val data: String?
        get() {
            val d = fields.filter { it.first == "data" }
            return if (d.isEmpty()) null else d.joinToString("\n") { it.second }
        }

    /** A keep-alive comment block (`: hb`) with no data — the tap ignores these, the writer forwards them. */
    val isComment: Boolean get() = fields.isEmpty() && comments.isNotEmpty()

    val isDone: Boolean get() = data?.trim() == "[DONE]"

    companion object {
        /** A gateway-synthesized single-`data:` frame (`data: <value>\n\n`) — used for error/`[DONE]` frames. */
        fun ofData(value: String): SseFrame =
            SseFrame(listOf("data" to value), emptyList(), "data: $value\n\n".encodeToByteArray())
    }
}

/**
 * Byte-safe SSE parser (the named line-at-a-time-decode anti-pattern's antidote, design §Risks). Bytes are
 * pushed in as they arrive off the socket; a line is decoded as UTF-8 **only once its full terminator
 * is seen**, so a multi-byte codepoint split across two network reads is reassembled before decoding.
 * CR, LF, and CRLF line terminators are all honored (SSE spec), including a CRLF split across reads.
 *
 * Pure — no Ktor, no coroutines — so `utf8-split` lives or dies here under adversarial chunking
 * ([feed] may be called with any byte boundaries). [SseFramer] is the thin channel adapter.
 */
class SseByteParser {
    private val line = ByteArrayOutputStream()
    private val frameBytes = ByteArrayOutputStream()
    private val fields = mutableListOf<Pair<String, String>>()
    private val comments = mutableListOf<String>()
    private var pendingCr = false
    private val out = mutableListOf<SseFrame>()

    /** Push [len] bytes of [bytes]; returns any frames completed by this push. */
    fun feed(
        bytes: ByteArray,
        len: Int = bytes.size,
    ): List<SseFrame> {
        for (i in 0 until len) consume(bytes[i])
        return drain()
    }

    /** Flush at EOF: a trailing lone CR terminates the last line; a complete pending block is dispatched. */
    fun close(): List<SseFrame> {
        if (pendingCr) {
            pendingCr = false
            completeLine()
        }
        if (fields.isNotEmpty() || comments.isNotEmpty()) dispatch()
        return drain()
    }

    private fun consume(b: Byte) {
        frameBytes.write(b.toInt())
        if (pendingCr) {
            pendingCr = false
            if (b == LF) {
                // CRLF: the full terminator is now consumed (both bytes in frameBytes), complete the line.
                completeLine()
                return
            }
            // Lone CR terminated the previous line; fall through to handle b as the next line's first byte.
            completeLine()
        }
        when (b) {
            CR -> pendingCr = true // wait for a possible LF before completing (CRLF vs lone CR)
            LF -> completeLine()
            else -> line.write(b.toInt())
        }
    }

    private fun completeLine() {
        val s = line.toString(Charsets.UTF_8.name()) // decode the whole buffered line at once (multibyte-safe)
        line.reset()
        processLine(s)
    }

    private fun processLine(s: String) {
        when {
            s.isEmpty() -> if (fields.isNotEmpty() || comments.isNotEmpty()) dispatch()
            s[0] == ':' -> comments.add(s.substring(1))
            else -> {
                val idx = s.indexOf(':')
                val name = if (idx < 0) s else s.substring(0, idx)
                var value = if (idx < 0) "" else s.substring(idx + 1)
                if (value.startsWith(" ")) value = value.substring(1) // strip ONE leading space (SSE spec)
                fields.add(name to value)
            }
        }
    }

    private fun dispatch() {
        out.add(SseFrame(fields.toList(), comments.toList(), frameBytes.toByteArray()))
        fields.clear()
        comments.clear()
        frameBytes.reset()
    }

    private fun drain(): List<SseFrame> {
        if (out.isEmpty()) return emptyList()
        val r = out.toList()
        out.clear()
        return r
    }

    private companion object {
        const val CR = '\r'.code.toByte()
        const val LF = '\n'.code.toByte()
    }
}

/** Channel adapter: a streaming upstream body → a cold [Flow] of [SseFrame], read byte-incrementally. */
object SseFramer {
    private const val READ_CHUNK = 8192

    fun frames(channel: ByteReadChannel): Flow<SseFrame> =
        flow {
            val parser = SseByteParser()
            val buf = ByteArray(READ_CHUNK)
            // readAvailable suspends only until SOME bytes arrive (or EOF → -1), returning what is available
            // RIGHT NOW rather than blocking for a fixed size. That per-read incrementality is what makes
            // per-event flush and idle heartbeats work; the parser tolerates any chunk boundary.
            while (true) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) parser.feed(buf, n).forEach { emit(it) }
            }
            parser.close().forEach { emit(it) }
        }
}
