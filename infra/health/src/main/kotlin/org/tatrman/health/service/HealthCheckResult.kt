package org.tatrman.health.service

import kotlinx.serialization.Serializable

@Serializable
data class HealthCheckResult(
    val technology: String,
    val source: String,
    val timestamp: String,
    val status: String,
    val error: String? = null,
    val details: Map<String, String> = emptyMap(),
) {
    companion object {
        fun healthy(
            technology: String,
            source: String,
            details: Map<String, String> = emptyMap(),
        ) = HealthCheckResult(
            technology = technology,
            source = source,
            timestamp =
                java.time.Instant
                    .now()
                    .toString(),
            status = "healthy",
            details = details,
        )

        fun unhealthy(
            technology: String,
            source: String,
            error: String,
            details: Map<String, String> = emptyMap(),
        ) = HealthCheckResult(
            technology = technology,
            source = source,
            timestamp =
                java.time.Instant
                    .now()
                    .toString(),
            status = "unhealthy",
            error = error,
            details = details,
        )
    }
}

@Serializable
data class TechnologyStatus(
    val name: String,
    val status: String,
    val error: String? = null,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class AllHealthResponse(
    val status: String,
    val summary: HealthSummary,
    val threshold: Int? = null,
    val technologies: List<TechnologyStatus>,
)

@Serializable
data class HealthSummary(
    val total: Int,
    val healthy: Int,
    val unhealthy: Int,
)
