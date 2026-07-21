// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.tatrman.fuzzy.core.RetrievalMode
import kotlin.math.abs

/**
 * The effort's contract gate. Two modes:
 *  - **LEGACY** (T5, FZ-P0/P1): byte-identical — the engine returns, for every pinned query, the
 *    identical `(candidateId, score)` sequence (ids exact, scores within 1e-9).
 *  - **INDEX_FIRST** (FZ-P2 T5): parity-or-BETTER — every returned candidate's exact-rescored score
 *    is ≥ the legacy 10th; any legacy top-10 candidate we drop is ≤ our 10th (justified displacement);
 *    differences are never worse, only "reaches more". Prints N identical / N better / 0 worse.
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

        "index-first is parity-or-BETTER vs the legacy goldens (0 worse required)" {
            val fixture = PerfFixture.parity(RetrievalMode.INDEX_FIRST)
            var identical = 0
            var better = 0
            val worse = mutableListOf<String>()
            try {
                val byId = goldens.associateBy { it.queryId }
                for (q in PerfFixture.parityQueries()) {
                    val golden = byId[q.id] ?: continue
                    val ours = fixture.matchTatrman(q.query, q.category, limit = 10)
                    val goldenResults = golden.results

                    if (goldenResults.isEmpty()) {
                        if (ours.isEmpty()) {
                            identical++
                        } else {
                            worse +=
                                "[${q.id}] golden empty, index-first returned ${ours.size}"
                        }
                        continue
                    }

                    val golden10 = goldenResults.last().score.toDouble()
                    val our10 = ours.lastOrNull()?.score ?: Double.NEGATIVE_INFINITY
                    val ourById = ours.associate { it.candidateId to it.score }

                    // (a) every returned candidate's exact score ≥ the legacy 10th (− ε).
                    for (o in ours) {
                        if (o.score < golden10 - 1e-9) {
                            worse +=
                                "[${q.id}] ${o.candidateId} exact ${formatGoldenScore(
                                    o.score,
                                )} < legacy-10th ${formatGoldenScore(golden10)}"
                        }
                    }
                    // (b) any legacy top-10 candidate we DROP must be ≤ our 10th (justified displacement).
                    for (g in goldenResults) {
                        if (g.candidateId !in ourById) {
                            val gs = g.score.toDouble()
                            if (gs > our10 + 1e-9) {
                                val our10Str = formatGoldenScore(our10)
                                worse += "[${q.id}] dropped ${g.candidateId} (legacy ${g.score}) > our-10th $our10Str"
                            }
                        }
                    }

                    val idsIdentical = ours.map { it.candidateId } == goldenResults.map { it.candidateId }
                    val scoresIdentical =
                        idsIdentical &&
                            ours.zip(goldenResults).all { (o, g) -> abs(o.score - g.score.toDouble()) < 1e-9 }
                    if (scoresIdentical) identical++ else better++
                }
            } finally {
                fixture.close()
            }
            println(
                "FZ-P2 parity-or-better [index-first]: $identical identical / $better better / ${worse.size} worse " +
                    "(of ${PerfFixture.parityQueries().size} queries; 0 worse required)",
            )
            if (worse.isNotEmpty()) {
                throw AssertionError(
                    "index-first is WORSE than legacy on ${worse.size} case(s):\n" +
                        worse.take(50).joinToString("\n"),
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
