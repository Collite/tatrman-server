package org.tatrman.geo.parse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class GeoSpanParserSpec :
    StringSpec({
        val p = GeoSpanParser()

        "en distance 'within 20 km of Brno' → Distance(Brno, 20000 m)" {
            val q = p.parse("within 20 km of Brno").shouldBeInstanceOf<GeoQuery.Distance>()
            q.place shouldBe "Brno"
            q.radiusMeters shouldBe 20000.0
            q.here shouldBe false
        }

        "cs distance 'do 20 km od Brna' keeps the declined place verbatim for the resolver" {
            val q = p.parse("do 20 km od Brna").shouldBeInstanceOf<GeoQuery.Distance>()
            q.place shouldBe "Brna"
            q.radiusMeters shouldBe 20000.0
        }

        "metres and cs decimal comma: '1,5 km' and '500 m'" {
            p.parse("within 1,5 km of Praha").shouldBeInstanceOf<GeoQuery.Distance>().radiusMeters shouldBe 1500.0
            p.parse("500 m from Praha").shouldBeInstanceOf<GeoQuery.Distance>().radiusMeters shouldBe 500.0
        }

        "'within 5 km of here' → Distance(here)" {
            val q = p.parse("within 5 km of here").shouldBeInstanceOf<GeoQuery.Distance>()
            q.here shouldBe true
            q.radiusMeters shouldBe 5000.0
        }

        "containment 'in Brno' (no radius) → Containment(Brno)" {
            p.parse("in Brno").shouldBeInstanceOf<GeoQuery.Containment>().place shouldBe "Brno"
        }

        "cs containment 've Zlíně' → Containment" {
            p.parse("ve Zlíně").shouldBeInstanceOf<GeoQuery.Containment>().place shouldBe "Zlíně"
        }

        "trailing clause after the place is dropped ('in Brno paid in March' → Brno)" {
            p.parse("in Brno paid in March").shouldBeInstanceOf<GeoQuery.Containment>().place shouldBe "Brno"
        }

        "distance place stops at a trailing clause ('within 5 km of Brno open now' → Brno)" {
            p.parse("within 5 km of Brno open now").shouldBeInstanceOf<GeoQuery.Distance>().place shouldBe "Brno"
        }

        "a multi-word place with a cs connector survives ('od Újezd u Brna')" {
            p.parse("do 5 km od Újezd u Brna").shouldBeInstanceOf<GeoQuery.Distance>().place shouldBe "Újezd u Brna"
        }

        "no place, no radius → null" {
            p.parse("somewhere out there").shouldBeNull()
        }

        "empty span → null" {
            p.parse("   ").shouldBeNull()
        }
    })
