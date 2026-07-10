package org.tatrman.llmgateway.async

import io.nats.client.Connection
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["llm.async.enabled"], havingValue = "true")
class NatsPublisher(
    private val natsConnection: Connection,
) {
    fun publishJobCompleted(job: Job) {
        val subject = "jobs.completed"
        // Simple serialization
        val payload = """{"jobId": "${job.id}", "status": "${job.status}"}"""
        natsConnection.publish(subject, payload.toByteArray())
    }
}
