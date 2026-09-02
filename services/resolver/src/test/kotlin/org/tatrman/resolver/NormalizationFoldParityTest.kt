// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.text.Normalization

/**
 * MH (plan risk 1) — the **third** reader of one parity table.
 *
 * The collision lint asks "would these two declarations claim the same word at runtime?", and the
 * only honest answer comes from the function that builds the anchor index the matcher queries:
 * `Normalization.fold`, here. The other two implementations —
 * `@tatrman/semantics` `foldForCollision` and `ttr-lexicon` `TermNormalizer.fold` — exist so the
 * question can be asked at authoring time, in two toolchains, and they are only useful while all
 * three agree. Each side holds itself to the SAME table; this is the copy that pins the one that
 * actually decides.
 *
 * **Whitespace is compared after the split, deliberately.** `Normalization.fold` neither trims nor
 * collapses runs — it does not need to, because `SpanProposal` splits a folded anchor on `' '` and
 * drops the blanks (`fold(anchor).split(' ').filter { it.isNotBlank() }`) before anything is
 * indexed. So the contract the index actually keeps is *"the same WORDS, in the same order"*, and
 * that is what this asserts. A row's expected value is the tatrman fold's output, which trims and
 * collapses; comparing the raw strings would fail on the two whitespace rows for a difference the
 * index cannot see.
 */
class NormalizationFoldParityTest :
    StringSpec({

        val table =
            ObjectMapper()
                .readTree(
                    checkNotNull(
                        NormalizationFoldParityTest::class.java.getResourceAsStream("/mh/fold-parity.json"),
                    ) { "missing /mh/fold-parity.json" },
                )["cases"]
                .map { it[0].asText() to it[1].asText() }

        /** The words the anchor index would key on — `SpanProposal`'s own step, verbatim. */
        fun words(text: String): List<String> = Normalization.fold(text).split(' ').filter { it.isNotBlank() }

        "the parity table is the 12 rows of MH contracts §1" {
            table.size shouldBe 12
        }

        table.forEachIndexed { i, (input, expected) ->
            "row $i — the index keys \"$input\" exactly as the toolchain folds it" {
                words(input) shouldBe words(expected)
            }
        }

        "the diacritic pair the lint exists for is ONE key here" {
            // If this ever stopped holding, the lint would be reporting collisions the matcher
            // never has — and, worse, missing the ones it does.
            words("výroba") shouldBe words("vyroba")
            words("Tržby") shouldBe words("trzby")
        }

        "a precomposed letter with no decomposition survives, as the table says" {
            // Only combining marks are stripped; `Đ`/`Ł` have no canonical decomposition, so a
            // fold that "removed diacritics" by transliteration would disagree with all three.
            words("Đ đ Ł ł") shouldBe listOf("đ", "đ", "ł", "ł")
        }
    })
