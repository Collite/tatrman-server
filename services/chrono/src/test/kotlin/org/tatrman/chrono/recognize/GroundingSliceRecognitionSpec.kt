// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.tatrman.grounding.lexicon.GroundingSlice
import org.tatrman.grounding.lexicon.GroundingTerm
import org.tatrman.grounding.lexicon.TriggerMethod
import org.tatrman.text.Normalization
import java.time.LocalDate

/**
 * RV-P1.6 T4 (RV-42) — the `ground:chrono` slice, at the recognizer.
 *
 * The two claims that matter, and the order they matter in:
 *
 *  1. **Nothing regresses.** With no slice, every recognition is byte-for-byte what it was — which
 *     is why the whole pre-existing `DateRecognizerSpec` runs against the no-slice default and
 *     stays green untouched. The cases here pin the boundary from the other side.
 *  2. **A slice term grounds what the hardcoded list did not.** "fiskální rok" with no year fell
 *     through before: the fiscal rule needs four digits and the relative rule needs a scope word.
 *     Declared as a trigger, it reads as the fiscal year at the reference.
 *
 * The slice never says what a span MEANS — every interval below is still the kernel's rule.
 */
class GroundingSliceRecognitionSpec :
    StringSpec({

        val recognizer = DateRecognizer()
        val reference = LocalDate.of(2026, 5, 12)

        fun slice(vararg terms: Pair<String, TriggerMethod>) =
            GroundingSlice(
                kind = "chrono",
                terms = terms.map { (t, m) -> GroundingTerm(Normalization.fold(t), t, m, "cs") },
                version = "sha256:test",
            )

        val stdlibish =
            slice(
                "rok" to TriggerMethod.Typos(1),
                "měsíc" to TriggerMethod.Typos(1),
                "čtvrtletí" to TriggerMethod.Typos(1),
                "fiskální rok" to TriggerMethod.Tokens,
            )

        // ---- 1. no slice ⇒ the pre-RV recognizer ------------------------------------------------

        "without a slice, a scopeless mention still falls through (the pre-RV behaviour)" {
            recognizer.recognize("fiskální rok", reference) shouldBe null
            recognizer.recognize("rok", reference) shouldBe null
            recognizer.recognize("čtvrtletí", reference) shouldBe null
        }

        "without a slice, every rule that used to fire still fires identically" {
            val scoped = recognizer.recognize("minulý měsíc", reference)
            scoped shouldNotBe null
            scoped!!.kind shouldBe ChronoKind.RELATIVE
            scoped.confidence shouldBe 0.9
            scoped.startInclusive shouldBe LocalDate.of(2026, 4, 1)

            val explicitFiscal = recognizer.recognize("fiskální rok 2026", reference)
            explicitFiscal!!.kind shouldBe ChronoKind.FISCAL_YEAR
            explicitFiscal.confidence shouldBe 0.95
        }

        // ---- 2. a slice term grounds what the list did not --------------------------------------

        "a declared `fiskální rok` grounds as the fiscal year at the reference" {
            val r = recognizer.recognize("fiskální rok", reference, stdlibish)

            r shouldNotBe null
            r!!.kind shouldBe ChronoKind.FISCAL_YEAR
            r.startInclusive shouldBe LocalDate.of(2026, 1, 1)
            r.endExclusive shouldBe LocalDate.of(2027, 1, 1)
            // Inferred scope is weaker evidence than an authored "tento"/"minulý" — but above
            // chrono's 0.6 clarification floor, because the estate declared the word.
            r.confidence shouldBe 0.8
        }

        "a declared bare `měsíc` reads as the current month, with a period code" {
            val r = recognizer.recognize("měsíc", reference, stdlibish)

            r!!.startInclusive shouldBe LocalDate.of(2026, 5, 1)
            r.endExclusive shouldBe LocalDate.of(2026, 6, 1)
            r.periodCode shouldBe "202605"
            r.kind shouldBe ChronoKind.RELATIVE
        }

        "a scope word still wins over the inferred one" {
            val r = recognizer.recognize("minulý rok", reference, stdlibish)

            r!!.startInclusive shouldBe LocalDate.of(2025, 1, 1)
            r.confidence shouldBe 0.9 // authored scope, not inferred
        }

        "an explicit year still wins over the trigger reading" {
            val r = recognizer.recognize("fiskální rok 2024", reference, stdlibish)

            r!!.startInclusive shouldBe LocalDate.of(2024, 1, 1)
            r.confidence shouldBe 0.95
        }

        "a trigger does not make a NON-time span groundable" {
            // The slice answers "is this span mine?", not "what does it mean?". A span that
            // carries a trigger but names no unit the kernel knows still falls through, because
            // there is no rule to read it with.
            recognizer.recognize("tržby podle prodejen", reference, stdlibish) shouldBe null
        }

        "an empty slice is exactly the no-slice case" {
            recognizer.recognize("fiskální rok", reference, GroundingSlice.empty("chrono")) shouldBe null
        }

        // ---- quarter: the third scopeless period, and the one the KDoc promised ----------------

        "a declared bare `čtvrtletí` reads as the current quarter, with a period code" {
            val r = recognizer.recognize("čtvrtletí", reference, stdlibish)

            r shouldNotBe null
            r!!.kind shouldBe ChronoKind.PERIOD
            r.startInclusive shouldBe LocalDate.of(2026, 4, 1) // the reference's own quarter
            r.endExclusive shouldBe LocalDate.of(2026, 7, 1)
            r.periodCode shouldBe "2026Q2"
            r.confidence shouldBe 0.8 // inferred scope, same rung as a scopeless month or year
        }

        "an authored quarter scope still wins, at full confidence" {
            val r = recognizer.recognize("poslední čtvrtletí", reference, stdlibish)

            r!!.periodCode shouldBe "2026Q1"
            r.confidence shouldBe 0.9
        }

        "an AMBIGUOUS quarter scope is still not a match, trigger or no trigger" {
            recognizer.recognize("tento poslední čtvrtletí", reference, stdlibish) shouldBe null
        }

        "a scopeless week is scored like the other inferred periods, not above them" {
            val r = recognizer.recognize("týden", reference, slice("týden" to TriggerMethod.Typos(1)))

            r!!.confidence shouldBe 0.8
        }
    })
