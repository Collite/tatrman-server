package org.tatrman.geo.geocode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.WKTWriter
import org.tatrman.geo.resolve.Boundary

/**
 * GeoJSON boundary (Nominatim `polygon_geojson`) → WKT + bounding box via JTS (A9.4). GeoJSON is
 * [lon, lat] order, so JTS Coordinate is (x = lon, y = lat) and the envelope maps X→lon, Y→lat.
 * Handles Polygon and MultiPolygon (outer rings); other geometry types yield null.
 */
object BoundaryGeometry {
    private val factory = GeometryFactory()
    private val wktWriter = WKTWriter()

    fun fromGeoJson(geoJson: NominatimGeoJson): Boundary? =
        // Any malformed geometry (degenerate ring, non-numeric coordinate, JTS validation) yields a
        // null boundary — a containment recipe then degrades to UNGROUNDABLE instead of the whole
        // gRPC call failing with INTERNAL.
        runCatching {
            val geometry: Geometry =
                when (geoJson.type) {
                    "Polygon" -> polygon(geoJson.coordinates.jsonArray) ?: return@runCatching null
                    "MultiPolygon" -> multiPolygon(geoJson.coordinates.jsonArray) ?: return@runCatching null
                    else -> return@runCatching null
                }
            val env = geometry.envelopeInternal
            Boundary(
                wkt = wktWriter.write(geometry),
                minLat = env.minY,
                minLon = env.minX,
                maxLat = env.maxY,
                maxLon = env.maxX,
            )
        }.getOrNull()

    /** GeoJSON Polygon coordinates = [outerRing, hole1, …]; we use the outer ring for the bbox. */
    private fun polygon(rings: JsonArray): Polygon? {
        val outer = rings.firstOrNull()?.jsonArray ?: return null
        val coords = ringCoordinates(outer) ?: return null
        return factory.createPolygon(coords.toTypedArray())
    }

    private fun multiPolygon(polygons: JsonArray): Geometry? {
        val built = polygons.mapNotNull { polygon(it.jsonArray) }
        return if (built.isEmpty()) null else factory.createMultiPolygon(built.toTypedArray())
    }

    private fun ringCoordinates(ring: JsonArray): List<Coordinate>? {
        val coords =
            ring.map { point ->
                val lonLat = point.jsonArray
                Coordinate(lonLat[0].jsonPrimitive.double, lonLat[1].jsonPrimitive.double)
            }
        // A JTS LinearRing must be closed (first == last) and have ≥ 4 points. Close it if needed,
        // then reject anything still under 4 points — an already-closed 3-point ring would otherwise
        // slip through and make createPolygon throw "Invalid number of points in LinearRing".
        val closed =
            if (coords.size >= 2 && coords.first().equals2D(coords.last())) coords else coords + coords.first()
        return if (closed.size >= 4) closed else null
    }
}
