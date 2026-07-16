// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.resolve

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.geo.geocode.NominatimClient

/**
 * A9.4 — the Nominatim client + geometry + resolver outcomes, over WireMock (no live OSM calls).
 * Covers the single-hit boundary parse (JTS WKT + bbox), the empty → Unknown and multi → Ambiguous
 * branches.
 */
class NominatimResolverSpec :
    StringSpec({
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }
        afterSpec { wm.stop() }

        wm.stubFor(
            get(urlPathEqualTo("/search"))
                .withQueryParam("q", equalTo("Brno"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(BRNO)),
        )
        wm.stubFor(
            get(urlPathEqualTo("/search"))
                .withQueryParam("q", equalTo("Springfield"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(SPRINGFIELD),
                ),
        )
        wm.stubFor(
            get(urlPathEqualTo("/search"))
                .withQueryParam("q", equalTo("Nowhereville"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")),
        )
        wm.stubFor(
            get(urlPathEqualTo("/search"))
                .withQueryParam("q", equalTo("Ratelimited"))
                .willReturn(aResponse().withStatus(429)),
        )

        val resolver = NominatimPlaceResolver(NominatimClient("http://localhost:${wm.port()}", "geo-test/1.0"))

        "single hit → Found with a JTS-derived boundary (WKT + bbox)" {
            val r = resolver.resolve("Brno", "cnc").shouldBeInstanceOf<PlaceResolution.Found>()
            r.place.lat shouldBe (49.1951 plusOrMinus 1e-4)
            val b = r.place.boundary!!
            b.minLat shouldBe (49.1 plusOrMinus 1e-6)
            b.maxLat shouldBe (49.3 plusOrMinus 1e-6)
            b.minLon shouldBe (16.5 plusOrMinus 1e-6)
            b.maxLon shouldBe (16.7 plusOrMinus 1e-6)
            b.wkt shouldContain "POLYGON"
        }

        "several hits → Ambiguous with candidate options" {
            val r = resolver.resolve("Springfield", "cnc").shouldBeInstanceOf<PlaceResolution.Ambiguous>()
            r.candidates.map { it.id } shouldBe listOf("10", "11")
        }

        "no hits → Unknown" {
            resolver.resolve("Nowhereville", "cnc") shouldBe PlaceResolution.Unknown
        }

        "a 429 / outage → Unavailable (not Unknown — the place may well exist)" {
            resolver.resolve("Ratelimited", "cnc").shouldBeInstanceOf<PlaceResolution.Unavailable>()
        }

        "resolveChoice picks the chosen ambiguous candidate → Found" {
            val r =
                resolver
                    .resolveChoice("Springfield", "cnc", "11")
                    .shouldBeInstanceOf<PlaceResolution.Found>()
            r.place.lat shouldBe (42.1 plusOrMinus 1e-4)
        }

        "resolveChoice with an unknown id → Unknown" {
            resolver.resolveChoice("Springfield", "cnc", "999") shouldBe PlaceResolution.Unknown
        }
    })

private const val BRNO = """
[{"place_id":1,"lat":"49.1951","lon":"16.6068","display_name":"Brno, Jihomoravský kraj, Czechia",
  "geojson":{"type":"Polygon","coordinates":[[[16.5,49.1],[16.7,49.1],[16.7,49.3],[16.5,49.3],[16.5,49.1]]]}}]
"""

private const val SPRINGFIELD = """
[{"place_id":10,"lat":"39.8","lon":"-89.6","display_name":"Springfield, Illinois, USA"},
 {"place_id":11,"lat":"42.1","lon":"-72.5","display_name":"Springfield, Massachusetts, USA"}]
"""
