// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.geocode

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

/**
 * A9.4 — [BoundaryGeometry] must never throw: a well-formed polygon parses to WKT, and a degenerate
 * ring (< 4 points, e.g. an already-closed 3-point ring) returns null rather than letting JTS'
 * "Invalid number of points in LinearRing" escape as a gRPC INTERNAL.
 */
class BoundaryGeometrySpec :
    StringSpec({
        fun geoJson(
            type: String,
            coordinates: String,
        ) = NominatimGeoJson(type, Json.parseToJsonElement(coordinates))

        "a valid polygon → WKT + bbox" {
            val b =
                BoundaryGeometry
                    .fromGeoJson(geoJson("Polygon", "[[[16.5,49.1],[16.7,49.1],[16.7,49.3],[16.5,49.3],[16.5,49.1]]]"))
                    .shouldNotBeNull()
            b.wkt shouldContain "POLYGON"
        }

        "an already-closed 3-point ring → null (no JTS exception)" {
            BoundaryGeometry
                .fromGeoJson(geoJson("Polygon", "[[[16.5,49.1],[16.7,49.1],[16.5,49.1]]]"))
                .shouldBeNull()
        }

        "a non-numeric coordinate → null (no crash)" {
            BoundaryGeometry
                .fromGeoJson(geoJson("Polygon", """[[["x","y"],[16.7,49.1],[16.7,49.3],[16.5,49.1]]]"""))
                .shouldBeNull()
        }
    })
