// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.mssql

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.ServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import shared.logging.IncomingCallLoggingInterceptor
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.apache.arrow.memory.RootAllocator
import org.slf4j.LoggerFactory
import org.tatrman.worker.mssql.client.GrpcTranslatorClient
import org.tatrman.worker.mssql.client.TranslatorClient
import org.tatrman.worker.mssql.client.TranslatorHealth
import org.tatrman.worker.mssql.connection.ConnectionPoolManager
import org.tatrman.worker.mssql.grpc.WorkerServiceImpl
import org.tatrman.worker.mssql.pipeline.ExecutePipeline
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

private val log = LoggerFactory.getLogger("org.tatrman.worker.mssql.Application")

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "mssql", 7295)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    installKtorServerBase(KtorConfigFactory.fromConfig(config, "mssql", 7295))

    // OTel SDK init: configures OTLP trace/metric/log exporters AND installs the bridge
    // into the Logback OpenTelemetryAppender so all SLF4J logs are forwarded to OTLP → Alloy → Loki.
    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "mssql",
            protocol = System.getenv("MSSQL_OTEL_PROTOCOL") ?: "grpc",
        ),
        enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled"),
    )

    val useFixture = config.hasPath("worker.use-fixture") && config.getBoolean("worker.use-fixture")

    val pool = ConnectionPoolManager.fromConfig(config)
    val translatorRaw = pickTranslator(config, useFixture)
    val translatorHealth = TranslatorHealth()
    val translator = TranslatorHealth.Wrapping(translatorRaw, translatorHealth)

    // Startup probes — see issue: operators were unable to tell from the boot log whether
    // the DB pool was healthy or the translator was reachable.
    logStartupProbes(pool, translatorRaw, translatorHealth, useFixture)

    val limits =
        ExecutePipeline.ExecutionLimits(
            defaultBatchSizeRows = config.getInt("execution.default-batch-size-rows"),
            maxBatchSizeRows = config.getInt("execution.max-batch-size-rows"),
            defaultTimeoutSeconds = config.getLong("execution.default-timeout-seconds"),
            maxTimeoutSeconds = config.getLong("execution.max-timeout-seconds"),
            maxBlobBytesPerCell = config.getLong("execution.max-blob-bytes-per-cell"),
        )

    val allocator = RootAllocator(Long.MAX_VALUE)
    val pipeline = ExecutePipeline(pool = pool, translator = translator, limits = limits, allocator = allocator)
    val service =
        WorkerServiceImpl(
            pipeline = pipeline,
            pool = pool,
            translatorHealth = translatorHealth,
            capabilities =
                WorkerServiceImpl.WorkerCapabilities(
                    engineName = config.getString("worker.engine"),
                    engineVersion = config.getString("worker.engine-version"),
                    limits = limits,
                ),
        )

    val grpcPort = config.getInt("grpc.port")
    val maxMessageSize = config.getInt("grpc.max-message-size-bytes")
    val reflectionEnabled =
        config.hasPath("grpc.reflection-enabled") && config.getBoolean("grpc.reflection-enabled")
    val grpcServer =
        ServerBuilder
            .forPort(grpcPort)
            .maxInboundMessageSize(maxMessageSize)
            .intercept(IncomingCallLoggingInterceptor())
            .addService(service)
            .apply { if (reflectionEnabled) addService(ProtoReflectionServiceV1.newInstance()) }
            .build()

    launch {
        grpcServer.start()
        log.info("Mssql (MSSQL worker) gRPC server started on port {} (reflection={})", grpcPort, reflectionEnabled)
        grpcServer.awaitTermination()
    }

    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") {
            val ready = pool.supportedConnections.isNotEmpty() || useFixture
            if (ready) {
                call.respond(
                    buildJsonObject {
                        put("status", "UP")
                        put("connections", pool.supportedConnections.size.toString())
                    },
                )
            } else {
                call.respond(
                    io.ktor.http.HttpStatusCode.ServiceUnavailable,
                    buildJsonObject {
                        put("status", "NOT_READY")
                        put("reason", "no connections configured")
                    },
                )
            }
        }
        get("/status") {
            call.respond(
                buildJsonObject {
                    put("service", "mssql")
                    put("engine", config.getString("worker.engine"))
                    put("engine_version", config.getString("worker.engine-version"))
                    put("grpc_port", grpcPort)
                    putJsonArray("supported_connections") { pool.supportedConnections.toList().forEach { add(it) } }
                    put("active_queries", pipeline.activeQueries)
                    putJsonObject("pool_stats") {
                        pool.poolStats().forEach { (id, s) ->
                            putJsonObject(id) {
                                put("active", s.active)
                                put("idle", s.idle)
                                put("max", s.max)
                                put("awaiting", s.awaiting)
                            }
                        }
                    }
                },
            )
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Shutting down Mssql")
        grpcServer.shutdown()
        runCatching { pool.close() }
        if (translatorRaw is AutoCloseable) runCatching { translatorRaw.close() }
        runCatching { allocator.close() }
    }
}

private fun Application.logStartupProbes(
    pool: ConnectionPoolManager,
    translator: TranslatorClient,
    translatorHealth: TranslatorHealth,
    useFixture: Boolean,
) {
    if (pool.supportedConnections.isEmpty()) {
        log.warn("No JDBC connections configured (connections {} in HOCON is empty).")
    } else {
        log.info("Probing {} JDBC connection(s) at startup...", pool.supportedConnections.size)
        // Probe is fast (5s timeout per connection); doing it inline so the result lands
        // before the "Application started" line — operators see DB health up front.
        pool.probeAll().forEach { r ->
            if (r.connected) {
                log.info("JDBC connection_id={} OK", r.connectionId)
            } else {
                log.warn(
                    "JDBC connection_id={} FAILED at startup: {} (worker will keep running; " +
                        "GetStatus.connections[{}].connected=false until the next successful probe)",
                    r.connectionId,
                    r.lastError,
                    r.connectionId,
                )
            }
        }
    }

    if (useFixture) {
        log.info("Translator probe skipped (worker.use-fixture = true).")
        translatorHealth.recordSuccess() // fixture stub is always "reachable"
        return
    }
    launch {
        log.info("Probing translator at startup...")
        translatorHealth.probe(translator)
    }
}

private fun pickTranslator(
    config: Config,
    useFixture: Boolean,
): TranslatorClient {
    if (useFixture) {
        log.warn(
            "Mssql booting in fixture mode (worker.use-fixture = true). " +
                "Translator client is a no-op stub; production deployments must flip this to false.",
        )
        return object : TranslatorClient {
            override suspend fun unparse(request: org.tatrman.translate.v1.UnparseRequest) =
                org.tatrman.translate.v1
                    .UnparseResponse
                    .newBuilder()
                    .setOutput("")
                    .setContext(request.context)
                    .build()

            override suspend fun probe() = Unit
        }
    }
    return GrpcTranslatorClient(
        host = config.getString("translate.host"),
        port = config.getInt("translate.port"),
        // Was defaulting to the client's hardcoded 30s — the conf value was never wired.
        timeoutSeconds = config.getLong("translate.timeout-seconds"),
    )
}
