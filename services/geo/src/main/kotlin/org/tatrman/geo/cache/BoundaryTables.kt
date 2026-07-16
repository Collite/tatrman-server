// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.cache

import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table for the durable boundary cache (A9.4). Mirrors `V1__boundary_cache.sql`. `wkt` and
 * the bbox columns are nullable — a centroid-only Nominatim hit has no polygon.
 */
object BoundaryCacheTable : Table("boundary_cache") {
    val placeRef = varchar("place_ref", 512)
    val label = varchar("label", 1024)
    val lat = double("lat")
    val lon = double("lon")
    val wkt = text("wkt").nullable()
    val minLat = double("min_lat").nullable()
    val minLon = double("min_lon").nullable()
    val maxLat = double("max_lat").nullable()
    val maxLon = double("max_lon").nullable()
    val sourceCol = varchar("source", 128)
    val attribution = varchar("attribution", 512)
    val fetchedAtMs = long("fetched_at_ms")

    override val primaryKey = PrimaryKey(placeRef)
}

/** Declined / alternate surface form → canonical `place_ref` (A9.4). */
object PlaceAliasTable : Table("place_alias") {
    val alias = varchar("alias", 512)
    val placeRef = varchar("place_ref", 512)

    override val primaryKey = PrimaryKey(alias)
}
