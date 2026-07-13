package org.tatrman.geo.geocode

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One OSM/Nominatim search hit. `geojson` is present when the request asks for `polygon_geojson=1`. */
@Serializable
data class NominatimPlace(
    @SerialName("place_id") val placeId: Long = 0,
    val lat: String,
    val lon: String,
    @SerialName("display_name") val displayName: String = "",
    val geojson: NominatimGeoJson? = null,
) {
    val latitude: Double get() = lat.toDouble()
    val longitude: Double get() = lon.toDouble()
}

/** Raw GeoJSON geometry — `coordinates` is left as a [JsonElement] and decoded by BoundaryGeometry. */
@Serializable
data class NominatimGeoJson(
    val type: String,
    val coordinates: JsonElement,
)

/**
 * Thin client over OSM Nominatim `/search` (A9.4). OSM usage policy requires a descriptive
 * User-Agent and rate limiting; callers must cache aggressively (the resolver does). ČÚZK RÚIAN is
 * the preferred source for CZ admin units but its API is deferred — Nominatim is the working source.
 */
class NominatimClient(
    private val baseUrl: String,
    private val userAgent: String,
    timeoutMs: Long = 10_000L,
) : AutoCloseable {
    private val httpClient =
        HttpClient(CIO) {
            // A 429 (rate-limit) / 5xx must surface as a thrown ResponseException so the resolver can
            // treat it as a transient outage (→ Unavailable), not silently deserialize an error body.
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }

    /** Geocode [query], returning up to [limit] candidates with boundary polygons. */
    suspend fun search(
        query: String,
        limit: Int = 5,
    ): List<NominatimPlace> =
        httpClient
            .get("$baseUrl/search") {
                parameter("q", query)
                parameter("format", "jsonv2")
                parameter("limit", limit.toString())
                parameter("polygon_geojson", "1")
                parameter("addressdetails", "0")
                header("User-Agent", userAgent)
            }.body()

    override fun close() {
        httpClient.close()
    }
}
