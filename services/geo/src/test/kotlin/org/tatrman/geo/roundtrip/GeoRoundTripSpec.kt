package org.tatrman.geo.roundtrip

import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.Value
import org.tatrman.translate.v1.Language
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.geo.FakeMetadataClient
import org.tatrman.geo.parse.GeoQuery
import org.tatrman.geo.recipe.GeoRecipeBuilder
import org.tatrman.geo.resolve.Boundary
import org.tatrman.geo.resolve.ModelPoiRef
import org.tatrman.geo.resolve.ResolvedPlace
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam

/**
 * A9.6 round-trip — the distance recipe's sql_preview, wrapped into a full query, parses + validates
 * through the real Translator, and the plan still carries the geo_distance_m catalog call. Proves the
 * SQL text and the plan.v1 Expression tree agree (derived-not-duplicated).
 */
class GeoRoundTripSpec :
    StringSpec({
        val translator = Translator(GroundingModelHandle.storeModel())
        val builder = GeoRecipeBuilder(FakeMetadataClient.poi("cnc"))

        "distance FilterRecipe sql_preview parses + the plan carries geo_distance_m" {
            val query = GeoQuery.Distance(place = "Brno", here = false, radiusMeters = 20000.0, confidence = 0.9)
            val g = builder.buildDistance(query, ResolvedPlace("Brno", 49.1951, 16.6068), "cnc")!!

            val sql = "SELECT 1 FROM Store AS t WHERE ${g.sqlPreview}"
            val r = translator.parseToRelNode(sql, Language.SQL, parameters = g.filter.parametersList.map(::toSqlParam))

            r.shouldBeInstanceOf<ParseResult.Success>()
            r.plan.toString().shouldContainIgnoringCase("geo_distance_m")
        }

        "containment bbox FilterRecipe sql_preview parses + validates against the model" {
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
            val g = builder.buildContainment(place, "cnc")!!

            val sql = "SELECT 1 FROM Store AS t WHERE ${g.sqlPreview}"
            val r = translator.parseToRelNode(sql, Language.SQL, parameters = g.filter.parametersList.map(::toSqlParam))
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "POI-in-model JoinRecipe sql_preview parses (join on the POI name + geo_distance_m filter)" {
            val poi =
                ModelPoiRef(
                    entity =
                        org.tatrman.plan.v1.QualifiedName
                            .newBuilder()
                            .setSchemaCode(org.tatrman.plan.v1.SchemaCode.ER)
                            .setNamespace("Store")
                            .setName("Store")
                            .setPackage("cnc")
                            .build(),
                    entityName = "Store",
                    nameColumn = "store_name",
                    latColumn = "lat",
                    lonColumn = "lon",
                    placeName = "Central Depot",
                )
            val query =
                GeoQuery.Distance(
                    place = "Central Depot",
                    here = false,
                    radiusMeters = 20000.0,
                    confidence = 0.9,
                )
            val g = builder.buildDistanceToPoi(query, poi, "cnc")!!

            val sql = "SELECT 1 FROM Store AS t ${g.sqlPreview}"
            val r = translator.parseToRelNode(sql, Language.SQL, parameters = g.join.parametersList.map(::toSqlParam))
            r.shouldBeInstanceOf<ParseResult.Success>()
            r.plan.toString().shouldContainIgnoringCase("geo_distance_m")
        }
    })

private fun toSqlParam(pb: ParameterBinding): SqlParam {
    val value: Any? =
        when (pb.value.vCase) {
            Value.VCase.FLOAT_VALUE -> pb.value.floatValue
            Value.VCase.STRING_VALUE -> pb.value.stringValue
            Value.VCase.INT_VALUE -> pb.value.intValue
            else -> null
        }
    return SqlParam(pb.name, pb.type, value)
}
