// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.checker

import org.tatrman.health.service.HealthCheckResult

interface HealthChecker {
    val technology: String

    suspend fun check(): HealthCheckResult
}
