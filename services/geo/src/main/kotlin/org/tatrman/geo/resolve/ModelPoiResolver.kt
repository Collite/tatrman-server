// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.resolve

import org.tatrman.geo.discover.GeoDiscovery

/**
 * Resolves an anchor against a POI entity in the model (A9 POI-in-model layer). When the package has
 * a POI entity (A3 `kind: poi`) with a name attribute + geo columns, ANY anchor name is treated as a
 * possible POI reference and returned as [PlaceResolution.ModelPoi] — the actual name match happens
 * at query time via `poi.name = {place}` (a non-matching name simply yields no rows). When the
 * package has no POI entity, returns [PlaceResolution.Unknown] so a resolver chain can fall through.
 *
 * Sits AFTER the geocoder in the chain: a geocodable place (a city) resolves to coordinates first;
 * only a non-geocodable anchor ("the Prague depot") falls through to here. This inverts A9.3's stated
 * "model-first" order — a justified deviation, since the service can't read rows to validate a POI
 * name match and so can't safely prefer a POI join over a successful geocode.
 */
class ModelPoiResolver(
    private val discovery: GeoDiscovery,
) : PlaceResolver {
    override suspend fun resolve(
        name: String,
        pkg: String,
    ): PlaceResolution {
        val poi = discovery.poiEntity(pkg) ?: return PlaceResolution.Unknown
        return PlaceResolution.ModelPoi(
            ModelPoiRef(
                entity = poi.entity,
                entityName = poi.entityName,
                nameColumn = poi.nameColumn,
                latColumn = poi.latColumn,
                lonColumn = poi.lonColumn,
                placeName = name,
            ),
        )
    }
}

/**
 * Tries [resolvers] in order, returning the first non-[PlaceResolution.Unknown] outcome. Used to put
 * the networked geocoder in front of the [ModelPoiResolver] fallback.
 */
class ChainedPlaceResolver(
    private val resolvers: List<PlaceResolver>,
) : PlaceResolver {
    override suspend fun resolve(
        name: String,
        pkg: String,
    ): PlaceResolution {
        // A non-Unknown outcome (Found / Ambiguous / ModelPoi / Unavailable) stops the chain — in
        // particular a geocoder Unavailable propagates rather than silently falling through to POIs.
        for (resolver in resolvers) {
            val resolution = resolver.resolve(name, pkg)
            if (resolution != PlaceResolution.Unknown) return resolution
        }
        return PlaceResolution.Unknown
    }

    override suspend fun resolveChoice(
        name: String,
        pkg: String,
        chosenId: String,
    ): PlaceResolution {
        for (resolver in resolvers) {
            val resolution = resolver.resolveChoice(name, pkg, chosenId)
            if (resolution != PlaceResolution.Unknown) return resolution
        }
        return PlaceResolution.Unknown
    }
}
