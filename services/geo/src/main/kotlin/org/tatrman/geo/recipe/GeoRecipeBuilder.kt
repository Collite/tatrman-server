package org.tatrman.geo.recipe

import org.tatrman.grounding.v1.FilterRecipe
import org.tatrman.grounding.v1.GeoPoint
import org.tatrman.grounding.v1.GeoShape
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.JoinRecipe
import org.tatrman.grounding.v1.Normalized
import org.tatrman.plan.v1.JoinType
import org.tatrman.grounding.core.PlanExpr
import org.tatrman.grounding.core.SqlRenderer
import org.tatrman.geo.discover.GeoDiscovery
import org.tatrman.geo.parse.GeoQuery
import org.tatrman.geo.resolve.ModelPoiRef
import org.tatrman.geo.resolve.ResolvedPlace

/**
 * Turns a resolved distance query into a [GroundingResult] (A9.5, distance path). Emits a
 * **FilterRecipe** over the `geo_distance_m` catalog function against the queried entity's
 * `geo_lat`/`geo_lon` columns: `geo_distance_m(t.lat, t.lon, {lat}, {lon}) <= {r}`. `sql_preview`
 * is rendered by [SqlRenderer] from the same Expression tree (single source). Returns null when the
 * package exposes no geo columns (caller → UNGROUNDABLE).
 *
 * Containment (boundary → bbox/WKT) is the A9.4 path and lives elsewhere.
 */
