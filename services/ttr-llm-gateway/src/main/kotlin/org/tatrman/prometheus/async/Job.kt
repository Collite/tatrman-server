package org.tatrman.prometheus.async

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

@Table("jobs")
data class Job(
    @Id val id: String,
    val status: String,
    val result: String? = null,
    val requestPayload: String? = null, // JSON
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

enum class JobStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    ERROR,
}

interface JobRepository : CrudRepository<Job, String>
