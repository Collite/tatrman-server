package org.tatrman.llmgateway.async

import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class WebhookDispatcher {
    private val restClient = RestClient.create()

    fun dispatch(
        url: String,
        job: Job,
    ) {
        try {
            restClient
                .post()
                .uri(url)
                .body(job)
                .retrieve()
                .toBodilessEntity()
        } catch (e: Exception) {
            // Log error but don't fail the job
            println("Webhook Failed: ${e.message}")
        }
    }
}
