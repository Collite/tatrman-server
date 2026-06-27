package org.tatrman.kantheon.ariadne.search

import org.tatrman.ariadne.v1.SearchRequest

/**
 * Threshold + sort + page, applied uniformly whether the caller asked for
 * a single algorithm or `all`. Centralised so behaviour is identical:
 *
 *  1. Drop hits whose score is below `request.result_threshold` (default
 *     0.0 = no-op).
 *  2. Sort descending by score (stable for equal-score ties — preserves
 *     algorithm-internal order).
 *  3. Apply paging: `page.page_size` (0 = server default 100, capped at
 *     1000) starting from offset 0. Page-token-based offset is not
 *     interpreted here (the proto allows it but the search contract is
 *     "small N — chip flow"; offset paging is a follow-up if needed).
 */
fun postProcess(
    hits: List<SearchHit>,
    request: SearchRequest,
): List<SearchHit> {
    val threshold = request.resultThreshold
    val filtered = if (threshold > 0f) hits.filter { it.score >= threshold } else hits
    val sorted = filtered.sortedByDescending { it.score }
    val limit = pagedLimit(request)
    return if (limit > 0 && sorted.size > limit) sorted.take(limit) else sorted
}

private fun pagedLimit(request: SearchRequest): Int {
    val raw = if (request.hasPage()) request.page.pageSize else 0
    return when {
        raw <= 0 -> DEFAULT_PAGE_SIZE
        raw > MAX_PAGE_SIZE -> MAX_PAGE_SIZE
        else -> raw
    }
}

private const val DEFAULT_PAGE_SIZE = 100
private const val MAX_PAGE_SIZE = 1000
