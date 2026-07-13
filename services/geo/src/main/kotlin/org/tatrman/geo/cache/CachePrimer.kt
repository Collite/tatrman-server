// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.cache

import org.slf4j.LoggerFactory
import org.tatrman.geo.resolve.PlaceResolution
import org.tatrman.geo.resolve.PlaceResolver

/** Tally of a priming run — how many anchor names warmed the boundary cache vs. couldn't. */
data class PrimingReport(
    val primed: Int,
    val ambiguous: Int,
    val unknown: Int,
    val unavailable: Int,
) {
    val total: Int get() = primed + ambiguous + unknown + unavailable
}

/**
 * RG-P3.S2.T2 — the install-time boundary-cache warmer. Reuses the *existing* geocode + store code
 * path (it simply drives [PlaceResolver.resolve] over a name list; the networked resolver stores each
 * single-hit boundary as a side effect), so the priming run is just a batch client — no duplicate
 * geocoding logic. Feed it (a) the model-declared POI place names and (b) the distinct city values in
 * member data; after a run those places resolve from the cache even when Nominatim is unreachable.
 *
 * A geocoder outage during priming ([PlaceResolution.Unavailable]) is counted, not fatal — priming is
 * best-effort and re-runnable; the runtime resolver still fails loud on a live miss.
 */
class CachePrimer(
    private val resolver: PlaceResolver,
) {
    private val logger = LoggerFactory.getLogger(CachePrimer::class.java)

    suspend fun prime(
        names: Collection<String>,
        pkg: String = "",
    ): PrimingReport {
        var primed = 0
        var ambiguous = 0
        var unknown = 0
        var unavailable = 0
        for (name in names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            when (resolver.resolve(name, pkg)) {
                is PlaceResolution.Found, is PlaceResolution.ModelPoi -> primed++
                is PlaceResolution.Ambiguous -> ambiguous++
                is PlaceResolution.Unavailable -> unavailable++
                PlaceResolution.Unknown -> unknown++
            }
        }
        val report = PrimingReport(primed, ambiguous, unknown, unavailable)
        logger.info(
            "boundary-cache priming: {} primed / {} ambiguous / {} unknown / {} unavailable (of {} names)",
            report.primed,
            report.ambiguous,
            report.unknown,
            report.unavailable,
            report.total,
        )
        return report
    }
}
