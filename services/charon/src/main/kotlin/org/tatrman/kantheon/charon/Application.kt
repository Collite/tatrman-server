package org.tatrman.kantheon.charon

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.charon.core.CharonMoveExecutor
import org.tatrman.kantheon.charon.core.ConnectionRegistry
import org.tatrman.kantheon.charon.core.HikariConnectionProvider
import org.tatrman.kantheon.charon.core.MovePlanner
import org.tatrman.kantheon.charon.endpoints.GrpcWorkerGatewayFactory
import org.tatrman.kantheon.charon.endpoints.RedisEndpoint
import org.tatrman.kantheon.charon.grpc.CharonServiceImpl
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.charon.Application")

/**
 * Bootstrap. Per `EXAMPLES.md` §1b the `App.kt` does only wiring; business
 * logic lives under `core/` and `grpc/`.
 *
 * Stage 1.3 fills in the Redis endpoint (charon/plan.md §3.3 T1–T6):
 *  - [CharonMoveExecutor] now wires both Seaweed (Stage 1.2) and Redis
 *    (Stage 1.3) into the move-pipe seam.
 *  - The Lettuce [RedisClient] / [StatefulRedisConnection] are built at
 *    startup and shut down on `ApplicationStopping`.
 *  - `/metrics` route serves the Prometheus scrape.
 *  - `/ready` returns a static 200 once the process is up. It does NOT
 *    probe S3/Redis reachability — that is checked lazily at the first
 *    move (per the architecture §7 readiness contract: endpoints
 *    validated lazily rather than gating the pod). The gRPC server
 *    binds before the HTTP server starts, so a bind failure crashes the
 *    process before `/ready` can answer. (review-006 R8.8: KDoc trimmed
 *    to match what the route actually checks.)
 */
fun main() {
    val config = ConfigFactory.load()
    val httpPort = config.getInt("charon.http.port")
    val grpcPort = config.getInt("charon.grpc.port")

    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val s3Client = buildS3Client(config)
    val redisConnection = buildRedisConnection(config)
    Runtime.getRuntime().addShutdownHook(Thread { redisConnection.close() })
    val redisMaxValueBytes = config.getLong("charon.redis.max-value-bytes")
    val redisDefaultTtlSeconds =
        if (config.hasPath("charon.redis.default-ttl-s")) {
            config.getLong("charon.redis.default-ttl-s")
        } else {
            null
        }
    // Phase 2 — the named-connection registry + JDBC provider. The registry is
    // loaded lazily-validated (a broken DB connection does NOT gate the pod —
    // architecture §7 + plan §4 Stage 2.3); a missing file ⇒ blob-only pod.
    val connectionsFile = File(config.getString("charon.connections.path"))
    val connectionRegistry = ConnectionRegistry.fromFile(connectionsFile)
    val dbProvider = HikariConnectionProvider()
    Runtime.getRuntime().addShutdownHook(Thread { dbProvider.close() })
    log.info("Charon connection registry: {} connection(s) {}", connectionRegistry.ids().size, connectionRegistry.ids())

    // Phase 3 — worker gateways (POLARS = Steropes worker.v1; METIS = metis.v1).
    // Targets are optional; an unset engine isn't wired on this pod.
    val workerFactory =
        GrpcWorkerGatewayFactory(
            polarsTarget =
                if (config.hasPath(
                        "charon.worker.polars-target",
                    )
                ) {
                    config.getString("charon.worker.polars-target")
                } else {
                    null
                },
            metisTarget =
                if (config.hasPath(
                        "charon.worker.metis-target",
                    )
                ) {
                    config.getString("charon.worker.metis-target")
                } else {
                    null
                },
        )
    Runtime.getRuntime().addShutdownHook(Thread { workerFactory.close() })

    val executor =
        CharonMoveExecutor(
            s3Client = s3Client,
            redisConnection = redisConnection,
            redisMaxValueBytes = redisMaxValueBytes,
            registry = meterRegistry,
            redisDefaultTtlSeconds = redisDefaultTtlSeconds,
            connectionRegistry = connectionRegistry,
            dbProvider = dbProvider,
            workerGatewayFactory = workerFactory,
        )
    val service =
        CharonServiceImpl(
            planner = MovePlanner(),
            executor = executor,
        )
    val grpcServer =
        NettyServerBuilder
            .forPort(grpcPort)
            .permitKeepAliveTime(20, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .maxInboundMessageSize(33554432) // 32 MiB; matches ai-platform/ariadne
            .addService(service)
            .build()
    Runtime.getRuntime().addShutdownHook(Thread { grpcServer.shutdownNow() })
    grpcServer.start()
    log.info(
        "Charon gRPC server started on :{} (Stage 1.3; Seaweed + Redis live, others stubbed)",
        grpcPort,
    )

    embeddedServer(
        Netty,
        port = httpPort,
        host = "0.0.0.0",
        module = { module(meterRegistry, connectionRegistry, connectionsFile) },
    ).start(wait = true)
}

private fun buildS3Client(config: Config): S3Client {
    val endpoint = config.getString("charon.s3.endpoint")
    val region = config.getString("charon.s3.region")
    val accessKey =
        if (config.hasPath("charon.s3.access-key")) config.getString("charon.s3.access-key") else ""
    val secretKey =
        if (config.hasPath("charon.s3.secret-key")) config.getString("charon.s3.secret-key") else ""
    val builder =
        S3Client
            .builder()
            .region(Region.of(region))
            .endpointOverride(URI.create(endpoint))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
    if (accessKey.isNotBlank() && secretKey.isNotBlank()) {
        builder.credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)),
        )
    }
    return builder.build()
}