class GeoRecipeBuilder(
    private val discovery: GeoDiscovery,
) {
    private companion object {
        const val FACT_ALIAS = "t"
    }

    suspend fun buildDistance(
        query: GeoQuery.Distance,
        center: ResolvedPlace,
        pkg: String,
    ): GroundingResult? {
        val cols = discovery.geoColumns(pkg) ?: return null
        val condition =
            PlanExpr.le(
                PlanExpr.geoDistanceM(
                    PlanExpr.col(FACT_ALIAS, cols.lat.columnName),
                    PlanExpr.col(FACT_ALIAS, cols.lon.columnName),
                    PlanExpr.param("lat", "float"),
                    PlanExpr.param("lon", "float"),
                ),
                PlanExpr.param("r", "float"),
            )
        val filter =
            FilterRecipe
                .newBuilder()
                .setCondition(condition)
                .addParameters(PlanExpr.floatParam("lat", center.lat, "Anchor latitude (${center.label})"))
                .addParameters(PlanExpr.floatParam("lon", center.lon, "Anchor longitude (${center.label})"))
                .addParameters(PlanExpr.floatParam("r", query.radiusMeters, "Radius (m)"))
                .setAnchorColumn(cols.lat.qname)
                .build()
        val normalized =
            Normalized
                .newBuilder()
                .setPoint(
                    GeoPoint
                        .newBuilder()
                        .setLat(center.lat)
                        .setLon(center.lon)
                        .setRadiusM(query.radiusMeters),
                ).build()
        return GroundingResult
            .newBuilder()
            .setNormalized(normalized)
            .setFilter(filter)
            .setSqlPreview(SqlRenderer.render(condition))
            .setConfidence(query.confidence.toFloat())
            .setSource(GroundingResult.Source.RULES)
            .setExplanation("Within ${query.radiusMeters.toInt()} m of ${center.label}.")
            .build()
    }

    /**
     * Distance to a POI *in the model* (A9 POI-in-model layer): emit a **JoinRecipe** that joins the
     * POI entity on `poi.name = {place}` and filters `geo_distance_m(t.lat, t.lon, poi.lat, poi.lon)
     * <= {r}`. The anchor coordinates are read from the joined POI row at query time — the service
     * never reads business rows, so `normalized.point` carries only the radius (lat/lon unknown here).
     * Returns null when the package exposes no geo columns (caller → UNGROUNDABLE).
     */
    suspend fun buildDistanceToPoi(
        query: GeoQuery.Distance,
        poi: ModelPoiRef,
        pkg: String,
    ): GroundingResult? {
        val cols = discovery.geoColumns(pkg) ?: return null
        val poiAlias = "poi"
        val distance =
            PlanExpr.geoDistanceM(
                PlanExpr.col(FACT_ALIAS, cols.lat.columnName),
                PlanExpr.col(FACT_ALIAS, cols.lon.columnName),
                PlanExpr.col(poiAlias, poi.latColumn),
                PlanExpr.col(poiAlias, poi.lonColumn),
            )
        val filterCond = PlanExpr.le(distance, PlanExpr.param("r", "float"))
        val onCondition =
            PlanExpr.eq(PlanExpr.col(poiAlias, poi.nameColumn, "text"), PlanExpr.param("place", "text"))
        val join =
            JoinRecipe
                .newBuilder()
                .setEntity(poi.entity)
                .setJoinType(JoinType.INNER)
                .setOnCondition(onCondition)
                .setFilter(filterCond)
                .addParameters(PlanExpr.textParam("place", poi.placeName, "Anchor POI name"))
                .addParameters(PlanExpr.floatParam("r", query.radiusMeters, "Radius (m)"))
                .setSuggestedAlias(poiAlias)
                .build()
        val normalized =
            Normalized
                .newBuilder()
                .setPoint(GeoPoint.newBuilder().setRadiusM(query.radiusMeters))
                .build()
        val sql = SqlRenderer.renderJoin(poi.entityName, poiAlias, onCondition, filterCond)
        return GroundingResult
            .newBuilder()
            .setNormalized(normalized)
            .setJoin(join)
            .setSqlPreview(sql)
            .setConfidence(query.confidence.toFloat())
            .setSource(GroundingResult.Source.RULES)
            .setExplanation(
                "Within ${query.radiusMeters.toInt()} m of the in-model POI matching '${poi.placeName}'.",
            ).build()
    }

    /**
     * Containment (A9.4/A9.5 v1 decision): emit a **bbox-prefilter** FilterRecipe over the POI's
     * lat/lon plus the full polygon WKT in `Normalized.shape`. The precise point-in-polygon is a
     * downstream post-filter (Golem); the service never reads business rows. Returns null when the
     * place has no boundary or the package has no geo columns.
     */
    suspend fun buildContainment(
        place: ResolvedPlace,
        pkg: String,
    ): GroundingResult? {
        val boundary = place.boundary ?: return null
        val cols = discovery.geoColumns(pkg) ?: return null
        val latCol = PlanExpr.col(FACT_ALIAS, cols.lat.columnName)
        val lonCol = PlanExpr.col(FACT_ALIAS, cols.lon.columnName)
        val condition =
            PlanExpr.and(
                PlanExpr.and(
                    PlanExpr.ge(latCol, PlanExpr.param("min_lat", "float")),
                    PlanExpr.le(latCol, PlanExpr.param("max_lat", "float")),
                ),
                PlanExpr.and(
                    PlanExpr.ge(lonCol, PlanExpr.param("min_lon", "float")),
                    PlanExpr.le(lonCol, PlanExpr.param("max_lon", "float")),
                ),
            )
        val filter =
            FilterRecipe
                .newBuilder()
                .setCondition(condition)
                .addParameters(PlanExpr.floatParam("min_lat", boundary.minLat, "Bounding-box min latitude"))
                .addParameters(PlanExpr.floatParam("max_lat", boundary.maxLat, "Bounding-box max latitude"))
                .addParameters(PlanExpr.floatParam("min_lon", boundary.minLon, "Bounding-box min longitude"))
                .addParameters(PlanExpr.floatParam("max_lon", boundary.maxLon, "Bounding-box max longitude"))
                .setAnchorColumn(cols.lat.qname)
                .build()
        val normalized =
            Normalized
                .newBuilder()
                .setShape(
                    GeoShape
                        .newBuilder()
                        .setWkt(boundary.wkt)
                        .setSource(boundary.source)
                        .setFetchedAt(boundary.fetchedAt),
                ).build()
        return GroundingResult
            .newBuilder()
            .setNormalized(normalized)
            .setFilter(filter)
            .setSqlPreview(SqlRenderer.render(condition))
            .setConfidence(0.85f)
            .setSource(GroundingResult.Source.RULES)
            .setExplanation("Inside ${place.label} (bounding-box prefilter; exact polygon in shape).")
            .build()
    }
}
