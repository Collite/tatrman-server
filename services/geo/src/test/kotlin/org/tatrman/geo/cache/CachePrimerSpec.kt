// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.cache

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.tatrman.geo.geocode.NominatimClient
import org.tatrman.geo.resolve.NominatimPlaceResolver
import org.tatrman.geo.resolve.PlaceResolution

/**
 * RG-P3.S2.T2 — install-time boundary-cache priming. After a priming run warms the store, the place
 * resolves from the cache even when Nominatim is unreachable; an un-primed place still fails loud
 * (proving the primed hit came from the cache, not a live call).
 */
class CachePrimerSpec :
    StringSpec({
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }
        afterSpec { wm.stop() }

        wm.stubFor(
            get(urlPathEqualTo("/search"))
                .withQueryParam("q", equalTo("Brno"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(BRNO)),
        )

        "priming warms the cache; the primed place then resolves with Nominatim unreachable" {
            val store = InMemoryBoundaryStore()

            // 1) prime against the live (WireMock) geocoder — reuses the resolver's geocode+store path.
            val onlineResolver = NominatimPlaceResolver(NominatimClient(wm.baseUrl(), "tatrman-geo-test/1.0"), store)
            val report = runBlocking { CachePrimer(onlineResolver).prime(listOf("Brno")) }
            report.primed shouldBe 1
            runBlocking { store.get("Brno") }.shouldNotBeNull()

            // 2) a resolver whose geocoder is UNREACHABLE, over the SAME primed store.
            val offlineResolver =
                NominatimPlaceResolver(NominatimClient("http://127.0.0.1:1", "tatrman-geo-test/1.0"), store)

            // the primed place resolves from the cache — the dead geocoder is never reached.
            val hit = runBlocking { offlineResolver.resolve("Brno", "cnc") }
            hit.shouldBeInstanceOf<PlaceResolution.Found>()
            hit.place.label shouldBe "Brno, Jihomoravský kraj, Czechia"

            // an UN-primed place hits the dead geocoder → Unavailable (so the hit above was the cache).
            runBlocking { offlineResolver.resolve("Praha", "cnc") }
                .shouldBeInstanceOf<PlaceResolution.Unavailable>()
        }
    })

private const val BRNO = """
[{"place_id":1,"lat":"49.1951","lon":"16.6068","display_name":"Brno, Jihomoravský kraj, Czechia",
  "geojson":{"type":"Polygon","coordinates":[[[16.5,49.1],[16.7,49.1],[16.7,49.3],[16.5,49.3],[16.5,49.1]]]}}]
"""
