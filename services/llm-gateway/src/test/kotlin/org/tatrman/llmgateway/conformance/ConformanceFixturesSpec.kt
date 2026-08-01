// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.conformance

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * LG-P0·S2·T2 — proves the conformance fixture format loads and the SSE fixtures replay byte-exact
 * through the harness. LG-P2 will bind each fixture's `expected` observation script to the real tap;
 * here we only guarantee the DATA + loader are sound so those suites have solid ground.
 */
class ConformanceFixturesSpec :
    StringSpec({

        "every named fixture loads with well-formed metadata" {
            ConformanceFixtures.ALL.forEach { name ->
                withClue(name) {
                    val f = ConformanceFixtures.load(name)
                    f.meta.name shouldBe name
                    f.meta.kind shouldBe if (name.endsWith("429") || name.endsWith("529")) "http" else "sse"
                    f.meta.origin shouldBe "synthetic" // hand-authored — must not masquerade as captured
                    f.meta.expected.shouldNotBeEmpty()
                    f.body.size shouldBeGreaterThan 0
                }
            }
        }

        "SSE fixtures replay through the harness byte-for-byte" {
            ConformanceFixtures.ALL
                .map { ConformanceFixtures.load(it) }
                .filter { it.meta.kind == "sse" }
                .forEach { fixture ->
                    withClue(fixture.meta.name) {
                        ConformanceFixtures.sseServer(fixture).use { server ->
                            val body = SseTestClient.readBody(server.baseUrl)
                            body.toList() shouldBe fixture.body.toList()
                        }
                    }
                }
        }

        "utf8-split fragments the stream strictly inside a multibyte codepoint" {
            val fixture = ConformanceFixtures.load("utf8-split")
            val offsets = fixture.resolvedSplitOffsets()
            offsets.shouldNotBeEmpty()
            // the resolved split byte is a UTF-8 continuation byte (10xxxxxx) — i.e. mid-codepoint
            offsets.forEach { off -> (fixture.body[off].toInt() and 0xC0) shouldBe 0x80 }
        }

        "http error fixtures declare a status and an expected GatewayError mapping" {
            ConformanceFixtures.ALL
                .map { ConformanceFixtures.load(it) }
                .filter { it.meta.kind == "http" }
                .forEach { fixture ->
                    withClue(fixture.meta.name) {
                        fixture.meta.httpStatus shouldNotBe null
                        fixture.meta.expectedError shouldNotBe null
                        // the raw response's status line must agree with the declared status
                        val statusLine =
                            fixture.body
                                .toString(Charsets.UTF_8)
                                .lineSequence()
                                .first()
                        statusLine.contains(fixture.meta.httpStatus.toString()) shouldBe true
                    }
                }
        }
    })
