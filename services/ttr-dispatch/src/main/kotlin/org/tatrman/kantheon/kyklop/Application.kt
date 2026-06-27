package org.tatrman.kantheon.kyklop

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.tatrman.kyklop.v1.WorkerHealthStatus
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import shared.logging.IncomingCallLoggingInterceptor
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.kyklop.client.GrpcWorkerClient
import org.tatrman.kantheon.kyklop.client.WorkerClient
import org.tatrman.kantheon.kyklop.config.WorkerConfigLoader
import org.tatrman.kantheon.kyklop.grpc.KyklopServiceImpl
import org.tatrman.kantheon.kyklop.registry.CapabilityPoller
import org.tatrman.kantheon.kyklop.registry.WorkerEntry
import org.tatrman.kantheon.kyklop.registry.WorkerRegistry
import org.tatrman.kantheon.kyklop.sticky.StickyRegistry
import org.tatrman.kantheon.kyklop.world.WorldConfig
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.kyklop.Application")

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "kyklop", 7290)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    installKtorServerBase(KtorConfigFactory.fromConfig(config, "kyklop", 7290))

    // OTel SDK init: configures OTLP trace/metric/log exporters AND installs the bridge
    // into the Logback OpenTelemetryAppender so all SLF4J logs are forwarded to OTLP → Alloy → Loki.
    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "kyklop",
            protocol = System.getenv("KYKLOP_OTEL_PROTOCOL") ?: "grpc",
        ),
    )

    val world = WorldConfig.fromConfig(config)

    val degradedAfter = config.getInt("kyklop.capability-refresh.degraded-after-failures")
    val unreachableAfter = config.getInt("kyklop.capability-refresh.unreachable-after-failures")
    val registry = WorkerRegistry(degradedAfter = degradedAfter, unreachableAfter = unreachableAfter)

    val sticky =
        StickyRegistry(
            idleTimeout = Duration.ofSeconds(config.getLong("kyklop.sticky.idle-timeout-seconds")),
            maxEntries = config.getInt("kyklop.sticky.max-entries"),
        )

    // Wire callbacks: when a Worker becomes UNREACHABLE, evict its sticky sessions.
    val pollerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val slots = WorkerConfigLoader.load(config)
    val workerClients: Map<String, WorkerClient> =
        slots.associate { slot -> slot.endpoint to (GrpcWorkerClient(endpoint = slot.endpoint) as WorkerClient) }
    val seed =
        slots.map { slot ->
            WorkerEntry(
                endpoint = slot.endpoint,
                roleHint = slot.roleHint,
                client = workerClients.getValue(slot.endpoint),
                capabilities = null,
                health = WorkerHealthStatus.WORKER_HEALTH_STATUS_UNSPECIFIED,
                lastPolled = null,
                consecutiveFailures = 0,
            )
        }
    registry.seed(seed)
    workerClients.keys.forEach { ep -> registry.onUnreachable(ep) { sticky.evictByEndpoint(ep) } }

    val poller =
        CapabilityPoller(
            registry = registry,
            clients = workerClients,
            intervalSeconds = config.getLong("kyklop.capability-refresh.interval-seconds"),
            scope = pollerScope,
        )
    if (!config.getBoolean("kyklop.use-fixture")) {
        poller.start()
    } else {
        log.warn("Kyklop booting in fixture mode (kyklop.use-fixture = true); capability poll disabled")
    }

    // Sticky sweep
    val sweepInterval = config.getLong("kyklop.sticky.sweep-interval-seconds")
    pollerScope.launch {
        while (isActive) {
            delay(sweepInterval.seconds)
            sticky.sweepIdle()
        }
    }

    val allowStickyFailover =
        config.hasPath("kyklop.sticky.allow-failover") &&
            config.getBoolean("kyklop.sticky.allow-failover")
    val service =
        KyklopServiceImpl(
            registry = registry,
            sticky = sticky,
            world = world,
            allowStickyFailover = allowStickyFailover,
        )

    val grpcPort = config.getInt("grpc.port")
    val maxMessageSize = config.getInt("grpc.max-message-size-bytes")
    val reflectionEnabled =
        config.hasPath("grpc.reflection-enabled") && config.getBoolean("grpc.reflection-enabled")
    val grpcServer =
        NettyServerBuilder
            .forPort(grpcPort)
            // Tolerate clients' keepalive (30s pings, incl. idle) instead of GOAWAY too_many_pings.
            .permitKeepAliveTime(20, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .maxInboundMessageSize(maxMessageSize)
            .intercept(IncomingCallLoggingInterceptor())
            .addService(service)
            .apply { if (reflectionEnabled) addService(ProtoReflectionServiceV1.newInstance()) }
            .build()

    launch {
        grpcServer.start()
        log.info("Kyklop gRPC server started on port {} (reflection={})", grpcPort, reflectionEnabled)
        grpcServer.awaitTermination()
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "UP")) }
        get("/ready") {
            val healthy = registry.all().count { it.health == WorkerHealthStatus.HEALTHY }
            // Homogeneous values only: the content serializer rejects a Map with mixed element
            // types (String + Int here), which made /ready throw 500. Stringify the count.
            if (healthy > 0 || config.getBoolean("kyklop.use-fixture")) {
                call.respond(mapOf("status" to "UP", "healthy_workers" to healthy.toString()))
            } else {
                call.respond(
                    io.ktor.http.HttpStatusCode.ServiceUnavailable,
                    mapOf("status" to "NOT_READY", "healthy_workers" to healthy.toString()),
                )
            }
        }
        get("/status") {
            val workers = registry.all()
            call.respond(
                mapOf(
                    "service" to "kyklop",
                    "grpc_port" to grpcPort,
                    "default_connection" to world.defaultConnection,
                    "known_workers" to workers.size,
                    "healthy_workers" to workers.count { it.health == WorkerHealthStatus.HEALTHY },
                    "active_sticky_sessions" to sticky.size(),
                ),
            )
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Shutting down kyklop")
        poller.stop()
        pollerScope.cancel()
        grpcServer.shutdown()
        workerClients.values.forEach { runCatching { it.close() } }
    }
}

private fun CoroutineScope.cancel() {
    coroutineContext[kotlinx.coroutines.Job]?.cancel()
}
