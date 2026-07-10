package org.tatrman.fuzzy

import com.typesafe.config.ConfigFactory
import io.grpc.ServerInterceptors
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.fuzzy.api.GrpcService
import org.tatrman.fuzzy.api.configureRoutes
import org.tatrman.fuzzy.config.ConfigLoader
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.EchoMatcher
import org.tatrman.fuzzy.core.Lemmatizer
import org.tatrman.fuzzy.core.NoopLemmatizer
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.db.DatabaseFactory
import org.tatrman.fuzzy.loader.EchoCatalog
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.loader.MetadataLoaderSource
import org.tatrman.fuzzy.loader.MetadataServiceClient
import org.tatrman.fuzzy.loader.StaticLoaderSource
import org.tatrman.fuzzy.telemetry.EchoTelemetry
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("org.tatrman.fuzzy.Application")

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "echo", 8080)
    KtorServerBootstrap.createServer(serverConfig) { module(serverConfig) }.start(wait = true)
}

/**
 * Runs a composed `SELECT pk, col` and maps each row to a [Candidate]. Used by
 * the `metadata` loader; relies on the Exposed connection opened by
 * [DatabaseFactory.connect]. Column order is `(pk, value)` per [buildSelect].
 */
private fun fetchSqlCandidates(sql: String): List<Candidate> {
    val results = mutableListOf<Candidate>()
    transaction {
        exec(sql) { rs ->
            while (rs.next()) {
                results.add(Candidate.fromValues(rs.getString(1), rs.getString(2)))
            }
        }
    }
    return results
}

fun Application.module(serverConfig: KtorServerConfig) {
    installKtorServerBase(serverConfig)

    val telemetry = EchoTelemetry()

    install(io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry) {
        setOpenTelemetry(telemetry.openTelemetry)
    }

    val config = ConfigLoader.load()

    val typesafeConfig =
        com.typesafe.config.ConfigFactory
            .load()
    configureSecurity(typesafeConfig)

    // Czech lemmatisation via Kadmos (Phase 2.3). Disabled at v1 → folded-
    // surface matching only. The HTTP client is built only when `nlp.enabled`
    // is true (off in the default config).
    val nlpHttpClient: io.ktor.client.HttpClient? =
        if (config.nlp.enabled) {
            io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
                install(io.ktor.client.plugins.HttpTimeout) {
                    requestTimeoutMillis = config.nlp.timeoutMs
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = config.nlp.timeoutMs
                }
            }
        } else {
            null
        }
    val lemmatizer: Lemmatizer =
        if (nlpHttpClient != null) {
            log.info("Czech lemmatisation enabled via Kadmos at ${config.nlp.baseUrl} (lang=${config.nlp.lang})")
            org.tatrman.fuzzy.core
                .NlpLemmatizer(nlpHttpClient, config.nlp.baseUrl, config.nlp.lang)
        } else {
            NoopLemmatizer
        }

    // Loader source selection.
    //   static   (default): read the in-repo JSON catalog — no DB, local/CI-friendly.
    //   metadata          : the full ai-platform behaviour — ask Ariadne for the
    //                       fuzzy columns, compose `SELECT pk, col FROM table`,
    //                       query the warehouse, populate the catalog. Opens a DB
    //                       pool + an Ariadne gRPC channel, both owned here and
    //                       torn down in ApplicationStopping.
    val metadataChannel: io.grpc.ManagedChannel? =
        if (config.loaderSource.source == "metadata") {
            io.grpc.ManagedChannelBuilder
                .forAddress(config.metadata.host, config.metadata.port)
                .usePlaintext()
                .build()
        } else {
            null
        }

    val loaderSource: LoaderSource =
        if (metadataChannel != null) {
            val database =
                config.database
                    ?: error(
                        "echo.loader.source=metadata requires a warehouse connection " +
                            "(set echo.type=postgres|mssql + echo.<type>.*); none configured",
                    )
            DatabaseFactory.connect(database)
            log.info(
                "Loader source: metadata — ariadne at {}:{} schema={} sourceNamespace='{}' (fuzzy column indexing)",
                config.metadata.host,
                config.metadata.port,
                config.metadata.schema,
                config.metadata.namespace,
            )
            val client = MetadataServiceClient(metadataChannel, config.metadata.schema, config.metadata.timeoutMs)
            MetadataLoaderSource(
                client = client,
                dialect = database,
                sourceNamespace = config.metadata.namespace,
                fetchCandidates = ::fetchSqlCandidates,
                telemetry = telemetry,
            )
        } else {
            val catalog: Map<String, List<Candidate>> = EchoCatalog.fromResource("/echo-catalog.json")
            log.info("Loader source: static (in-repo catalog, ${catalog.size} categories)")
            StaticLoaderSource(catalog)
        }

    val repository = StringRepository(config, loaderSource, telemetry, lemmatizer)
    val echoMatcher =
        EchoMatcher(repository, telemetry, lemmatizer, idfEnabled = config.tokenBasedConfig.idfEnabled)
    val grpcService = GrpcService(echoMatcher, telemetry)

    // Tolerate clients' keepalive (30s pings, incl. idle) instead of GOAWAY too_many_pings.
    val grpcServer =
        NettyServerBuilder
            .forPort(config.grpcPort)
            .permitKeepAliveTime(20, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .addService(
                ServerInterceptors.intercept(
                    grpcService as io.grpc.BindableService,
                    SecurityInterceptor(typesafeConfig) as io.grpc.ServerInterceptor,
                ),
            ).apply { if (config.grpcReflectionEnabled) addService(ProtoReflectionServiceV1.newInstance()) }
            .build()

    launch {
        grpcServer.start()
        log.info("gRPC server started on port ${config.grpcPort} (reflection=${config.grpcReflectionEnabled})")
        grpcServer.awaitTermination()
    }

    configureRoutes(echoMatcher, repository, telemetry, typesafeConfig)

    routing {
        get("/health") {
            call.respond(buildJsonObject { put("status", JsonPrimitive("UP")) })
        }
        get("/ready") {
            if (repository.isCatalogReady()) {
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable)
            }
        }
    }

    environment.monitor.subscribe(ApplicationStopping) {
        repository.close()
        nlpHttpClient?.close()
        grpcServer.shutdown()
        metadataChannel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
    }
}
