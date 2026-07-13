package org.tatrman.geo

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GetStatusRequest
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.geo.discover.EmptyGeoDiscovery
import org.tatrman.geo.grpc.GeoGroundingService
import org.tatrman.geo.resolve.StaticPlaceResolver

/**
 * A9.2 scaffold DoD — the service boots, GetStatus answers, and the not-yet-implemented Ground
 * path degrades to UNGROUNDABLE (never throws). Place-resolver / recipe specs (A9.3+) supersede
 * the Ground assertion as the corpus turns green.
 */
class GeoScaffoldSpec :
    StringSpec({
        val svc =
            GeoGroundingService(
                EmptyGeoDiscovery,
                placeResolver = StaticPlaceResolver(emptyMap()),
                llmFallback = null,
            )

        "GetStatus reports service=geo, ready, and capability flags" {
            val s = svc.getStatus(GetStatusRequest.getDefaultInstance())
            s.service shouldBe "geo"
            s.ready shouldBe true
            s.capabilitiesMap["metadata"] shouldBe "ok"
            s.capabilitiesMap["llm_fallback"] shouldBe "false"
        }

        "Ground on a location span degrades to UNGROUNDABLE (scaffold)" {
            val r =
                svc.ground(
                    GroundRequest
                        .newBuilder()
                        .setSpanText("within 20 km of Brno")
                        .setKind(EntityKind.LOCATION)
                        .build(),
                )
            r.status shouldBe GroundResponse.Status.UNGROUNDABLE
        }
    })
