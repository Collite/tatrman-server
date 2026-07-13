package org.tatrman.geo.recipe

import org.tatrman.grounding.v1.GroundingResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.geo.FakeMetadataClient
import org.tatrman.geo.parse.GeoQuery
import org.tatrman.geo.resolve.Boundary
import org.tatrman.geo.resolve.ResolvedPlace

class GeoRecipeBuilderSpec :
    StringSpec({
        val brno = ResolvedPlace("Brno", 49.1951, 16.6068)
        val query = GeoQuery.Distance(place = "Brno", here = false, radiusMeters = 20000.0, confidence = 0.9)

        "distance → FilterRecipe over geo_distance_m against the POI's lat/lon" {
            val builder = GeoRecipeBuilder(FakeMetadataClient.poi("cnc"))
            val r = builder.buildDistance(query, brno, "cnc").shouldNotBeNull()

            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.filter.condition.function.operation shouldBe "le"
            r.filter.condition.function
                .getOperands(0)
                .function.operation shouldBe "geo_distance_m"
            r.filter.parametersList.map { it.name } shouldBe listOf("lat", "lon", "r")
            r.filter.parametersList
                .first { it.name == "r" }
                .value.floatValue shouldBe 20000.0
            r.filter.parametersList
                .first { it.name == "lat" }
                .value.floatValue shouldBe 49.1951

            r.sqlPreview shouldContain "geo_distance_m(t.\"lat\", t.\"lon\", {lat}, {lon}) <= {r}"
            r.normalized.point.radiusM shouldBe 20000.0
            r.normalized.point.lat shouldBe 49.1951
            r.source shouldBe GroundingResult.Source.RULES
        }

        "no geo columns in the package → null (caller emits UNGROUNDABLE)" {
            val builder = GeoRecipeBuilder(FakeMetadataClient(emptyList()))
            builder.buildDistance(query, brno, "cnc").shouldBeNull()
        }

        "containment → bbox FilterRecipe + polygon WKT in Normalized.shape" {
            val builder = GeoRecipeBuilder(FakeMetadataClient.poi("cnc"))
            val place =
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
                )
            val r = builder.buildContainment(place, "cnc").shouldNotBeNull()

            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.filter.parametersList.map { it.name } shouldBe listOf("min_lat", "max_lat", "min_lon", "max_lon")
            r.filter.parametersList
                .first { it.name == "max_lon" }
                .value.floatValue shouldBe 16.7
            r.sqlPreview shouldContain "t.\"lat\" >= {min_lat}"
            r.sqlPreview shouldContain "t.\"lon\" <= {max_lon}"
            r.normalized.shape.wkt shouldContain "POLYGON"
            r.normalized.shape.source shouldContain "OSM"
        }

        "containment with no boundary → null (caller emits UNGROUNDABLE)" {
            val builder = GeoRecipeBuilder(FakeMetadataClient.poi("cnc"))
            builder.buildContainment(ResolvedPlace("X", 0.0, 0.0, boundary = null), "cnc").shouldBeNull()
        }
    })
