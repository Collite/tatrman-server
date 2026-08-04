// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.parse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.grounding.lexicon.GroundingSlice
import org.tatrman.grounding.lexicon.GroundingTerm
import org.tatrman.grounding.lexicon.TriggerMethod
import org.tatrman.text.Normalization

/**
 * RV-P1.6 T5 (RV-42) — the `ground:geo` slice, and the **parked boundary made executable**.
 *
 * Geo is the odd one of the three kernels twice over:
 *
 *  - It had **no hardcoded category list to replace**. Its parser knows radius units and anchor
 *    prepositions, not category nouns, so this slice is net-new vocabulary rather than a migration.
 *  - The place-name **gazetteer stays geo-side and PARKED**. That is the load-bearing claim here:
 *    a category word may strip a noun off the front of an anchor, and it may do nothing else. No
 *    place resolves differently, and the resolver/geocoder path is not consulted by any of this.
 */
class GroundingSliceCategorySpec :
    StringSpec({

        val parser = GeoSpanParser()

        val categories =
            GroundingSlice(
                kind = "geo",
                terms =
                    listOf("město", "kraj", "region", "okres", "city")
                        .map { GroundingTerm(Normalization.fold(it), it, TriggerMethod.Typos(1), "cs") },
                version = "sha256:test",
            )

        // ---- the parked boundary --------------------------------------------------------------

        "a category word alone is NOT a place — no query, nothing to resolve" {
            parser.parse("město", categories) shouldBe null
            // "kraji" is the only word after the locative and it is a category, so there is no
            // place. Better no query than a geocoder lookup for the word "kraji" — which is
            // exactly what the parser did before the slice.
            parser.parse("v kraji", categories) shouldBe null
            parser.parse("v kraji").shouldBeInstanceOf<GeoQuery.Containment>().place shouldBe "kraji"
        }

        "the slice carries no places, so no span resolves to a place it did not before" {
            // Every place below comes from the span text, exactly as it did pre-RV.
            val withSlice = parser.parse("do 20 km od Brna", categories)
            val without = parser.parse("do 20 km od Brna")

            withSlice shouldBe without
            withSlice.shouldBeInstanceOf<GeoQuery.Distance>().place shouldBe "Brna"
        }

        "cs place connectors still survive — the stop set is untouched" {
            parser
                .parse("v Ústí nad Labem", categories)
                .shouldBeInstanceOf<GeoQuery.Containment>()
                .place shouldBe "Ústí nad Labem"
            // "Újezd u Brna" is NOT asserted here: an anchor preposition with no radius yields
            // no query at all in this parser ("u Brna" is read as an anchor, and a distance query
            // without a radius is not one). That is pre-existing behaviour, unchanged by the
            // slice — the probe below pins it so the omission is deliberate, not an oversight.
            parser.parse("v Újezd u Brna", categories) shouldBe parser.parse("v Újezd u Brna")
        }

        // ---- what a category word actually buys ------------------------------------------------

        "a LEADING category word is stripped off the anchor, so the place is the place" {
            parser
                .parse("v kraji Vysočina", categories)
                .shouldBeInstanceOf<GeoQuery.Containment>()
                .place shouldBe "Vysočina"
            parser
                .parse("ve městě Brno", categories)
                .shouldBeInstanceOf<GeoQuery.Containment>()
                .place shouldBe "Brno"
        }

        "without the slice the category noun stays part of the anchor (the pre-RV parse)" {
            parser
                .parse("v kraji Vysočina")
                .shouldBeInstanceOf<GeoQuery.Containment>()
                .place shouldBe "kraji Vysočina"
        }

        "a category word INSIDE a name is kept — it is far likelier to be part of it" {
            // Only a leading category noun is dropped. "Kraj Vysočina" as an anchor's tail, or a
            // place whose name contains the word, must survive.
            parser
                .parse("v Hradec Kralove kraj", categories)
                .shouldBeInstanceOf<GeoQuery.Containment>()
                .place shouldBe "Hradec Kralove kraj"
        }

        "an empty slice is exactly the no-slice case" {
            parser.parse("v kraji Vysočina", GroundingSlice.empty("geo")) shouldBe
                parser.parse("v kraji Vysočina")
        }
    })
