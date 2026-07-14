// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

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
import org.tatrman.resolver.client.GrpcFuzzyClient
import org.tatrman.resolver.client.GrpcNlpClient
import org.tatrman.resolver.grpc.ResolverGrpcService
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.LiveMetadataRegistryAdapter
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.token.ResumeTokenCodec
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.logging.IncomingCallLoggingInterceptor
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("org.tatrman.resolver.Application")

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "resolver", 7275)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    installKtorServerBase(KtorConfigFactory.fromConfig(config, "resolver", 7275))

    val grpcPort = config.getInt("grpc.port")
    val maxMessageSize = config.getInt("grpc.max-message-size-bytes")
    val reflectionEnabled =
        config.hasPath("grpc.reflection-enabled") && config.getBoolean("grpc.reflection-enabled")

    // Upstream clients — ttr-nlp (parse + capability matrix) and ttr-fuzzy
    // (vocabulary match). NEITHER is an LLM (RS-23).
    val nlpClient =
        GrpcNlpClient(config.getString("nlp.host"), config.getInt("nlp.port"), config.getLong("nlp.deadline-seconds"))
    val fuzzyClient =
        GrpcFuzzyClient(
            config.getString("fuzzy.host"),
            config.getInt("fuzzy.port"),
            config.getLong("fuzzy.deadline-seconds"),
        )

    val defaultThresholds =
        ResolverThresholds(
            bind = config.getDouble("resolver.threshold-bind"),
            ambiguityGap = config.getDouble("resolver.threshold-ambiguity-gap"),
            exact = config.getDouble("resolver.threshold-exact"),
            maxOptions = config.getInt("resolver.max-options"),
        )
    // Snapshot-fed registry over the one-channel seam (RS-24). Startup uses the
    // E3-β step-one live-metadata adapter; a per-request `Registry` override wins.
    val registry = SnapshotRegistry(LiveMetadataRegistryAdapter(), defaultThresholds)

    // HMAC resume-token codec (RS-26). Keys come from config so they rotate by
    // key_id without a redeploy; the active key signs, all listed keys verify
    // (rotation window). Key management is chart discipline (S-3).
    val tokenCodec = buildResumeTokenCodec(config)

    val pipeline = ResolverPipeline(nlpClient, fuzzyClient, registry, siblings = emptyMap(), tokenCodec = tokenCodec)
    val resolverService = ResolverGrpcService(pipeline)

    val grpcServer =
        NettyServerBuilder
            .forPort(grpcPort)
            .permitKeepAliveTime(20, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .maxInboundMessageSize(maxMessageSize)
            .intercept(IncomingCallLoggingInterceptor())
            .addService(resolverService)
            .apply { if (reflectionEnabled) addService(ProtoReflectionServiceV1.newInstance()) }
            .build()

    launch {
        grpcServer.start()
        log.info("resolver gRPC server started on port {} (reflection={})", grpcPort, reflectionEnabled)
        grpcServer.awaitTermination()
    }

    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/status") {
            call.respond(
                buildJsonObject {
                    put("service", "resolver")
                    put("grpc_port", grpcPort)
                    put("llm", false) // RS-23: ZERO LLM — the door line is the determinism line
                },
            )
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Shutting down resolver gRPC server")
        grpcServer.shutdown()
        nlpClient.close()
        fuzzyClient.close()
    }
}

/**
 * Build the HMAC resume-token codec from config (S-3 key discipline, RS-26).
 * `resolver.resume-token.keys` is a map key_id → base64url secret; `active-key-id`
 * signs. In dev, when no keys are configured, mint an ephemeral key so the service
 * boots — a WARN makes the non-persistence explicit (tokens won't survive restart).
 */
private fun buildResumeTokenCodec(config: Config): ResumeTokenCodec {
    val path = "resolver.resume-token"
    if (config.hasPath("$path.keys") && !config.getConfig("$path.keys").isEmpty) {
        val keysConfig = config.getConfig("$path.keys")
        val keys =
            keysConfig
                .root()
                .keys
                .associateWith {
                    java.util.Base64
                        .getUrlDecoder()
                        .decode(keysConfig.getString(it))
                }
        val active = config.getString("$path.active-key-id")
        log.info("resume-token codec: {} key(s), active={}", keys.size, active)
        return ResumeTokenCodec(keys, active)
    }
    val ephemeral =
        java.security.SecureRandom
            .getInstanceStrong()
            .generateSeed(32)
    log.warn("resolver.resume-token.keys unset — using an EPHEMERAL dev key; resume tokens will not survive a restart")
    return ResumeTokenCodec(mapOf("dev" to ephemeral), activeKeyId = "dev")
}
