package org.tatrman.health.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

data class HealthConfig(
    val prometheus: PrometheusConfig = PrometheusConfig(),
    val technologies: Map<String, TechnologyConfig> = emptyMap(),
)

data class PrometheusConfig(
    val url: String = "http://prometheus:9090",
    val timeout: Long = 5000,
    val queryInterval: Long = 60,
)

data class TechnologyConfig(
    val type: String,
    val url: String? = null,
    val healthEndpoint: String? = null,
    val job: String? = null,
    val prometheusUrl: String? = null,
    val timeout: Long = 5000,
    val host: String? = null,
    val port: Int? = null,
)

object ConfigLoader {
    fun load(): HealthConfig {
        val config: Config = ConfigFactory.load()
        val healthConfig = config.getConfig("health-check-service")
        return parseHealthConfig(healthConfig)
    }

    private fun parseHealthConfig(config: Config): HealthConfig {
        val prometheusConfig =
            if (config.hasPath("prometheus")) {
                val prom = config.getConfig("prometheus")
                PrometheusConfig(
                    url = prom.getString("url"),
                    timeout = prom.getLong("timeout"),
                    queryInterval = prom.getLong("queryInterval"),
                )
            } else {
                PrometheusConfig()
            }
        val technologies =
            if (config.hasPath("technologies")) {
                val techConfig = config.getConfig("technologies")
                val keys = techConfig.root().keys
                keys.associateWith { key ->
                    val tc = techConfig.getConfig(key)
                    TechnologyConfig(
                        type = tc.getString("type"),
                        url = tc.getStringOrNull("url"),
                        healthEndpoint = tc.getStringOrNull("healthEndpoint"),
                        job = tc.getStringOrNull("job"),
                        prometheusUrl = tc.getStringOrNull("prometheusUrl"),
                        timeout = if (tc.hasPath("timeout")) tc.getLong("timeout") else 5000,
                        host = tc.getStringOrNull("host"),
                        port = if (tc.hasPath("port")) tc.getInt("port") else null,
                    )
                }
            } else {
                emptyMap()
            }
        return HealthConfig(
            prometheus = prometheusConfig,
            technologies = technologies,
        )
    }

    private fun Config.getStringOrNull(path: String): String? = if (hasPath(path)) getString(path) else null
}
