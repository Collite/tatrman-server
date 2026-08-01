// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.conformance

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * LG-P0·S2·T1 — self-test of the SSE conformance harness. Proves the fixture server has the byte
 * control the B-4/P-1 edge cases require, BEFORE any of those fixtures (T2) or the tap (LG-P2) exist:
 * exact bytes on the wire, a split landing mid-UTF-8-codepoint, honored inter-frame delays, and an
 * abrupt mid-stream drop distinguishable from a clean `[DONE]` close.
 */
class SseFixtureServerSpec :
    StringSpec({

        "delivers the exact scripted bytes even when a write splits a UTF-8 codepoint" {
            // 世 = E4 B8 96 (3 bytes). Split one byte into it, so neither chunk is valid UTF-8 alone.
            val prefix = "data: {\"delta\":\"".encodeToByteArray()
            val payload = "data: {\"delta\":\"世界\"}\n\n".encodeToByteArray()
            val midCodepoint = prefix.size + 1 // inside 世's three bytes

            SseFixtureServer.start { splitAt(payload, midCodepoint) }.use { server ->
                val body = SseTestClient.readBody(server.baseUrl)

                body.toList() shouldBe payload.toList() // byte-exact delivery, split notwithstanding
                (payload[midCodepoint].toInt() and 0xC0) shouldBe 0x80 // offset is a UTF-8 continuation byte
                // decoding the first scripted write in isolation corrupts — the hazard the tap must avoid
                server.scriptedChunks[0].bytes.toString(Charsets.UTF_8) shouldContain "�"
                // reassembling both writes decodes cleanly
                server.scriptedChunks
                    .flatMap { it.bytes.toList() }
                    .toByteArray()
                    .toString(Charsets.UTF_8) shouldContain "世界"
            }
        }

        "honors an inter-frame delay" {
            val elapsedMs =
                System.nanoTime().let { start ->
                    SseFixtureServer
                        .start {
                            text("data: {\"delta\":\"a\"}\n\n")
                            text("data: {\"delta\":\"b\"}\n\n", delayMillis = 200)
                        }.use { server -> SseTestClient.readBody(server.baseUrl) }
                    (System.nanoTime() - start) / 1_000_000
                }
            elapsedMs shouldBeGreaterThan 150L
        }

        "a script without a terminator models an upstream mid-stream drop (EOF, no [DONE])" {
            SseFixtureServer
                .start {
                    text("data: {\"delta\":\"hi\"}\n\n")
                    text("data: {\"delta\":\" there\"}\n\n")
                    // no `data: [DONE]` — the connection just closes
                }.use { server ->
                    val body = SseTestClient.readBody(server.baseUrl).toString(Charsets.UTF_8)
                    body shouldContain "hi"
                    body shouldContain "there"
                    body shouldNotContain "[DONE]"
                }
        }

        "a clean stream ends with the [DONE] terminator" {
            SseFixtureServer
                .start {
                    text("data: {\"delta\":\"x\"}\n\n")
                    text("data: [DONE]\n\n")
                }.use { server ->
                    SseTestClient.readBody(server.baseUrl).toString(Charsets.UTF_8) shouldContain "data: [DONE]"
                }
        }
    })
