// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

/**
 * RG-P2.S1.T5 — the axis-matters proof. The inflection class of the Q-17 corpus
 * runs twice. With the lemma axis ON (fixture lemmatiser) every case resolves
 * AND lands an EXACT lemma match (top-1 score 1.0). With the axis forced OFF
 * (NoopLemmatizer, surface-only) the inflected surface forms score strictly
 * lower — the matcher keeps the better of the surface/lemma axes, so the axis
 * can only raise quality; a strict drop when it's off proves the axis is
 * load-bearing (and guards against a silent regression to surface-only).
 */
class MatchQualityAxisProofTest :
    StringSpec({

        val inflection = loadCorpus().filter { it.cls == "inflection" }

        data class Outcome(
            val correct: Int,
            val exact: Int,
            val scoreSum: Double,
        )

        fun run(lemmatizer: Lemmatizer): Outcome {
            val (repo, matcher) = corpusMatcher(lemmatizer)
            return try {
                runBlocking {
                    repo.forceRefresh()
                    var correct = 0
                    var exact = 0
                    var sum = 0.0
                    for (c in inflection) {
                        val top = matcher.match(c.query, c.category, AlgorithmType.TATRMAN, 5).firstOrNull()
                        if (top?.candidateId == c.expected) correct++
                        if (top != null && top.candidateId == c.expected && top.score >= 0.999) exact++
                        sum += top?.score ?: 0.0
                    }
                    Outcome(correct, exact, sum)
                }
            } finally {
                repo.close()
            }
        }

        "the lemma axis carries the inflection class (exact matches ON; strictly lower quality OFF)" {
            val on = run(FIXTURE_LEMMATIZER)
            val off = run(NoopLemmatizer)

            // With the axis, every inflected case resolves AND lands an exact lemma match.
            on.correct shouldBe inflection.size
            on.exact shouldBe inflection.size
            // The axis is load-bearing: turning it off strictly lowers match quality
            // (fewer exact matches, lower total score) — surface-only can't reach the lemma.
            on.exact shouldBeGreaterThan off.exact
            on.scoreSum shouldBeGreaterThan off.scoreSum
        }
    })
