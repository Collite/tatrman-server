// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import org.tatrman.llmgateway.config.CatalogModel
import org.tatrman.llmgateway.config.Pricing
import org.tatrman.llmgateway.conformance.ConformanceFixtures
import org.tatrman.llmgateway.stream.SseByteParser
import org.tatrman.llmgateway.stream.SseFrame
import org.tatrman.llmgateway.stream.StreamObservation
import org.tatrman.llmgateway.stream.TapParser

/**
 * LG-P2·S2·T5/T6 — the passthrough SSE writer/tee. Proves: the usage chunk is the ONE rewritten frame
 * (§1.3 extension injected, every other frame byte-identical), a mid-stream drop after the first token
 * synthesizes an error frame + `[DONE]` (§1.4), and client disconnect cancels the upstream read (no hang).
 */
class SsePumpSpec :
    StringSpec({

        val model =
            CatalogModel("m", "m", provider = "azure", upstream = "m", type = "chat", pricing = Pricing(2.0, 8.0))

        fun framesOf(fixture: String): List<SseFrame> {
            val body = ConformanceFixtures.load(fixture).body
            val parser = SseByteParser()
            return parser.feed(body) + parser.close()
        }

        /** Run [frames] through the pump; return the exact bytes written to the client + the tapped observations. */
        fun pump(
            frames: Flow<SseFrame>,
            parser: TapParser,
        ): Pair<ByteArray, List<StreamObservation>> =
            runBlocking {
                val channel = ByteChannel(autoFlush = true)
                val observations = mutableListOf<StreamObservation>()
                val reader = async { channel.readRemaining().readByteArray() }
                pumpSse(
                    frames = frames,
                    out = channel,
                    tap = { observations += it },
                    parser = parser,
                    model = model,
                    heartbeatMillis = 0, // no heartbeat noise in these deterministic byte assertions
                )
                channel.flushAndClose()
                reader.await() to observations
            }

        "the usage chunk is rewritten with the §1.3 extension; every other frame is byte-identical" {
            val frames = framesOf("usage-final-chunk")
            val (bytes, obs) = pump(flowOf(*frames.toTypedArray()), TapParser("azure", "gpt-4o"))
            val out = bytes.decodeToString()

            // non-usage frames pass through verbatim
            val contentFrame = frames[0].raw.decodeToString()
            val finishFrame = frames[1].raw.decodeToString()
            out shouldContain contentFrame
            out shouldContain finishFrame
            out shouldContain "data: [DONE]\n\n"

            // the usage frame gained the extension, preserving upstream fields (incl. cached_tokens)
            out shouldContain "\"input_tokens\":11"
            out shouldContain "\"output_tokens\":3"
            out shouldContain "\"cost\":"
            out shouldContain "\"cached_tokens\":7"
            out shouldContain "\"cached\":false"

            obs.any { it is StreamObservation.UsageChunk } shouldBe true
            obs.last() shouldBe StreamObservation.Done
        }

        "a mid-stream drop after the first token synthesizes an error frame + [DONE] (§1.4)" {
            val first = framesOf("done-terminator").first() // one content delta, then the stream just ends
            val parser = TapParser("azure", "gpt-4o")
            val (bytes, obs) = pump(flowOf(first), parser)
            val out = bytes.decodeToString()

            out shouldContain first.raw.decodeToString() // the token that DID arrive passed through
            out shouldContain "\"error\":" // then the synthesized error frame
            out shouldContain "data: [DONE]\n\n" // and a terminator — never a silent stall
            obs.any { it is StreamObservation.ErrorFrame } shouldBe true
            obs.last() shouldBe StreamObservation.Done
        }

        "clean end without the first token closes with [DONE] and no error frame" {
            val (bytes, obs) = pump(flowOf(), TapParser("azure", "gpt-4o"))
            val out = bytes.decodeToString()
            out shouldContain "data: [DONE]\n\n"
            out shouldNotContain "\"error\":"
            obs.none { it is StreamObservation.ErrorFrame } shouldBe true
        }

        "client disconnect cancels the upstream read — the flow is cancelled, no hang" {
            runBlocking {
                val cancelled = CompletableDeferred<Unit>()
                val first = framesOf("done-terminator").first()
                val neverEnds =
                    flow {
                        emit(first)
                        try {
                            awaitCancellation() // model an upstream that keeps the connection open
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                val channel = ByteChannel(autoFlush = true)
                val job =
                    launch {
                        pumpSse(
                            frames = neverEnds,
                            out = channel,
                            tap = {},
                            parser = TapParser("azure", "gpt-4o"),
                            model = model,
                            heartbeatMillis = 0,
                        )
                    }
                delay(100) // let the first frame flow through
                job.cancel() // the client went away
                withTimeout(2_000) { cancelled.await() } // structured concurrency tore the upstream down
            }
        }
    })
