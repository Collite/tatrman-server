// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.stream

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.llmgateway.conformance.ConformanceFixtures

/**
 * LG-P2·S2·T1/T2 — the byte-safe framer under adversarial chunking. `utf8-split` lives or dies here:
 * a stream fragmented INSIDE a 4-byte codepoint must decode identically to the whole-buffer read (the
 * `readUTF8Line` anti-pattern would corrupt it). Also proves byte-faithful `raw` reassembly, all three
 * SSE line terminators, multi-`data:` concatenation, and comment blocks.
 */
class SseByteParserSpec :
    StringSpec({

        fun framesOf(chunks: List<ByteArray>): List<SseFrame> {
            val parser = SseByteParser()
            val out = mutableListOf<SseFrame>()
            chunks.forEach { out += parser.feed(it) }
            out += parser.close()
            return out
        }

        /** Every possible single-cut chunking is dominated by byte-by-byte — the strongest adversary. */
        fun byteByByte(bytes: ByteArray): List<ByteArray> = bytes.map { byteArrayOf(it) }

        "utf8-split: fragmenting inside the 🌍 codepoint yields the same frames as a whole-buffer read" {
            val fixture = ConformanceFixtures.load("utf8-split")
            val whole = framesOf(listOf(fixture.body)).map { it.data }
            val perByte = framesOf(byteByByte(fixture.body)).map { it.data }
            // and split exactly inside the emoji, as the fixture's meta declares
            val offsets = fixture.resolvedSplitOffsets()
            val atCodepoint =
                framesOf(
                    listOf(
                        fixture.body.copyOfRange(0, offsets.first()),
                        fixture.body.copyOfRange(offsets.first(), fixture.body.size),
                    ),
                ).map { it.data }

            perByte shouldContainExactly whole
            atCodepoint shouldContainExactly whole
            // the multibyte content survived intact (accented Latin + CJK + 4-byte emoji)
            whole.first()!! shouldContain "café 世界 🌍"
        }

        "raw bytes reassemble to the exact input (byte-faithful passthrough)" {
            val body = ConformanceFixtures.load("done-terminator").body
            val reassembled = framesOf(byteByByte(body)).fold(ByteArray(0)) { acc, f -> acc + f.raw }
            reassembled.toList() shouldBe body.toList()
        }

        "all three SSE line terminators frame identically (LF, CRLF, lone CR), incl. CRLF split across reads" {
            val lf = "data: a\n\ndata: b\n\n".encodeToByteArray()
            val crlf = "data: a\r\n\r\ndata: b\r\n\r\n".encodeToByteArray()
            val cr = "data: a\r\rdata: b\r\r".encodeToByteArray()

            framesOf(listOf(lf)).map { it.data } shouldContainExactly listOf("a", "b")
            framesOf(byteByByte(crlf)).map { it.data } shouldContainExactly listOf("a", "b")
            framesOf(byteByByte(cr)).map { it.data } shouldContainExactly listOf("a", "b")

            // CRLF split precisely between the CR and the LF of a terminator
            val split = listOf("data: x\r".encodeToByteArray(), "\n\r\n".encodeToByteArray())
            framesOf(split).map { it.data } shouldContainExactly listOf("x")
        }

        "multiple data: lines in one block concatenate with \\n (SSE spec)" {
            val block = "data: line1\ndata: line2\n\n".encodeToByteArray()
            framesOf(listOf(block)).single().data shouldBe "line1\nline2"
        }

        "comment blocks are surfaced as comment frames, not data" {
            val frames = framesOf(listOf(": hb\n\ndata: real\n\n".encodeToByteArray()))
            frames[0].isComment shouldBe true
            frames[0].data shouldBe null
            frames[1].data shouldBe "real"
        }
    })
