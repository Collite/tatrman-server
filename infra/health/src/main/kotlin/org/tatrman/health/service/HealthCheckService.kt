package org.tatrman.health.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.tatrman.health.checker.HealthChecker
import org.tatrman.health.checker.NativeHealthChecker
import org.tatrman.health.checker.PrometheusHealthChecker
import org.tatrman.health.checker.TcpHealthChecker
import org.tatrman.health.config.HealthConfig
import org.tatrman.health.config.TechnologyConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.time.Duration

class HealthCheckService(
    private val config: HealthConfig,
) {
    private val cache: Cache<String, HealthCheckResult> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofSeconds(config.prometheus.queryInterval))
            .build()

    private val checkers: Map<String, HealthChecker> = buildCheckers()

    private fun buildCheckers(): Map<String, HealthChecker> =
        config.technologies.mapValues { (name, techConfig) ->
            createChecker(name, techConfig)
        }

    private fun createChecker(
        name: String,
        config: TechnologyConfig,
    ): HealthChecker =
        when (config.type.lowercase()) {
            "native" ->
                NativeHealthChecker(
                    technology = name,
                    url = config.url ?: throw IllegalArgumentException("URL required for native check"),
                    healthEndpoint = config.healthEndpoint ?: "/health",
                    timeout = config.timeout,
                )
            "prometheus" ->
                PrometheusHealthChecker(
                    technology = name,
                    prometheusUrl = config.prometheusUrl ?: this.config.prometheus.url,
                    job = config.job ?: name,
                    timeout = config.timeout,
                )
            "tcp" ->
                TcpHealthChecker(
                    technology = name,
                    host = config.host ?: throw IllegalArgumentException("Host required for TCP check"),
                    port = config.port ?: throw IllegalArgumentException("Port required for TCP check"),
                    timeout = config.timeout,
                )
            else -> throw IllegalArgumentException("Unknown checker type: ${config.type}")
        }

    suspend fun checkHealth(
        technology: String,
        bypassCache: Boolean = false,
    ): HealthCheckResult {
        val cacheKey = technology
        if (!bypassCache) {
            cache.getIfPresent(cacheKey)?.let { return it }
        }
        val checker =
            checkers[technology.lowercase()]
                ?: throw IllegalArgumentException("Unknown technology: $technology")
        val result = checker.check()
        cache.put(cacheKey, result)
        return result
    }

    suspend fun checkHealthDetailed(technology: String): HealthCheckResult =
        // TODO no details defined yet
        checkHealth(technology, true)

    suspend fun checkAllHealth(threshold: Int = 100): AllHealthResponse =
        coroutineScope {
            val asyncChecks =
                checkers.mapValues { (name, checker) ->
                    async {
                        try {
                            withTimeout(30_000) {
                                checker.check()
                            }
                        } catch (e: Exception) {
                            HealthCheckResult.unhealthy(
                                technology = name,
                                source =
                                    checker.javaClass.simpleName
                                        .removeSuffix("Checker")
                                        .lowercase(),
                                error = e.message ?: "Check failed",
                            )
                        }
                    }
                }
            val results = asyncChecks.mapValues { it.value.await() }
            val technologies =
                results.map { (name, result) ->
                    TechnologyStatus(
                        name = name,
                        status = result.status,
                        error = result.error,
                        details = result.details,
                    )
                }
            val healthyCount = technologies.count { it.status == "healthy" }
            val total = technologies.size
            val allHealthy = healthyCount >= (total * threshold / 100)
            AllHealthResponse(
                status = if (allHealthy) "healthy" else "unhealthy",
                summary =
                    HealthSummary(
                        total = total,
                        healthy = healthyCount,
                        unhealthy = total - healthyCount,
                    ),
                threshold = if (threshold != 100) threshold else null,
                technologies = technologies,
            )
        }

    fun getSupportedTechnologies(): List<String> = checkers.keys.toList()

    fun clearCache() = cache.invalidateAll()
}
