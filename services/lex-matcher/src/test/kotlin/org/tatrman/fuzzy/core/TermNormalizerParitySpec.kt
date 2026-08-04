// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.lexicon.TermNormalizer

/**
 * RV-P1.4 T4 — holds the engine's authored form to the toolchain's, on every build.
 *
 * T1's finding #4 flagged the hazard: the engine folds (diacritics stripped, for recall) while the
 * lexicon compiler normalizes (diacritics preserved, the authored word). `EXACT` and `TYPOS(n)`
 * dispatch compare a live query against strings the *compiler* wrote into the archive, so the two
 * sides must agree character for character — if they drift, `EXACT` quietly stops matching terms
 * that are, in the archive, identical to the query.
 *
 * They cannot literally be one function: `TermNormalizer` ships in `org.tatrman:ttr-lexicon`
 * (toolchain repo) and `Normalization` is a server lib, and the dependency runs server → toolchain,
 * never back. This spec is the substitute — it turns "two copies that could drift" into "two copies
 * CI proves identical", and it is the reason `MethodDispatcher` may safely call the local one.
 *
 * This is also the only place in the build where both are on the classpath, which is why it lives
 * in the service rather than in `lex-matcher-core`.
 */
class TermNormalizerParitySpec :
    StringSpec({

        // Pre-composed (U+00E1) vs decomposed (a + U+0301) — one word, two encodings. The NFC step
        // is what makes them one term, and it is the step most likely to be lost to a tidy-up.
        val composed = "\u00E1kord"
        val decomposed = "a\u0301kord"

        val cases =
            listOf(
                "zákazník",
                "Zákazník",
                "  Čistý   Obrat  ",
                "čistý\tobrat",
                "net revenue",
                "NET REVENUE",
                "výroba",
                "vyroba",
                "Kč",
                "ŠKODA AUTO a.s.",
                "obrat\n\ncelkem",
                "",
                "   ",
                "ß",
                "İstanbul",
                composed,
                decomposed,
            )

        cases.forEachIndexed { index, input ->
            "the engine's canonical form matches TermNormalizer for case $index" {
                TextNormalizer.canonical(input) shouldBe TermNormalizer.normalize(input)
            }
        }

        "NFC is actually applied — the composed and decomposed spellings land on one term" {
            TextNormalizer.canonical(composed) shouldBe TextNormalizer.canonical(decomposed)
        }

        "the two forms are genuinely different rules — this spec is not asserting a tautology" {
            // If canonical() ever became fold(), every case above would still pass while EXACT
            // dispatch silently gained the diacritic tolerance the author declined.
            (TextNormalizer.canonical("výroba") == TextNormalizer.fold("výroba")) shouldBe false
        }
    })
