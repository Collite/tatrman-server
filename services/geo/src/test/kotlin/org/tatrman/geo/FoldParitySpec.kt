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
 * entrypoint is byte-identical to the shared spec over the golden-style vectors, so
 * a reintroduced private fold (or a drift in the shared spec) turns this red.
 *
 * The golden vectors are canonically `shared/libs/kotlin/ttr-text/.../NormalizationSpec`;
 * a place-name-relevant subset is mirrored here (that module is not importable from a
 * test source set — the documented characterization bridge).
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
    })
