// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.stream

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.llmgateway.conformance.ConformanceFixtures

/**
 * LG-P2·S2·T3 — the parse-lite tap over the LG-P0·S2 SSE fixtures. Proves the observation script per
 * design B-4: FirstToken flips once, content/tool-call deltas are counted, the usage chunk is extracted
 * with cached tokens, `[DONE]` terminates, a mid-stream error frame maps through the ErrorConverter, and
 * heartbeat comments are ignored (never mistaken for a token).
 */
class TapParserSpec :
    StringSpec({

        fun StreamObservation.tag(): String =
            when (this) {
                is StreamObservation.Opened -> "Opened"
                is StreamObservation.FirstToken -> "FirstToken"
                is StreamObservation.ContentDelta -> "ContentDelta"
                is StreamObservation.UsageChunk ->
                    usage.run { "UsageChunk($promptTokens,$completionTokens,$cachedTokens)" }
                is StreamObservation.Finish -> "Finish($finishReason)"
                is StreamObservation.ErrorFrame -> "ErrorFrame(${error::class.simpleName})"
                StreamObservation.Done -> "Done"
            }

        /** Replay a fixture through the framer + tap, returning the observation tags (incl. the opening). */
        fun observe(fixture: String): List<String> {
            val body = ConformanceFixtures.load(fixture).body
            val parser = SseByteParser()
            val frames = parser.feed(body) + parser.close()
            val tap = TapParser("azure", "gpt-4o", nowMs = { 1_000 })
            return buildList {
                add(tap.opened().tag())
                frames.forEach { f -> tap.onFrame(f).forEach { add(it.tag()) } }
            }
        }

        "done-terminator: Opened → FirstToken → 2× ContentDelta → Finish(stop) → Done" {
            observe("done-terminator") shouldContainExactly
                listOf("Opened", "FirstToken", "ContentDelta", "ContentDelta", "Finish(stop)", "Done")
        }

        "usage-final-chunk: the late usage frame is extracted with cached tokens (Hebe's exact names)" {
            observe("usage-final-chunk") shouldContainExactly
                listOf("Opened", "FirstToken", "ContentDelta", "Finish(stop)", "UsageChunk(11,3,7)", "Done")
        }

        "usage-absent: no UsageChunk observation (settlement falls back to an estimate downstream, D-4)" {
            observe("usage-absent") shouldContainExactly
                listOf("Opened", "FirstToken", "ContentDelta", "Finish(stop)", "Done")
        }

        "toolcall-deltas: FirstToken once, argument fragments counted, Finish(tool_calls)" {
            observe("toolcall-deltas") shouldContainExactly
                listOf(
                    "Opened",
                    "FirstToken",
                    "ContentDelta", // name + empty args
                    "ContentDelta", // args fragment 1
                    "ContentDelta", // args fragment 2
                    "Finish(tool_calls)",
                    "Done",
                )
        }

        "error-frame-midstream: after the first token, the error frame maps through the ErrorConverter" {
            observe("error-frame-midstream") shouldContainExactly
                listOf("Opened", "FirstToken", "ContentDelta", "ErrorFrame(Provider5xx)", "Done")
        }

        "heartbeat-comments: comment frames are ignored — not tokens, not errors" {
            observe("heartbeat-comments") shouldContainExactly
                listOf("Opened", "FirstToken", "ContentDelta", "ContentDelta", "Finish(stop)", "Done")
        }

        "FirstToken fires exactly once and only from the first content-bearing delta" {
            val body = ConformanceFixtures.load("done-terminator").body
            val parser = SseByteParser()
            val frames = parser.feed(body) + parser.close()
            val tap = TapParser("azure", "gpt-4o")
            tap.firstTokenSeen shouldBe false
            val all = frames.flatMap { tap.onFrame(it) }
            tap.firstTokenSeen shouldBe true
            all.count { it is StreamObservation.FirstToken } shouldBe 1
        }
    })
