// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.cache

import com.typesafe.config.ConfigFactory
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.tatrman.geo.geocode.NominatimClient
import org.tatrman.geo.resolve.NominatimPlaceResolver
import shared.libs.db.common.DatabaseConnection
import java.time.Duration

/**
 * RG-P3.S2.T2 — the install-time boundary-cache priming job (run as a K8s Job / CLI, NOT in the
 * request path). Warms the durable boundary cache from a supplied place list so the runtime resolver
 * serves those places even when Nominatim is unreachable / rate-limited. The list is the operator's
 * to assemble — (a) the model-declared POI place names and (b) the distinct city values in member
 * data — and is passed as `GEO_PRIME_PLACES` (comma/newline-separated) or as CLI args.
 *
 * It reuses the exact geocode + store code path the service uses ([NominatimPlaceResolver] +
 * [BoundaryStore] over the same Postgres when `geo.db.enabled`), so there is no duplicate geocoding.
 */
object PrimingRunner {
    private val log = LoggerFactory.getLogger(PrimingRunner::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        val config = ConfigFactory.load()

        val names =
            (args.toList() + (System.getenv("GEO_PRIME_PLACES") ?: "").split(',', '\n'))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        if (names.isEmpty()) {
            log.warn("boundary-cache priming: no place names supplied (GEO_PRIME_PLACES / args empty) — nothing to do")
            return
        }

        val client =
            NominatimClient(
                baseUrl = config.getString("geo.nominatim.base-url"),
                userAgent = config.getString("geo.nominatim.user-agent"),
            )
        val dbEnabled = config.hasPath("geo.db.enabled") && config.getBoolean("geo.db.enabled")
        val db =
            if (dbEnabled && config.getString("geo.db.host").isNotBlank()) {
                DatabaseConnection.fromConfig(config, "geo.db").also {
                    it.init()
                    Flyway
                        .configure()
                        .dataSource(it.getDataSource())
                        .load()
                        .migrate()
                }
            } else {
                null
            }
        val ttl = Duration.ofDays(config.getLong("geo.boundary-cache-ttl-days"))
        val store: BoundaryStore = db?.let { PostgresBoundaryStore(it, ttl) } ?: InMemoryBoundaryStore(ttl)

        try {
            val report = runBlocking { CachePrimer(NominatimPlaceResolver(client, store)).prime(names) }
            log.info("boundary-cache priming complete: {}", report)
        } finally {
            client.close()
            db?.close()
        }
    }
}
