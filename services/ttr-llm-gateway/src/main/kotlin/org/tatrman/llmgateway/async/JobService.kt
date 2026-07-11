package org.tatrman.llmgateway.async

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.tatrman.llmgateway.web.ChatCompletionRequestApi
import org.tatrman.llmgateway.web.ChatController
import java.time.Instant
import java.util.UUID

@Service
@ConditionalOnProperty(name = ["llm.async.enabled"], havingValue = "true")
class JobService(
    private val jobRepository: JobRepository,
    private val chatController: ChatController,
    private val natsPublisher: NatsPublisher?, // Optional, as NATS might not be connected in all envs
    private val webhookDispatcher: WebhookDispatcher,
    @org.springframework.context.annotation.Lazy private val self: JobService,
) {
    private val logger = LoggerFactory.getLogger(JobService::class.java)

    fun submitJob(request: ChatCompletionRequestApi): String {
        val jobId = UUID.randomUUID().toString()
        val job =
            Job(
                id = jobId,
                status = JobStatus.QUEUED.name,
                requestPayload = Json.encodeToString(request),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        jobRepository.save(job)
        self.processJobAsync(jobId, request) // Call via proxy for Async
        return jobId
    }

    @Async
    fun processJobAsync(
        jobId: String,
        request: ChatCompletionRequestApi,
    ) {
        logger.info("Starting Async Job: $jobId")
        try {
            updateStatus(jobId, JobStatus.PROCESSING)

            val response = chatController.chat(request)

            updateStatus(jobId, JobStatus.COMPLETED, Json.encodeToString(response))
        } catch (e: Exception) {
            logger.error("Job failed: $jobId", e)
            updateStatus(jobId, JobStatus.ERROR, e.message)
        }
    }

    private fun updateStatus(
        jobId: String,
        status: JobStatus,
        result: String? = null,
    ) {
        val job = jobRepository.findById(jobId).orElse(null) ?: return
        val updated = job.copy(status = status.name, result = result, updatedAt = Instant.now())
        val saved = jobRepository.save(updated)

        if (status == JobStatus.COMPLETED || status == JobStatus.ERROR) {
            // Notify NATS
            try {
                natsPublisher?.publishJobCompleted(saved)
            } catch (e: Exception) {
                logger.error("NATS Publish failed", e)
            }

            // Webhook if supported (Assuming we extract URL from request someday, for now logic is
            // Placeheld)
            // webhookDispatcher.dispatch("...", saved)
        }
    }

    fun getJob(jobId: String): Job? = jobRepository.findById(jobId).orElse(null)
}
