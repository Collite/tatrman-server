package org.tatrman.money

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
import org.tatrman.money.client.KtorLlmGatewayClient
import org.tatrman.money.client.LlmGatewayClient
import org.tatrman.money.client.MetaV1MoneyDiscovery
import org.tatrman.money.discover.EmptyMoneyDiscovery
import org.tatrman.money.discover.MoneyDiscovery
import org.tatrman.money.grpc.MoneyGroundingService
import org.tatrman.money.obs.MoneyMetrics
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.logging.IncomingCallLoggingInterceptor
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("org.tatrman.money.Application")

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "money", 7122)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    installKtorServerBase(KtorConfigFactory.fromConfig(config, "money", 7122))

    val openTelemetry =
        createOpenTelemetrySdk(
            OtelEndpointConfig(
                serviceName = "money",
                protocol = System.getenv("MONEY_OTEL_PROTOCOL") ?: "grpc",
            ),
            enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled"),
        )
    val metrics = MoneyMetrics(openTelemetry.getMeter("money"))

    val useFixture =
        config.hasPath("money.use-fixture-model") && config.getBoolean("money.use-fixture-model")
    val discovery = pickDiscovery(config, useFixture)
    val llmFallback = buildLlmFallbackClient(config)

    val moneyService =
        MoneyGroundingService(
            discovery = discovery,
            llmFallback = llmFallback,
            llmModel = config.getString("money.llm-fallback.model"),
            confidenceThreshold = config.getDouble("money.confidence-threshold"),
            defaultCurrency = config.getString("money.default-currency"),
            defaultTolerancePct = config.getDouble("money.default-tolerance-pct"),
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
            .addService(moneyService)
            .apply { if (reflectionEnabled) addService(ProtoReflectionServiceV1.newInstance()) }
            .build()

    launch {
        grpcServer.start()
        log.info("money gRPC server started on port {} (reflection={})", grpcPort, reflectionEnabled)
        grpcServer.awaitTermination()
    }

    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/status") {
            call.respond(
                buildJsonObject {
                    put("service", "money")
                    put("grpc_port", grpcPort)
                    put("llm_fallback", llmFallback != null)
                },
            )
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Shutting down money gRPC server")
        grpcServer.shutdown()
        if (discovery is AutoCloseable) discovery.close()
        llmFallback?.close()
    }
}

private fun pickDiscovery(
    config: Config,
    useFixture: Boolean,
): MoneyDiscovery {
    if (useFixture) return EmptyMoneyDiscovery
    return MetaV1MoneyDiscovery.forAddress(
        host = config.getString("metadata.host"),
        port = config.getInt("metadata.port"),
    )
}

private fun buildLlmFallbackClient(config: Config): LlmGatewayClient? {
    if (!config.getBoolean("money.llm-fallback.enabled")) return null
    val baseUrl = config.getString("money.llm-fallback.gateway-url").trim()
    if (baseUrl.isEmpty()) {
        log.warn("money.llm-fallback.enabled = true but gateway-url is empty — running rules-only")
        return null
    }
    val timeoutMs = config.getLong("money.llm-fallback.timeout-ms")
    val apiKey = config.getString("money.llm-fallback.api-key").takeIf { it.isNotBlank() }
    log.info("money llm-gateway fallback configured at {} (timeout {}ms)", baseUrl, timeoutMs)
    return KtorLlmGatewayClient(baseUrl = baseUrl, timeoutMs = timeoutMs, apiKey = apiKey)
}
