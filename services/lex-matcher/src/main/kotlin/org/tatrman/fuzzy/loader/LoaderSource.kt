// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.LoaderWarningInfo

interface LoaderSource {
    /**
     * Returns the next cache contents, or `null` if the load failed and the previous
     * cache should be preserved.
     */
    suspend fun loadNextCache(): Map<String, List<Candidate>>?

    /**
     * B-T4 loader report: structured warnings from the last load (e.g. declared
     * fuzzy columns skipped for lacking a usable PK — `RG-FUZ-001`). Surfaced via
     * `GetStatus` so estates learn which declared columns aren't searchable.
     * Default: none (the static source has no skips).
     */
    fun warnings(): List<LoaderWarningInfo> = emptyList()
}
