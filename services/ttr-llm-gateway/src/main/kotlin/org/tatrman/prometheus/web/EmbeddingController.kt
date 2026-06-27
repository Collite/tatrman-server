package org.tatrman.prometheus.web

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.tatrman.prometheus.model.EmbeddingService

/**
 * Phase 09 A3 / DF-X — embeddings endpoint.
 *
 * Mirrors OpenAI's `/v1/embeddings` shape so existing clients can switch their base URL with no
 * other code changes. The service-level provider routing lives in [EmbeddingService]; this
 * controller is just the HTTP surface.
 */
@RestController
@RequestMapping("/api/v1")
class EmbeddingController(
    private val embeddingService: EmbeddingService,
) {
    @PostMapping("/embeddings")
    fun embeddings(
        @RequestBody request: EmbeddingRequestApi,
    ): EmbeddingResponseApi = embeddingService.process(request)
}
