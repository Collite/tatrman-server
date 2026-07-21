// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * T5 — the effort's contract gate. Always on: it loads the committed goldens and asserts the
 * CURRENT engine still returns, for every pinned query, the identical `(candidateId, score)`
 * sequence (candidate ids exact, scores within 1e-9). FZ-P1 must keep this byte-identical; FZ-P2
 * swaps this for the parity-or-better gate under the `index-first` flag.
 *
 * Failure messages carry queryId + position + expected/actual so a regression is diagnosable from
 * the report alone.
 */
class ParityGateSpec :
    StringSpec({
        val goldens: List<GoldenEntry> = loadGoldens()

        "goldens are present and cover the full pinned query set" {
            goldens.isNotEmpty() shouldBe true
            goldens.size shouldBe PerfFixture.parityQueries().size
        }

        "current engine matches the parity goldens exactly (candidate ids + scores to 1e-9)" {
            val fixture = PerfFixture.parity()
            val mismatches = mutableListOf<String>()
            try {
                val byId = goldens.associateBy { it.queryId }
                for (q in PerfFixture.parityQueries()) {
                    val golden =
                        byId[q.id] ?: run {
                            mismatches += "[${q.id}] missing from goldens"
                            continue
                        }
                    val actual = fixture.matchTatrman(q.query, q.category, limit = 10)
                    if (actual.size != golden.results.size) {
                        mismatches +=
                            "[${q.id}] result count: expected ${golden.results.size}, actual ${actual.size} (query='${q.query}', category=${q.category})"
                    }
                    val n = minOf(actual.size, golden.results.size)
                    for (i in 0 until n) {
                        val exp = golden.results[i]
                        val act = actual[i]
                        if (act.candidateId != exp.candidateId) {
                            mismatches +=
                                "[${q.id}] pos $i candidateId: expected ${exp.candidateId}, actual ${act.candidateId}"
                        }
                        val expScore = exp.score.toDouble()
                        if (abs(act.score - expScore) >= 1e-9) {
                            mismatches +=
                                "[${q.id}] pos $i score: expected ${exp.score}, actual ${formatGoldenScore(
                                    act.score,
                                )} (Δ=${abs(
                                    act.score - expScore,
                                )})"
                        }
                    }
                }
            } finally {
                fixture.close()
            }
            if (mismatches.isNotEmpty()) {
                throw AssertionError(
                    "Parity gate FAILED — ${mismatches.size} mismatch(es):\n" +
                        mismatches.take(50).joinToString("\n"),
                )
            }
        }
    })

private fun loadGoldens(): List<GoldenEntry> {
    val stream =
        ParityGateSpec::class.java.getResourceAsStream("/perf/parity-goldens.json")
            ?: error(
                "Missing /perf/parity-goldens.json on the test classpath. Regenerate it:\n" +
                    "  ./gradlew :services:ttr-fuzzy:test --tests '*GoldenCaptureTest*' -DregenGoldens=true",
            )
    val text = stream.bufferedReader().use { it.readText() }
    return Json.decodeFromString(text)
}
