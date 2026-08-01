// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.conformance

import java.net.Socket
import java.net.URI

/**
 * Minimal raw-socket SSE reader for the conformance self-tests. Reads the full response body to
 * EOF (the fixture server closes the connection to signal end-of-stream), returning the body bytes
 * with the HTTP head stripped. Deliberately not a real SSE parser — the harness self-test asserts
 * on raw bytes; parsing is LG-P2's tap.
 */
object SseTestClient {
    /** Connect, send a minimal GET, and return the response body bytes (head stripped). */
    fun readBody(baseUrl: String): ByteArray {
        val uri = URI(baseUrl)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(
                    (
                        "GET /sse HTTP/1.1\r\n" +
                            "Host: ${uri.host}:${uri.port}\r\n" +
                            "Accept: text/event-stream\r\n\r\n"
                    ).encodeToByteArray(),
                )
                flush()
            }
            val all = socket.getInputStream().readBytes()
            val bodyStart = indexOfHeadEnd(all)
            return if (bodyStart < 0) ByteArray(0) else all.copyOfRange(bodyStart, all.size)
        }
    }

    private fun indexOfHeadEnd(bytes: ByteArray): Int {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == CR && bytes[i + 1] == LF && bytes[i + 2] == CR && bytes[i + 3] == LF) {
                return i + 4
            }
        }
        return -1
    }

    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()
}
