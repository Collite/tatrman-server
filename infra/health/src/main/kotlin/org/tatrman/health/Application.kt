// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health

import org.tatrman.health.api.healthRoutes
import org.tatrman.health.config.ConfigLoader
import org.tatrman.health.config.HealthConfig
import org.tatrman.health.service.HealthCheckService
import com.typesafe.config.ConfigFactory
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

private val logger = LoggerFactory.getLogger("main")

fun main() {
    val config = ConfigFactory.load()
    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "health-check-service",
            protocol = System.getenv("HEALTH_CHECK_SERVICE_OTEL_PROTOCOL") ?: "grpc",
        ),
        enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled"),
    )
    val serverConfig =
        KtorConfigFactory.fromConfig(
            config = config,
            defaultServiceName = "health-check-service",
            defaultPort = 7000,
        )
    val healthConfig = ConfigLoader.load()
    KtorServerBootstrap.createServer(serverConfig) { module(healthConfig, serverConfig) }.start(wait = true)
}

fun Application.module(
    healthConfig: HealthConfig,
    serverConfig: KtorServerConfig,
) {
    logger.info("Starting Health Check service")
    installKtorServerBase(serverConfig)

    val service = HealthCheckService(healthConfig)

    routing {
        healthRoutes(service)
    }
}
