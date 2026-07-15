// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.conformance

import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * LG-P0·S2 — byte-exact SSE fixture server for the provider conformance harness.
 *
 * Models an **upstream** SSE producer with total control over the byte stream: it emits a
 * scripted sequence of raw chunks, with per-chunk delays, and can end abruptly (mid-stream
 * drop) by simply closing the socket without a `[DONE]` terminator. LG-P2's parse-lite tap
 * and SSE writer are exercised against streams built with this.
 *
 * **Why a raw [ServerSocket] rather than WireMock `chunkedDribbleDelay`** (task S2·T1, tool is
 * negotiable, byte control is not): the B-4/P-1 edge cases require splitting the stream at a
 * *specific* byte offset — notably mid-UTF-8-codepoint — and dribble only distributes a body
 * across N equal chunks over a fixed duration, with no control over where a split lands. The
 * socket gives exact offsets in ~70 lines with no extra dependency (java.net + one daemon thread).
 * WireMock stays the tool for HTTP-status/error-frame fixtures (`.http` cases); byte-level SSE
 * framing uses this. (Recorded in the S2 findings.)
 */
class SseFixtureServer private constructor(
    private val server: ServerSocket,
    val scriptedChunks: List<Chunk>,
) : Closeable {
    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** One scripted write to the socket: [bytes] flushed after an optional [delayMillis] pause. */
    data class Chunk(
        val bytes: ByteArray,
        val delayMillis: Long = 0,
    ) {
        override fun equals(other: Any?) =
            other is Chunk && bytes.contentEquals(other.bytes) && delayMillis == other.delayMillis

        override fun hashCode() = 31 * bytes.contentHashCode() + delayMillis.hashCode()
    }

    @Suppress("unused")
    private val worker =
        thread(start = true, isDaemon = true, name = "sse-fixture-$port") {
            try {
                server.accept().use { socket -> serve(socket) }
            } catch (_: Exception) {
                // server closed before/while a client connected — expected on teardown
            }
        }

    private fun serve(socket: Socket) {
        socket.soTimeout = 5_000
        // Read the request head AND drain the body (POST callers send one) — closing the socket while the
        // client is still writing its request would RST the connection and fail the client's response read.
        readRequest(socket.getInputStream())
        val out = socket.getOutputStream()
        out.write(RESPONSE_HEAD)
        out.flush()
        for (chunk in scriptedChunks) {
            if (chunk.delayMillis > 0) Thread.sleep(chunk.delayMillis)
            out.write(chunk.bytes)
            out.flush()
        }
        // Close = EOF to the client. A fixture whose script omits `data: [DONE]` therefore models
        // an upstream mid-stream drop; one that ends with it models a clean close.
    }

    override fun close() {
        server.close()
    }

    companion object {
        private val RESPONSE_HEAD =
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
            ).encodeToByteArray()

        /** Start a fixture server on an ephemeral loopback port with a scripted stream. */
        fun start(build: ScriptBuilder.() -> Unit): SseFixtureServer {
            val chunks = ScriptBuilder().apply(build).chunks
            val server = ServerSocket()
            server.bind(InetSocketAddress("127.0.0.1", 0))
            return SseFixtureServer(server, chunks)
        }

        /** Read the request head (through CRLFCRLF) then drain any body per Content-Length, so the client's write completes. */
        private fun readRequest(input: InputStream) {
            val head = StringBuilder()
            var state = 0 // matches \r \n \r \n
            while (state < 4) {
                val b = input.read()
                if (b == -1) return
                head.append(b.toChar())
                state =
                    when {
                        (state == 0 || state == 2) && b == '\r'.code -> state + 1
                        (state == 1 || state == 3) && b == '\n'.code -> state + 1
                        else -> 0
                    }
            }
            val contentLength =
                head
                    .lineSequence()
                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.toIntOrNull() ?: 0
            var remaining = contentLength
            while (remaining > 0 && input.read() != -1) remaining--
        }
    }

    /** DSL for scripting the emitted byte stream. */
    class ScriptBuilder {
        val chunks = mutableListOf<Chunk>()

        /** Emit [bytes] as one flushed write, after an optional pre-delay. */
        fun bytes(
            bytes: ByteArray,
            delayMillis: Long = 0,
        ) {
            chunks += Chunk(bytes, delayMillis)
        }

        /** Emit UTF-8 [text] as one flushed write. */
        fun text(
            text: String,
            delayMillis: Long = 0,
        ) {
            chunks += Chunk(text.encodeToByteArray(), delayMillis)
        }

        /**
         * Emit [payload] cut into flushed writes at the given absolute byte [offsets]. Offsets may
         * fall **inside** a multi-byte UTF-8 codepoint — that is the point: it forces the reader to
         * buffer across writes rather than decode each one in isolation (the 1.x `readUTF8Line`
         * anti-pattern, design §Risks). Delivered bytes are identical to [payload].
         */
        fun splitAt(
            payload: ByteArray,
            vararg offsets: Int,
            delayMillis: Long = 0,
        ) {
            require(offsets.all { it in 1 until payload.size }) { "offsets must be within (0, size)" }
            require(offsets.toList() == offsets.sorted()) { "offsets must be ascending" }
            var prev = 0
            for (o in offsets) {
                chunks += Chunk(payload.copyOfRange(prev, o), delayMillis)
                prev = o
            }
            chunks += Chunk(payload.copyOfRange(prev, payload.size), delayMillis)
        }
    }
}
