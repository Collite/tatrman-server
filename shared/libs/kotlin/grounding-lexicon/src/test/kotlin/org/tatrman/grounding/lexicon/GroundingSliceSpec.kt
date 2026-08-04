// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.lexicon

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * RV-P1.6 T4 (RV-42) — the trigger-vocabulary contract.
 *
 * What is asserted here is deliberately narrow: whether a span carries a trigger, under the RV-32
 * method its author declared. What the span MEANS is the kernel's, and no test here touches it.
 */
class GroundingSliceSpec :
    StringSpec({

        fun slice(vararg terms: Pair<String, TriggerMethod>) =
            GroundingSlice(
                kind = "chrono",
                terms =
                    terms.map { (text, method) ->
                        GroundingTerm(
                            org.tatrman.text.Normalization
                                .fold(text),
                            text,
                            method,
                            "cs",
                        )
                    },
                version = "sha256:test",
            )

        "EXACT fires on a whole word only" {
            val s = slice("Kč" to TriggerMethod.Exact)
            s.matches("nad 100 Kč") shouldBe true
            s.matches("100 kč") shouldBe true // folding is case+diacritic insensitive
            s.matches("kčokoliv") shouldBe false // NOT a substring match
        }

        "TYPOS(1) covers a one-edit case form" {
            val s = slice("měsíc" to TriggerMethod.Typos(1))
            s.matches("minulý měsíc") shouldBe true
            s.matches("za měsíce") shouldBe true // one edit
            s.matches("v měsících") shouldBe false // three edits — the kernel's stem still owns this
            s.matches("tržby") shouldBe false
        }

        "TOKENS fires when every token appears, in any order and separated" {
            val s = slice("fiskální rok" to TriggerMethod.Tokens)
            s.matches("ve fiskálním roce 2026") shouldBe false // the tokens are inflected, not present
            s.matches("fiskální rok 2026") shouldBe true
            s.matches("rok fiskální") shouldBe true // order-free
            s.matches("fiskální čtvrtletí") shouldBe false // one token missing
        }

        "an empty slice matches nothing and never throws" {
            GroundingSlice.empty("chrono").matches("cokoliv") shouldBe false
            GroundingSlice.empty("chrono").isEmpty shouldBe true
        }

        "an empty or punctuation-only span matches nothing" {
            val s = slice("rok" to TriggerMethod.Typos(1))
            s.matches("") shouldBe false
            s.matches("   ") shouldBe false
            s.matches("!!!") shouldBe false
        }

        "the matched term is reported, so a caller can annotate with it" {
            val s = slice("rok" to TriggerMethod.Typos(1), "měsíc" to TriggerMethod.Typos(1))
            s.matched("minulý měsíc")?.text shouldBe "měsíc"
            s.matched("žádný trigger") shouldBe null
        }

        "an unrecognised method degrades to EXACT rather than failing the load" {
            TriggerMethod.parse("EXACT") shouldBe TriggerMethod.Exact
            TriggerMethod.parse("TOKENS") shouldBe TriggerMethod.Tokens
            TriggerMethod.parse("TYPOS(2)") shouldBe TriggerMethod.Typos(2)
            TriggerMethod.parse("SEMANTIC(0.8)") shouldBe TriggerMethod.Exact
        }

        "the bounded edit distance agrees with Levenshtein on the cases that matter" {
            withinDistance("rok", "rok", 1) shouldBe true
            withinDistance("roku", "rok", 1) shouldBe true
            withinDistance("rocích", "rok", 1) shouldBe false
            withinDistance("mesic", "mesice", 1) shouldBe true
            // A length gap wider than the budget short-circuits before any work.
            withinDistance("a", "abcdefgh", 2) shouldBe false
        }

        "a disabled source serves an empty slice and refreshing is a no-op" {
            val source = GroundingSliceSource.disabled("chrono")
            source.current().isEmpty shouldBe true
            source.refresh().isEmpty shouldBe true
        }
    })
