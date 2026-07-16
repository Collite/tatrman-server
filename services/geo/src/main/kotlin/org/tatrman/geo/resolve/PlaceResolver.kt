// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.resolve

import org.tatrman.text.Normalization

/** A place's boundary polygon (WKT, WGS84) + its bounding box — for containment (A9.4). */
data class Boundary(
    val wkt: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val source: String = "OSM/Nominatim",
    val fetchedAt: String = "",
)

/** A resolved anchor place — WGS84 centroid (distance) + optional [boundary] (containment). */
data class ResolvedPlace(
    val label: String,
    val lat: Double,
    val lon: Double,
    val boundary: Boundary? = null,
)

/** One option when a name resolves to several places ("Újezd"). */
data class PlaceCandidate(
    val id: String,
    val label: String,
    val lat: Double,
    val lon: Double,
)

/**
 * An anchor that names a POI *in the model* (A9 POI-in-model layer) — resolved at query time by
 * joining the POI [entity] on `nameColumn = [placeName]` rather than to literal coordinates (the
 * service never reads business rows).
 */
data class ModelPoiRef(
    val entity: org.tatrman.plan.v1.QualifiedName,
    val entityName: String,
    val nameColumn: String,
    val latColumn: String,
    val lonColumn: String,
    val placeName: String,
)

/** Outcome of resolving a place name. */
sealed interface PlaceResolution {
    data class Found(
        val place: ResolvedPlace,
    ) : PlaceResolution

    data class Ambiguous(
        val candidates: List<PlaceCandidate>,
    ) : PlaceResolution

    /** The anchor names a POI in the model — grounded via a join, not literal coordinates. */
    data class ModelPoi(
        val poi: ModelPoiRef,
    ) : PlaceResolution

    /** Recognized a place name but couldn't resolve it → the service emits UNGROUNDABLE. */
    data object Unknown : PlaceResolution

    /**
     * The geocoder dependency failed transiently (network / timeout / 429 / 5xx). Distinct from
     * [Unknown] (a genuine no-match): the service fails loud (gRPC UNAVAILABLE) so the caller retries,
     * rather than telling the user a real place doesn't exist. Never cached.
     */
    data class Unavailable(
        val reason: String,
    ) : PlaceResolution
}

/**
 * Name → place. The A9.3 implementation resolves POI entities in the model first, then
 * administrative places via ČÚZK RÚIAN with an OSM Nominatim fallback (rate-limited, cached). The
 * scaffold ships only the interface + [StaticPlaceResolver]; the networked resolver lands in A9.3.
 */
fun interface PlaceResolver {
    suspend fun resolve(
        name: String,
        pkg: String,
    ): PlaceResolution

    /**
     * Resolve a place after the user picked one of the [PlaceResolution.Ambiguous] candidates —
     * [chosenId] is the selected [PlaceCandidate.id]. Without this, a resume just re-runs [resolve],
     * gets the same Ambiguous set back, and clarification can never terminate. The default keeps the
     * old behaviour for resolvers that never disambiguate (they return Found/Unknown, not Ambiguous);
     * resolvers that DO return Ambiguous (the geocoder) override this to select the chosen candidate.
     */
    suspend fun resolveChoice(
        name: String,
        pkg: String,
        chosenId: String,
    ): PlaceResolution = resolve(name, pkg)
}

/** In-memory resolver for tests / fixture boots — no network. Matches diacritic-insensitively. */
class StaticPlaceResolver(
    private val places: Map<String, ResolvedPlace>,
) : PlaceResolver {
    override suspend fun resolve(
        name: String,
        pkg: String,
    ): PlaceResolution {
        // S-2 fold via the one shared spec (RG-P6.S2.T4 — no private fold); trim is
        // geo's input cleanup applied before the shared fold.
        val key = Normalization.fold(name.trim())
        // exact fold match, else a prefix match to tolerate cs declension ("brna" → "brno" won't
        // prefix-match, so declined forms are seeded explicitly in the test fixture).
        val hit = places[key] ?: places.entries.firstOrNull { key.startsWith(it.key) || it.key.startsWith(key) }?.value
        return hit?.let { PlaceResolution.Found(it) } ?: PlaceResolution.Unknown
    }

    companion object {
        /** Brno + Praha (with the common cs declined forms) for the distance-path tests. */
        fun czCities(): StaticPlaceResolver =
            StaticPlaceResolver(
                mapOf(
                    "brno" to ResolvedPlace("Brno", 49.1951, 16.6068),
                    "brna" to ResolvedPlace("Brno", 49.1951, 16.6068),
                    "praha" to ResolvedPlace("Praha", 50.0755, 14.4378),
                    "prague" to ResolvedPlace("Praha", 50.0755, 14.4378),
                ),
            )
    }
}
