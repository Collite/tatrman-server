// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.tatrman.llmgateway.observability.PromptLog
import org.tatrman.llmgateway.observability.PromptLogSearchService

@RestController
@RequestMapping("/api/v1/observability")
class ObservabilityController(
    private val searchService: PromptLogSearchService,
) {
    @GetMapping("/logs")
    fun getLogs(
        @RequestParam(required = false) query: String?,
    ): List<PromptLog> =
        if (!query.isNullOrBlank()) {
            searchService.search(query)
        } else {
            // Placeholder: Without search, implementation might fallback to repo.findAll or we
            // restrict it
            // For now, allow simple search trick or empty list if no query
            searchService.search("")
        }
}
