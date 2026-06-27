package org.tatrman.kantheon.echo.loader

import org.tatrman.kantheon.echo.core.Candidate

interface LoaderSource {
    /**
     * Returns the next cache contents, or `null` if the load failed and the previous
     * cache should be preserved.
     */
    suspend fun loadNextCache(): Map<String, List<Candidate>>?
}
