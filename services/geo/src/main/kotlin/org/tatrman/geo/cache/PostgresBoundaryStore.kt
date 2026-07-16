// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.cache

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tatrman.geo.resolve.Boundary
import org.tatrman.geo.resolve.ResolvedPlace
import shared.libs.db.common.DatabaseConnection
import java.time.Duration
import java.time.Instant

/**
 * Postgres-backed [BoundaryStore] (A9.4 persistence) over the shared [DatabaseConnection] (Hikari +
 * Exposed). Entries survive restarts and are shared across pods; a stale row (older than [ttl])
 * reads as a miss so the caller re-geocodes and overwrites it (refresh-on-read). `place_alias`
 * redirects declined / alternate surface forms onto the canonical `place_ref`.
 *
 * The schema is created by Flyway (`V1__boundary_cache.sql`) before this store is constructed.
 */
class PostgresBoundaryStore(
    private val db: DatabaseConnection,
    private val ttl: Duration = Duration.ofDays(90),
    private val now: () -> Instant = Instant::now,
) : BoundaryStore {
    override suspend fun get(name: String): ResolvedPlace? {
        val key = foldPlaceKey(name)
        return db.query {
            val canonical =
                PlaceAliasTable
                    .selectAll()
                    .where { PlaceAliasTable.alias eq key }
                    .singleOrNull()
                    ?.get(PlaceAliasTable.placeRef)
                    ?: key
            val row =
                BoundaryCacheTable
                    .selectAll()
                    .where { BoundaryCacheTable.placeRef eq canonical }
                    .singleOrNull()
                    ?: return@query null
            if (now().toEpochMilli() - row[BoundaryCacheTable.fetchedAtMs] > ttl.toMillis()) {
                return@query null
            }
            row.toResolvedPlace()
        }
    }

    override suspend fun put(
        name: String,
        place: ResolvedPlace,
    ) {
        val key = foldPlaceKey(name)
        val b = place.boundary
        val fetchedAt = now().toEpochMilli()
        db.query {
            // Delete-then-insert upsert — keeps the write portable and avoids version-specific
            // ON CONFLICT plumbing; single-row, guarded by the primary key.
            BoundaryCacheTable.deleteWhere { BoundaryCacheTable.placeRef eq key }
            BoundaryCacheTable.insert {
                it[placeRef] = key
                it[label] = place.label
                it[lat] = place.lat
                it[lon] = place.lon
                it[wkt] = b?.wkt
                it[minLat] = b?.minLat
                it[minLon] = b?.minLon
                it[maxLat] = b?.maxLat
                it[maxLon] = b?.maxLon
                it[sourceCol] = b?.source ?: DEFAULT_SOURCE
                it[attribution] = attributionFor(b?.source ?: DEFAULT_SOURCE)
                it[fetchedAtMs] = fetchedAt
            }
        }
    }

    override suspend fun putAlias(
        alias: String,
        canonicalName: String,
    ) {
        val aliasKey = foldPlaceKey(alias)
        val canonicalKey = foldPlaceKey(canonicalName)
        db.query {
            PlaceAliasTable.deleteWhere { PlaceAliasTable.alias eq aliasKey }
            PlaceAliasTable.insert {
                it[PlaceAliasTable.alias] = aliasKey
                it[placeRef] = canonicalKey
            }
        }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toResolvedPlace(): ResolvedPlace {
        val wkt = this[BoundaryCacheTable.wkt]
        val boundary =
            if (wkt != null) {
                Boundary(
                    wkt = wkt,
                    minLat = this[BoundaryCacheTable.minLat] ?: 0.0,
                    minLon = this[BoundaryCacheTable.minLon] ?: 0.0,
                    maxLat = this[BoundaryCacheTable.maxLat] ?: 0.0,
                    maxLon = this[BoundaryCacheTable.maxLon] ?: 0.0,
                    source = this[BoundaryCacheTable.sourceCol],
                    fetchedAt = Instant.ofEpochMilli(this[BoundaryCacheTable.fetchedAtMs]).toString(),
                )
            } else {
                null
            }
        return ResolvedPlace(
            label = this[BoundaryCacheTable.label],
            lat = this[BoundaryCacheTable.lat],
            lon = this[BoundaryCacheTable.lon],
            boundary = boundary,
        )
    }

    private companion object {
        const val DEFAULT_SOURCE = "OSM/Nominatim"

        fun attributionFor(source: String): String =
            if (source.contains("OSM", ignoreCase = true) || source.contains("Nominatim", ignoreCase = true)) {
                "© OpenStreetMap contributors"
            } else {
                ""
            }
    }
}
