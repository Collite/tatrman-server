// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.resolve

import org.slf4j.LoggerFactory
import org.tatrman.geo.cache.BoundaryStore
import org.tatrman.geo.cache.InMemoryBoundaryStore
import org.tatrman.geo.cache.foldPlaceKey
import org.tatrman.geo.geocode.BoundaryGeometry
import org.tatrman.geo.geocode.NominatimClient
import org.tatrman.geo.geocode.NominatimPlace
import java.util.Collections

/**
 * Networked [PlaceResolver] over OSM Nominatim (A9.4). A single hit resolves; several distinct hits
 * → Ambiguous with candidate options; none → Unknown.
 *
 * Resolved single-hit boundaries go through a durable [BoundaryStore] (in-memory by default, or the
 * service-local Postgres cache when configured) so OSM's rate-limit / caching policy is honoured
 * across restarts, with staleness / refresh-on-read handled by the store. Ambiguous / Unknown
 * outcomes are kept in a lightweight in-process negative cache (not persisted).
 *
 * POI-in-model resolution (metadata `poiEntities` + name attribute) is layered in front later; for
 * now the anchor is always geocoded.
 */
class NominatimPlaceResolver(
    private val client: NominatimClient,
    private val boundaryStore: BoundaryStore = InMemoryBoundaryStore(),
    private val maxCandidates: Int = 5,
    maxNegativeCache: Int = 4096,
) : PlaceResolver {
    private val logger = LoggerFactory.getLogger(NominatimPlaceResolver::class.java)

    // Bounded LRU (access-order) so a stream of unique/garbage anchor names can't grow it without
    // bound — the cache exists to spare Nominatim's rate limit, not to become a memory leak.
    private val negativeCache: MutableMap<String, PlaceResolution> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, PlaceResolution>(256, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, PlaceResolution>): Boolean =
                    size > maxNegativeCache
            },
        )

    override suspend fun resolve(
        name: String,
        pkg: String,
    ): PlaceResolution {
        boundaryStore.get(name)?.let { return PlaceResolution.Found(it) }
        val key = foldPlaceKey(name)
        negativeCache[key]?.let { return it }

        val hits =
            search(name)
                ?: return PlaceResolution.Unavailable("geocoder lookup failed for '$name'")
        return when {
            hits.isEmpty() -> PlaceResolution.Unknown.also { negativeCache[key] = it }
            hits.size == 1 -> {
                val place = toPlace(hits.first())
                boundaryStore.put(name, place)
                PlaceResolution.Found(place)
            }
            else ->
                PlaceResolution
                    .Ambiguous(hits.take(maxCandidates).map { toCandidate(it) })
                    .also { negativeCache[key] = it }
        }
    }

    override suspend fun resolveChoice(
        name: String,
        pkg: String,
        chosenId: String,
    ): PlaceResolution {
        // Re-geocode and pick the candidate the user chose. A fresh search (not the negative cache)
        // so the chosen hit's boundary polygon is rebuilt for a containment recipe.
        val hits = search(name) ?: return PlaceResolution.Unavailable("geocoder lookup failed for '$name'")
        val chosen =
            hits.firstOrNull { it.placeId.toString() == chosenId }
                ?: return PlaceResolution.Unknown
        val place = toPlace(chosen)
        boundaryStore.put(name, place)
        return PlaceResolution.Found(place)
    }

    /** Geocode [name], or null when the dependency failed (network / timeout / 429 / 5xx). */
    private suspend fun search(name: String): List<NominatimPlace>? =
        runCatching { client.search(name, limit = maxCandidates) }
            .getOrElse {
                logger.warn("nominatim lookup failed for '{}': {}", name, it.message)
                null
            }

    private fun toPlace(p: NominatimPlace): ResolvedPlace =
        ResolvedPlace(
            label = p.displayName,
            lat = p.latitude,
            lon = p.longitude,
            boundary = p.geojson?.let { BoundaryGeometry.fromGeoJson(it) },
        )

    private fun toCandidate(p: NominatimPlace): PlaceCandidate =
        PlaceCandidate(id = p.placeId.toString(), label = p.displayName, lat = p.latitude, lon = p.longitude)
}
