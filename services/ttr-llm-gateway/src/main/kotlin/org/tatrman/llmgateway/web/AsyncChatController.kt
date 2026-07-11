package org.tatrman.llmgateway.web

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.tatrman.llmgateway.async.Job
import org.tatrman.llmgateway.async.JobService

data class JobSubmitResponse(
    val jobId: String,
)

@RestController
@RequestMapping("/api/v1/async/chat")
@ConditionalOnProperty(name = ["llm.async.enabled"], havingValue = "true")
class AsyncChatController(
    private val jobService: JobService,
) {
    @PostMapping("/completions")
    fun submit(
        @RequestBody request: ChatCompletionRequestApi,
    ): JobSubmitResponse {
        val jobId = jobService.submitJob(request)
        return JobSubmitResponse(jobId)
    }

    @GetMapping("/jobs/{id}")
    fun poll(
        @PathVariable id: String,
    ): Job? = jobService.getJob(id)
}
