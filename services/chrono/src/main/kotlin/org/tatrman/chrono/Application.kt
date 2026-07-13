package org.tatrman.chrono

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.chrono.client.KtorLlmGatewayClient
import org.tatrman.chrono.client.LlmGatewayClient
import org.tatrman.chrono.client.MetaV1SemanticDiscovery
import org.tatrman.chrono.discover.EmptySemanticDiscovery
import org.tatrman.chrono.discover.SemanticDiscovery
import org.tatrman.chrono.grpc.ChronoGroundingService
import org.tatrman.chrono.obs.ChronoMetrics
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.logging.IncomingCallLoggingInterceptor
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("org.tatrman.chrono.Application")

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "chrono", 7120)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    installKtorServerBase(KtorConfigFactory.fromConfig(config, "chrono", 7120))

    val openTelemetry =
        createOpenTelemetrySdk(
            OtelEndpointConfig(
                serviceName = "chrono",
                protocol = System.getenv("CHRONO_OTEL_PROTOCOL") ?: "grpc",
            ),
            enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled"),
        )
    val metrics = ChronoMetrics(openTelemetry.getMeter("chrono"))

    val useFixture =
        config.hasPath("chrono.use-fixture-model") && config.getBoolean("chrono.use-fixture-model")
    val discovery = pickDiscovery(config, useFixture)
    val llmFallback = buildLlmFallbackClient(config)

    val chronoService =
        ChronoGroundingService(
            discovery = discovery,
            llmFallback = llmFallback,
            llmModel = config.getString("chrono.llm-fallback.model"),
            confidenceThreshold = config.getDouble("chrono.confidence-threshold"),
            metrics = metrics,
        )

    val grpcPort = config.getInt("grpc.port")
    val maxMessageSize = config.getInt("grpc.max-message-size-bytes")
    val reflectionEnabled =
        config.hasPath("grpc.reflection-enabled") && config.getBoolean("grpc.reflection-enabled")
    val grpcServer =
        NettyServerBuilder
            .forPort(grpcPort)
            .permitKeepAliveTime(20, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .maxInboundMessageSize(maxMessageSize)
            .intercept(IncomingCallLoggingInterceptor())
            .addService(chronoService)
            .apply { if (reflectionEnabled) addService(ProtoReflectionServiceV1.newInstance()) }
            .build()

    launch {
        grpcServer.start()
        log.info("chrono gRPC server started on port {} (reflection={})", grpcPort, reflectionEnabled)
        grpcServer.awaitTermination()
    }

    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/status") {
            call.respond(
                buildJsonObject {
                    put("service", "chrono")
                    put("grpc_port", grpcPort)
                    put("llm_fallback", llmFallback != null)
                },
            )
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Shutting down chrono gRPC server")
        grpcServer.shutdown()
        if (discovery is AutoCloseable) discovery.close()
        llmFallback?.close()
    }
}

private fun pickDiscovery(
    config: Config,
    useFixture: Boolean,
): SemanticDiscovery {
    if (useFixture) return EmptySemanticDiscovery
    return MetaV1SemanticDiscovery.forAddress(
        host = config.getString("metadata.host"),
        port = config.getInt("metadata.port"),
    )
}

private fun buildLlmFallbackClient(config: Config): LlmGatewayClient? {
    if (!config.getBoolean("chrono.llm-fallback.enabled")) return null
    val baseUrl = config.getString("chrono.llm-fallback.gateway-url").trim()
    if (baseUrl.isEmpty()) {
        log.warn("chrono.llm-fallback.enabled = true but gateway-url is empty — running rules-only")
        return null
    }
    val timeoutMs = config.getLong("chrono.llm-fallback.timeout-ms")
    val apiKey = config.getString("chrono.llm-fallback.api-key").takeIf { it.isNotBlank() }
    log.info("chrono llm-gateway fallback configured at {} (timeout {}ms)", baseUrl, timeoutMs)
    return KtorLlmGatewayClient(baseUrl = baseUrl, timeoutMs = timeoutMs, apiKey = apiKey)
}
