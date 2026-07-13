package org.tatrman.geo

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
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.tatrman.geo.cache.BoundaryStore
import org.tatrman.geo.cache.InMemoryBoundaryStore
import org.tatrman.geo.cache.PostgresBoundaryStore
import org.tatrman.geo.client.KtorLlmGatewayClient
import org.tatrman.geo.client.LlmGatewayClient
import org.tatrman.geo.client.MetaV1GeoDiscovery
import org.tatrman.geo.discover.EmptyGeoDiscovery
import org.tatrman.geo.discover.GeoDiscovery
import org.tatrman.geo.geocode.NominatimClient
import org.tatrman.geo.grpc.GeoGroundingService
import org.tatrman.geo.obs.GeoMetrics
import org.tatrman.geo.resolve.ChainedPlaceResolver
import org.tatrman.geo.resolve.ModelPoiResolver
import org.tatrman.geo.resolve.NominatimPlaceResolver
import shared.ktor.KtorConfigFactory
import shared.libs.db.common.DatabaseConnection
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.logging.IncomingCallLoggingInterceptor
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("org.tatrman.geo.Application")

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "geo", 7121)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    installKtorServerBase(KtorConfigFactory.fromConfig(config, "geo", 7121))

    val openTelemetry =
        createOpenTelemetrySdk(
            OtelEndpointConfig(
                serviceName = "geo",
                protocol = System.getenv("GEO_OTEL_PROTOCOL") ?: "grpc",
            ),
            enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled"),
        )
    val metrics = GeoMetrics(openTelemetry.getMeter("geo"))

    val useFixture =
        config.hasPath("geo.use-fixture-model") && config.getBoolean("geo.use-fixture-model")
    val discovery = pickDiscovery(config, useFixture)
    val llmFallback = buildLlmFallbackClient(config)

    // Networked Nominatim resolver (A9.4), backed by a durable boundary cache — service-local
    // Postgres when geo.db is enabled, else in-memory. RÚIAN (CZ admin) is the remaining source
    // follow-on; today the anchor is geocoded via Nominatim.
    val nominatimClient =
        NominatimClient(
            baseUrl = config.getString("geo.nominatim.base-url"),
            userAgent = config.getString("geo.nominatim.user-agent"),
        )
    val (boundaryStore, boundaryDb) = buildBoundaryStore(config)
    // Geocode first (cities resolve to coordinates); fall through to an in-model POI join for a
    // non-geocodable anchor ("the Prague depot") when the package has a POI entity (A9 POI-in-model).
    val placeResolver =
        ChainedPlaceResolver(
            listOf(
                NominatimPlaceResolver(nominatimClient, boundaryStore),
                ModelPoiResolver(discovery),
            ),
        )
    val geoService =
        GeoGroundingService(
            discovery = discovery,
            placeResolver = placeResolver,
            llmFallback = llmFallback,
            llmModel = config.getString("geo.llm-fallback.model"),
            metrics = metrics,
            // RS-19 / D-T2 capability posture surfaced through GetStatus.
            nominatimConfigured = config.getString("geo.nominatim.base-url").isNotBlank(),
            postgisAvailable = boundaryStore is PostgresBoundaryStore,
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
            .addService(geoService)
            .apply { if (reflectionEnabled) addService(ProtoReflectionServiceV1.newInstance()) }
            .build()

    launch {
        grpcServer.start()
        log.info("geo gRPC server started on port {} (reflection={})", grpcPort, reflectionEnabled)
        grpcServer.awaitTermination()
    }

    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/status") {
            call.respond(
                buildJsonObject {
                    put("service", "geo")
                    put("grpc_port", grpcPort)
                    put("llm_fallback", llmFallback != null)
                },
            )
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Shutting down geo gRPC server")
        grpcServer.shutdown()
        if (discovery is AutoCloseable) discovery.close()
        nominatimClient.close()
        llmFallback?.close()
        boundaryDb?.close()
    }
}

/**
 * The durable boundary cache (A9.4). When `geo.db.enabled` is set, connect the service-local
 * Postgres via the shared [DatabaseConnection], run Flyway, and back the resolver with a
 * [PostgresBoundaryStore]; otherwise fall back to an in-memory store. Returns the connection so the
 * caller can close it on shutdown.
 */
private fun buildBoundaryStore(config: Config): Pair<BoundaryStore, DatabaseConnection?> {
    val ttl = java.time.Duration.ofDays(config.getLong("geo.boundary-cache-ttl-days"))
    val dbEnabled = config.hasPath("geo.db.enabled") && config.getBoolean("geo.db.enabled")
    if (!dbEnabled || config.getString("geo.db.host").isBlank()) {
        log.info("geo boundary cache: in-memory (ttl {} days)", ttl.toDays())
        return InMemoryBoundaryStore(ttl) to null
    }
    val db = DatabaseConnection.fromConfig(config, "geo.db")
    db.init()
    Flyway
        .configure()
        .dataSource(db.getDataSource())
        .load()
        .migrate()
    log.info("geo boundary cache: Postgres at {} (ttl {} days)", config.getString("geo.db.host"), ttl.toDays())
    return PostgresBoundaryStore(db, ttl) to db
}

private fun pickDiscovery(
    config: Config,
    useFixture: Boolean,
): GeoDiscovery {
    if (useFixture) return EmptyGeoDiscovery
    return MetaV1GeoDiscovery.forAddress(
        host = config.getString("metadata.host"),
        port = config.getInt("metadata.port"),
    )
}

private fun buildLlmFallbackClient(config: Config): LlmGatewayClient? {
    if (!config.getBoolean("geo.llm-fallback.enabled")) return null
    val baseUrl = config.getString("geo.llm-fallback.gateway-url").trim()
    if (baseUrl.isEmpty()) {
        log.warn("geo.llm-fallback.enabled = true but gateway-url is empty — running rules-only")
        return null
    }
    val timeoutMs = config.getLong("geo.llm-fallback.timeout-ms")
    val apiKey = config.getString("geo.llm-fallback.api-key").takeIf { it.isNotBlank() }
    log.info("geo llm-gateway fallback configured at {} (timeout {}ms)", baseUrl, timeoutMs)
    return KtorLlmGatewayClient(baseUrl = baseUrl, timeoutMs = timeoutMs, apiKey = apiKey)
}
