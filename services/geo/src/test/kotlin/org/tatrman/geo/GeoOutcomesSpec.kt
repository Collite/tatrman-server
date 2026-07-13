package org.tatrman.geo

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.GroundingResult
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.geo.client.GatewayResponseFormat
import org.tatrman.geo.client.LlmGatewayClient
import org.tatrman.geo.grpc.GeoGroundingService
import org.tatrman.geo.resolve.Boundary
import org.tatrman.geo.resolve.PlaceCandidate
import org.tatrman.geo.resolve.PlaceResolution
import org.tatrman.geo.resolve.PlaceResolver
import org.tatrman.geo.resolve.ResolvedPlace
import org.tatrman.geo.resolve.StaticPlaceResolver

/**
 * A9.1/A9.5 geo outcomes over the full [GeoGroundingService.ground] pipeline (parser → resolver →
 * recipe), against the in-memory POI [FakeMetadataClient] + a static/ambiguous resolver.
 */
class GeoOutcomesSpec :
    StringSpec({
        fun svc(resolver: PlaceResolver = StaticPlaceResolver.czCities()) =
            GeoGroundingService(FakeMetadataClient.poi("cnc"), placeResolver = resolver, llmFallback = null)

        fun request(
            span: String,
            here: String = "",
            answerId: String = "",
        ): GroundRequest =
            GroundRequest
                .newBuilder()
                .setSpanText(span)
                .setKind(EntityKind.LOCATION)
                .setPackage("cnc")
                .setContext(GroundingContext.newBuilder().setHerePlaceRef(here))
                .apply { if (answerId.isNotEmpty()) clarificationAnswerId = answerId }
                .build()

        "'within 20 km of Brno' → OK distance FilterRecipe" {
            val r = svc().ground(request("within 20 km of Brno"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.result.normalized.point.radiusM shouldBe 20000.0
        }

        "cs 'do 20 km od Brna' resolves the declined place → OK" {
            svc().ground(request("do 20 km od Brna")).status shouldBe GroundResponse.Status.OK
        }

        "'within 5 km of here' uses here_place_ref → OK" {
            svc().ground(request("within 5 km of here", here = "Praha")).status shouldBe GroundResponse.Status.OK
        }

        "'near here' with no radius → UNGROUNDABLE (needs a radius)" {
            svc().ground(request("near here", here = "Praha")).status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "unknown place → UNGROUNDABLE" {
            svc().ground(request("within 20 km of Atlantis")).status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "containment 'in Brno' with a boundary-less resolver → UNGROUNDABLE (no polygon)" {
            // czCities() resolves to a point but carries no boundary → containment can't build a bbox.
            svc().ground(request("in Brno")).status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "containment 'in Brno' with a boundary → OK bbox FilterRecipe + shape" {
            val withBoundary =
                PlaceResolver { _, _ ->
                    PlaceResolution.Found(
                        ResolvedPlace(
                            "Brno",
                            49.19,
                            16.60,
                            boundary =
                                Boundary(
                                    "POLYGON ((16.5 49.1, 16.7 49.1, 16.7 49.3, 16.5 49.3, 16.5 49.1))",
                                    49.1,
                                    16.5,
                                    49.3,
                                    16.7,
                                ),
                        ),
                    )
                }
            val r = svc(withBoundary).ground(request("in Brno"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.result.normalized.shape.wkt shouldContain "POLYGON"
        }

        "ambiguous place → AWAITING_CLARIFICATION with candidate options" {
            val ambiguous =
                PlaceResolver { _, _ ->
                    PlaceResolution.Ambiguous(
                        listOf(
                            PlaceCandidate("cz-1", "Újezd u Brna", 49.04, 16.77),
                            PlaceCandidate("cz-2", "Újezd nad Lesy", 50.08, 14.66),
                        ),
                    )
                }
            val r = svc(ambiguous).ground(request("within 10 km of Újezd"))
            r.status shouldBe GroundResponse.Status.AWAITING_CLARIFICATION
            r.optionsList.map { it.id } shouldBe listOf("cz-1", "cz-2")
            r.optionsList[0]
                .normalized.point.lat shouldBe 49.04
        }

        "a clarification answer resolves the chosen candidate → OK (no re-clarification loop)" {
            // Ambiguous on the first call; on resume, resolveChoice returns the chosen place.
            val disambiguating =
                object : PlaceResolver {
                    override suspend fun resolve(
                        name: String,
                        pkg: String,
                    ) = PlaceResolution.Ambiguous(
                        listOf(
                            PlaceCandidate("cz-1", "Újezd u Brna", 49.04, 16.77),
                            PlaceCandidate("cz-2", "Újezd nad Lesy", 50.08, 14.66),
                        ),
                    )

                    override suspend fun resolveChoice(
                        name: String,
                        pkg: String,
                        chosenId: String,
                    ) = PlaceResolution.Found(ResolvedPlace("Újezd u Brna", 49.04, 16.77))
                }
            val r = svc(disambiguating).ground(request("within 10 km of Újezd", answerId = "cz-1"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
        }

        "a geocoder outage → gRPC UNAVAILABLE (fails loud, not a false 'not found')" {
            val downResolver = PlaceResolver { _, _ -> PlaceResolution.Unavailable("nominatim 503") }
            val ex =
                shouldThrow<StatusException> {
                    svc(downResolver).ground(request("within 20 km of Brno"))
                }
            ex.status.code shouldBe Status.UNAVAILABLE.code
        }

        // ----- llm-gateway fallback (A9.6) -----

        "an unrecognized span with no fallback client → UNGROUNDABLE" {
            svc().ground(request("qwerty nonsense")).status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "an unrecognized span with a fallback client → OK, source = LLM" {
            val gateway = FakeLlmGateway(VALID_LLM_RESULT)
            val svc =
                GeoGroundingService(
                    FakeMetadataClient.poi("cnc"),
                    placeResolver = StaticPlaceResolver.czCities(),
                    llmFallback = gateway,
                )
            val r = svc.ground(request("somewhere in the vicinity of the old mill"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.source shouldBe GroundingResult.Source.LLM
            gateway.lastUserPrompt!!.contains("old mill") shouldBe true
        }

        "a fallback client returning invalid JSON → UNGROUNDABLE (not a crash)" {
            val svc =
                GeoGroundingService(
                    FakeMetadataClient.poi("cnc"),
                    placeResolver = StaticPlaceResolver.czCities(),
                    llmFallback = FakeLlmGateway("not json"),
                )
            svc.ground(request("qwerty nonsense")).status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "D-T4: a rules-hit with a gateway present stays RULES and never calls the LLM" {
            val gateway = FakeLlmGateway(VALID_LLM_RESULT)
            val svc =
                GeoGroundingService(
                    FakeMetadataClient.poi("cnc"),
                    placeResolver = StaticPlaceResolver.czCities(),
                    llmFallback = gateway,
                )
            val r = svc.ground(request("within 20 km of Brno"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.source shouldBe GroundingResult.Source.RULES
            gateway.lastUserPrompt.shouldBeNull() // fallback fires only on a rules-miss / low-confidence
        }
    })

private class FakeLlmGateway(
    private val response: String,
) : LlmGatewayClient {
    var lastUserPrompt: String? = null

    override suspend fun chat(
        model: String,
        system: String,
        user: String,
        responseFormat: GatewayResponseFormat?,
    ): String {
        lastUserPrompt = user
        return response
    }

    override fun close() {}
}

// Minimal structurally-valid GroundingResult (proto JSON): normalized.point + a filter + sql_preview.
private const val VALID_LLM_RESULT = """
{
  "normalized": { "point": { "lat": 49.1951, "lon": 16.6068, "radiusM": 20000 } },
  "filter": {},
  "sqlPreview": "geo_distance_m(t.\"lat\", t.\"lon\", {lat}, {lon}) <= {r}",
  "confidence": 0.5,
  "explanation": "LLM-grounded near Brno."
}
"""
