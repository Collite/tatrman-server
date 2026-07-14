// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.geo.cache.foldPlaceKey
import org.tatrman.text.Normalization

/**
 * RG-P6.S2.T4 — the S-2 fold audit, geo call sites. Geo used to keep private fold
 * copies (`PlaceResolver.fold`, `BoundaryStore.foldPlaceKey`, this spec's own
 * `fold`); they are now the one shared spec (`org.tatrman.text.Normalization.fold`),
 * with `trim()` as geo's visible input cleanup. This locks that in: every geo fold
 * entrypoint is byte-identical to the shared spec over these vectors, so a
 * reintroduced private fold (or a drift in the shared spec) turns this red.
 *
 * SCOPE (RG-P6 review N): the old private folds stripped `\p{M}` (all combining
 * marks) while the shared spec strips `\p{Mn}` (non-spacing marks) — the two agree
 * for Latin/Czech diacritics (all `Mn`), which is what the geo corpus is, but would
 * diverge for a script with spacing/enclosing marks (`Mc`/`Me`, e.g. Indic vowel
 * signs). The `Mn`-scope case below pins that boundary so any future widening back
 * to `\p{M}` is a conscious, tested change, not silent drift. The golden vectors are
 * canonically `shared/libs/kotlin/ttr-text/.../NormalizationSpec`; a place-name subset
 * is mirrored here (that module is not importable from a test source set — the
 * documented characterization bridge).
 */
class FoldParitySpec :
    StringSpec({

        // (input, expected fold) — the canonical S-2 result for each.
        val golden =
            listOf(
                "Brno" to "brno",
                "BRNO" to "brno",
                "brno" to "brno",
                "Praha" to "praha",
                "České Budějovice" to "ceske budejovice",
                "Plzeň" to "plzen",
                "Žďár" to "zdar",
                "Zákazník" to "zakaznik",
            )

        "the shared spec folds the golden vectors canonically (lower → NFD → strip marks)" {
            for ((input, expected) in golden) Normalization.fold(input) shouldBe expected
        }

        "geo foldPlaceKey is byte-identical to the shared spec on trimmed input (no private fold)" {
            for ((input, expected) in golden) {
                foldPlaceKey(input) shouldBe expected
                foldPlaceKey("  $input  ") shouldBe expected // trim is geo's pre-step, the fold is shared
                foldPlaceKey(input) shouldBe Normalization.fold(input.trim())
            }
        }

        "case + diacritics collapse to one key (the geo cache invariant)" {
            foldPlaceKey("Brno") shouldBe foldPlaceKey("brno")
            foldPlaceKey("Plzeň") shouldBe foldPlaceKey("plzen")
        }

        // Pin the shared spec's mark-class scope (review N): it strips \p{Mn}
        // (non-spacing marks — the Czech diacritics), so a bare combining acute (U+0301,
        // Mn) folds away. If the spec ever widens back to \p{M}, this case is where that
        // shows up — geo's Czech corpus is unaffected either way.
        "the shared fold strips non-spacing (Mn) combining marks" {
            Normalization.fold("é") shouldBe "e" // e + combining acute → e
            Normalization.fold("Plzeň") shouldBe "plzen" // decomposed ň (n + caron)
        }
    })
