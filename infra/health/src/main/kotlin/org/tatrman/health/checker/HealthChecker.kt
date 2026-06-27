package org.tatrman.health.checker

import org.tatrman.health.service.HealthCheckResult

interface HealthChecker {
    val technology: String

    suspend fun check(): HealthCheckResult
}
