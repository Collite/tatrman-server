// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.GroundingResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.geo.grpc.GeoGroundingService
import org.tatrman.geo.parse.GeoQuery
import org.tatrman.geo.recipe.GeoRecipeBuilder
import org.tatrman.geo.resolve.ChainedPlaceResolver
import org.tatrman.geo.resolve.ModelPoiResolver
import org.tatrman.geo.resolve.PlaceResolution
import org.tatrman.geo.resolve.PlaceResolver
import org.tatrman.geo.resolve.ResolvedPlace

/**
 * A9 POI-in-model layer — an anchor that names a POI in the model is grounded by a JoinRecipe on
 * `poi.name = {place}` (coordinates read at query time), not literal coordinates. Covers the
 * resolver, the geocode→POI chain ordering, the recipe shape, and the service outcomes.
 */
class ModelPoiSpec :
    StringSpec({
        val unknownResolver = PlaceResolver { _, _ -> PlaceResolution.Unknown }
        val foundResolver = PlaceResolver { _, _ -> PlaceResolution.Found(ResolvedPlace("Brno", 49.2, 16.6)) }

        "ModelPoiResolver → ModelPoi when the package has a POI entity" {
            val r =
                ModelPoiResolver(FakeMetadataClient.poi("cnc"))
                    .resolve("Central Depot", "cnc")
                    .shouldBeInstanceOf<PlaceResolution.ModelPoi>()
            r.poi.entityName shouldBe "Store"
            r.poi.nameColumn shouldBe "store_name"
            r.poi.placeName shouldBe "Central Depot"
        }

        "ModelPoiResolver → Unknown when the package has no POI entity" {
            ModelPoiResolver(FakeMetadataClient.noPoi("cnc"))
                .resolve("Central Depot", "cnc") shouldBe PlaceResolution.Unknown
        }

        "chain resolves via the POI fallback only when the geocoder misses" {
            val poiResolver = ModelPoiResolver(FakeMetadataClient.poi("cnc"))
            ChainedPlaceResolver(listOf(unknownResolver, poiResolver))
                .resolve("Central Depot", "cnc")
                .shouldBeInstanceOf<PlaceResolution.ModelPoi>()
            // a geocodable place resolves first — the POI resolver is never consulted
            ChainedPlaceResolver(listOf(foundResolver, poiResolver))
                .resolve("Brno", "cnc")
                .shouldBeInstanceOf<PlaceResolution.Found>()
        }

        "buildDistanceToPoi → JoinRecipe on poi.name = {place} filtering geo_distance_m" {
            val poi =
                ModelPoiResolver(FakeMetadataClient.poi("cnc"))
                    .resolve("Central Depot", "cnc")
                    .shouldBeInstanceOf<PlaceResolution.ModelPoi>()
                    .poi
            val query =
                GeoQuery.Distance(
                    place = "Central Depot",
                    here = false,
                    radiusMeters = 20000.0,
                    confidence = 0.9,
                )
            val g =
                GeoRecipeBuilder(FakeMetadataClient.poi("cnc"))
                    .buildDistanceToPoi(query, poi, "cnc")!!

            g.applicationCase shouldBe GroundingResult.ApplicationCase.JOIN
            g.join.suggestedAlias shouldBe "poi"
            g.join.onCondition.function.operation shouldBe "eq"
            g.join.filter.function.operation shouldBe "le"
            g.join.parametersList.map { it.name } shouldBe listOf("place", "r")
            g.join.parametersList
                .first { it.name == "place" }
                .value.stringValue shouldBe "Central Depot"
            g.sqlPreview shouldContain "JOIN \"Store\" AS poi ON poi.\"store_name\" = {place}"
            g.sqlPreview shouldContain "geo_distance_m(t.\"lat\", t.\"lon\", poi.\"lat\", poi.\"lon\") <= {r}"
            g.normalized.point.radiusM shouldBe 20000.0
        }

        "service: a non-geocodable anchor + POI package → OK JoinRecipe" {
            val svc =
                GeoGroundingService(
                    FakeMetadataClient.poi("cnc"),
                    placeResolver =
                        ChainedPlaceResolver(
                            listOf(unknownResolver, ModelPoiResolver(FakeMetadataClient.poi("cnc"))),
                        ),
                    llmFallback = null,
                )
            val r = svc.ground(request("within 20 km of the central depot"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.applicationCase shouldBe GroundingResult.ApplicationCase.JOIN
        }

        "service: containment against a model POI → UNGROUNDABLE (a point has no boundary)" {
            val svc =
                GeoGroundingService(
                    FakeMetadataClient.poi("cnc"),
                    placeResolver = ModelPoiResolver(FakeMetadataClient.poi("cnc")),
                    llmFallback = null,
                )
            svc.ground(request("in the central depot")).status shouldBe GroundResponse.Status.UNGROUNDABLE
        }
    })

private fun request(span: String): GroundRequest =
    GroundRequest
        .newBuilder()
        .setSpanText(span)
        .setKind(EntityKind.LOCATION)
        .setPackage("cnc")
        .setContext(GroundingContext.getDefaultInstance())
        .build()
