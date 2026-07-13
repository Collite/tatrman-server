package org.tatrman.geo.cache

import org.tatrman.geo.resolve.ResolvedPlace
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Diacritic-folded cache key for a place name — "Brno" / "brno" / "BRNO" collapse to one key.
 * cs-declension ("Brna" → "brno") is NOT handled by folding; those are carried by [PlaceAliasTable].
 */
fun foldPlaceKey(s: String): String =
    Normalizer
        .normalize(s.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

/**
 * Durable cache of resolved place boundaries (A9.4). The networked resolver consults it before
 * geocoding and populates it after a single-hit resolution, so OSM's rate-limit / caching policy is
 * honoured across restarts. A [ttl] drives refresh-on-read: an entry older than the TTL reads as a
 * miss and is re-fetched.
 *
 * The `now` timesource here is OPERATIONAL (cache freshness), not grounding time — grounding results
 * stay sourced from the request. It is injectable so the persistence tests are deterministic.
 */
interface BoundaryStore {
    /** Cached place for [name] (via alias redirection), or null if absent or stale. */
    suspend fun get(name: String): ResolvedPlace?

    /** Upsert the resolved [place] for [name], stamping the fetch instant. */
    suspend fun put(
        name: String,
        place: ResolvedPlace,
    )

    /** Point a declined / alternate [alias] at the [canonicalName] that holds the cached boundary. */
    suspend fun putAlias(
        alias: String,
        canonicalName: String,
    )
}

/**
 * In-memory [BoundaryStore] — the default when no service-local Postgres is configured, and the
 * store used by unit tests / fixture boots. Same folding + alias-redirection + staleness semantics
 * as [PostgresBoundaryStore].
 */
class InMemoryBoundaryStore(
    private val ttl: Duration = Duration.ofDays(90),
    private val now: () -> Instant = Instant::now,
) : BoundaryStore {
    private data class Entry(
        val place: ResolvedPlace,
        val fetchedAtMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val aliases = ConcurrentHashMap<String, String>()

    override suspend fun get(name: String): ResolvedPlace? {
        val key = foldPlaceKey(name)
        val canonical = aliases[key] ?: key
        val entry = entries[canonical] ?: return null
        if (now().toEpochMilli() - entry.fetchedAtMs > ttl.toMillis()) return null
        return entry.place
    }

    override suspend fun put(
        name: String,
        place: ResolvedPlace,
    ) {
        entries[foldPlaceKey(name)] = Entry(place, now().toEpochMilli())
    }

    override suspend fun putAlias(
        alias: String,
        canonicalName: String,
    ) {
        aliases[foldPlaceKey(alias)] = foldPlaceKey(canonicalName)
    }
}
