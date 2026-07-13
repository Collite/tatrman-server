// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo

import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.geo.grpc.GeoGroundingService
import org.tatrman.geo.resolve.PlaceResolution
import org.tatrman.geo.resolve.PlaceResolver
import org.tatrman.geo.resolve.StaticPlaceResolver
import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GetStatusRequest
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundingContext

/**
 * RG-P3.S2.T1 — the RS-19 geo capability posture. A geocoder-backed LOCATION query must NEVER report
 * a false "place doesn't exist" when the geocoder is dark or erroring: it fails loud (gRPC
 * UNAVAILABLE) so the caller retries, and GetStatus reports the `nominatim` + `postgis` capabilities
 * truthfully (a dark geocoder carries RG-GND-001).
 */
class GeoCapabilityTest :
    StringSpec({

        // A geocoder that is dark / erroring (no endpoint, or 429/5xx) → the resolver's Unavailable.
        val darkResolver = PlaceResolver { _, _ -> PlaceResolution.Unavailable("geocoder down") }

        fun svc(
            resolver: PlaceResolver,
            nominatimConfigured: Boolean = true,
            postgisAvailable: Boolean = false,
        ) = GeoGroundingService(
            FakeMetadataClient.poi("cnc"),
            placeResolver = resolver,
            llmFallback = null,
            nominatimConfigured = nominatimConfigured,
            postgisAvailable = postgisAvailable,
        )

        fun distanceRequest(span: String = "within 20 km of Brno"): GroundRequest =
            GroundRequest
                .newBuilder()
                .setSpanText(span)
                .setKind(EntityKind.LOCATION)
                .setPackage("cnc")
                .setContext(GroundingContext.getDefaultInstance())
                .build()

        "(a) no Nominatim endpoint → GetStatus reports nominatim dark + RG-GND-001" {
            val s =
                runBlocking {
                    svc(
                        darkResolver,
                        nominatimConfigured = false,
                    ).getStatus(GetStatusRequest.getDefaultInstance())
                }
            s.capabilitiesMap["nominatim"] shouldBe "dark"
            s.messagesList.any { it.code == "RG-GND-001" } shouldBe true
        }

        "(a) no Nominatim endpoint → Ground(LOCATION) fails loud UNAVAILABLE, never UNGROUNDABLE" {
            val ex =
                shouldThrow<StatusException> {
                    runBlocking { svc(darkResolver, nominatimConfigured = false).ground(distanceRequest()) }
                }
            ex.status.code shouldBe Status.Code.UNAVAILABLE
        }

        "(b) endpoint configured but 429/5xx (resolver Unavailable) → still UNAVAILABLE; nominatim reports ok" {
            val svc = svc(darkResolver, nominatimConfigured = true)
            val s = runBlocking { svc.getStatus(GetStatusRequest.getDefaultInstance()) }
            s.capabilitiesMap["nominatim"] shouldBe "ok"
            s.messagesList.any { it.code == "RG-GND-001" } shouldBe false
            val ex = shouldThrow<StatusException> { runBlocking { svc.ground(distanceRequest()) } }
            ex.status.code shouldBe Status.Code.UNAVAILABLE
        }

        "(c) PostGIS surfacing (D-T2): GetStatus.capabilities reports postgis truthfully" {
            val on =
                runBlocking {
                    svc(
                        StaticPlaceResolver.czCities(),
                        postgisAvailable = true,
                    ).getStatus(GetStatusRequest.getDefaultInstance())
                }
            on.capabilitiesMap["postgis"] shouldBe "ok"
            val off =
                runBlocking {
                    svc(
                        StaticPlaceResolver.czCities(),
                        postgisAvailable = false,
                    ).getStatus(GetStatusRequest.getDefaultInstance())
                }
            off.capabilitiesMap["postgis"] shouldBe "absent"
        }
    })
