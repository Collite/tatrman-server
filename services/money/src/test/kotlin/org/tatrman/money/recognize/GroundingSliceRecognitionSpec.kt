// SPDX-License-Identifier: Apache-2.0
package org.tatrman.money.recognize

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.grounding.lexicon.GroundingSlice
import org.tatrman.grounding.lexicon.GroundingTerm
import org.tatrman.grounding.lexicon.TriggerMethod
import org.tatrman.text.Normalization

/**
 * RV-P1.6 T4 (RV-42) — the `ground:money` slice, at the recognizer.
 *
 * Money's payoff from the trigger gate is narrower than chrono's, and deliberately so: chrono can
 * read a bare declared noun as a period, money still needs a magnitude — "tisíc" alone is not an
 * amount. So here a trigger is EVIDENCE, and what it changes is confidence: a plain magnitude
 * scores 0.65, low enough to route to clarification or the LLM fallback, and an estate declaring
 * the surrounding words as its own money vocabulary is exactly the evidence that was missing.
 *
 * As in chrono, an empty slice leaves recognition byte-for-byte as it was — which the whole
 * pre-existing money suite, running against the no-slice default, is the real proof of.
 */
class GroundingSliceRecognitionSpec :
    StringSpec({

        val recognizer = AmountRecognizer()

        fun slice(vararg terms: Pair<String, TriggerMethod>) =
            GroundingSlice(
                kind = "money",
                terms = terms.map { (t, m) -> GroundingTerm(Normalization.fold(t), t, m, "cs") },
                version = "sha256:test",
            )

        val stdlibish =
            slice(
                "Kč" to TriggerMethod.Exact,
                "EUR" to TriggerMethod.Exact,
                "tisíc" to TriggerMethod.Typos(1),
                "korun" to TriggerMethod.Typos(1),
            )

        "without a slice, a plain magnitude keeps its 0.65 confidence" {
            recognizer.recognize("100 000", "cs-CZ")!!.confidence shouldBe 0.65
        }

        "a declared money word raises a plain magnitude above the fallback band" {
            val r = recognizer.recognize("100 000 korun", "cs-CZ", stdlibish)!!

            r.confidence shouldBe 0.8
            // The amount and currency are still the kernel's own parse — the slice added evidence,
            // not interpretation.
            r.amount.toPlainString() shouldBe "100000"
            r.currency shouldBe "CZK"
        }

        "an authored comparator still outranks a trigger" {
            val r = recognizer.recognize("nad 100 000 Kč", "cs-CZ", stdlibish)!!

            r.confidence shouldBe 0.9
            r.comparator shouldBe Comparator.GT
        }

        "an estate-declared currency word the ISO table does not know still lifts confidence" {
            // "šekel" is in no CURRENCY_WORDS regex, so the currency stays unknown (the default
            // applies downstream) — but the span is now known to BE about money.
            val estate = slice("šekel" to TriggerMethod.Typos(1))
            val r = recognizer.recognize("500 šekelů", "cs-CZ", estate)!!

            r.confidence shouldBe 0.8
            r.currency shouldBe null
        }

        "a trigger does not conjure an amount out of a span that has none" {
            // The gate answers "is this span mine?", never "what is the number?".
            recognizer.recognize("tisíc", "cs-CZ", stdlibish) shouldBe null
            recognizer.recognize("v korunách", "cs-CZ", stdlibish) shouldBe null
        }

        "an empty slice is exactly the no-slice case" {
            recognizer
                .recognize("100 000 korun", "cs-CZ", GroundingSlice.empty("money"))!!
                .confidence shouldBe 0.65
        }
    })