private fun buildRedisConnection(config: Config): StatefulRedisConnection<ByteArray, ByteArray> {
    val url = config.getString("charon.redis.url")
    val client = RedisEndpoint.clientFromUrl(url)
    // `ByteArrayCodec` for binary-safe value access. The Arrow IPC
    // stream lives in the value bytes; the schema fingerprint sidecar
    // is a UTF-8 hex string. `ByteArrayCodec` gives us `<byte[], byte[]>`
    // for K/V — keys are ASCII strings (encoded via [RedisEndpoint.keyOf]);
    // values are raw bytes. This is the only clean way to carry the
    // Arrow IPC stream through Redis.
    val connection =
        client.connect(
            io.lettuce.core.codec
                .ByteArrayCodec(),
        )
    Runtime.getRuntime().addShutdownHook(Thread { client.shutdown() })
    return connection
}

fun Application.module(
    meterRegistry: PrometheusMeterRegistry,
    connectionRegistry: ConnectionRegistry = ConnectionRegistry.of(emptyList()),
    connectionsFile: File? = null,
) {
    routing {
        get("/health") { call.respond(mapOf("status" to "UP")) }
        get("/ready") {
            // Lazily-validated connections: a broken DB connection does NOT
            // make the pod unready (architecture §7 / plan §4 Stage 2.3 —
            // "one broken DB ≠ unready pod"). The registered set is reported
            // as informational degraded-set context.
            call.respond(
                mapOf(
                    "status" to "UP",
                    "stage" to "2.3",
                    "connections" to connectionRegistry.ids().sorted(),
                ),
            )
        }
        get("/status") {
            call.respond(
                mapOf(
                    "service" to "charon",
                    "stage" to "2.3",
                    "endpoints" to "seaweed (live), redis (live), db_table (live); worker_df stubbed",
                    "connections" to connectionRegistry.ids().sorted(),
                ),
            )
        }
        // Registry reload (cluster-internal; contracts §4). Re-reads the
        // connections file and atomically swaps the live set.
        post("/refresh") {
            val file = connectionsFile
            if (file == null || !file.isFile) {
                call.respond(mapOf("status" to "no-op", "reason" to "no connections file"))
            } else {
                connectionRegistry.refresh(file.readText())
                call.respond(mapOf("status" to "reloaded", "connections" to connectionRegistry.ids().sorted()))
            }
        }
        get("/metrics") {
            call.respondText(meterRegistry.scrape(), ContentType.Text.Plain)
        }
    }
    monitor.subscribe(ApplicationStopping) {
        log.info("Charon shutting down")
    }
}
