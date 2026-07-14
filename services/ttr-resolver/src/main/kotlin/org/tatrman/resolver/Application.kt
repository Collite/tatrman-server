// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.mcp.identity.IdentityPolicy
import org.tatrman.mcp.identity.RequestContext
import org.tatrman.resolver.client.GrpcFuzzyClient
import org.tatrman.resolver.client.GrpcNlpClient
import org.tatrman.resolver.grpc.ResolverGrpcService
import org.tatrman.resolver.mcp.ResolveDoor
import org.tatrman.resolver.mcp.ResolveDoorHandler
import org.tatrman.resolver.mcp.installResolveDoor
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.LiveMetadataRegistryAdapter
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.token.ResumeTokenCodec
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.ktor.mcp.McpKtorConfig
import shared.ktor.mcp.installMcpKtorBase
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

    // RG-P6.S1 — the `resolve.bind:v1` MCP door, co-hosted in-process on its own
    // port (RS-22: the service is the door). A second embedded CIO server keeps the
    // MCP streamable-HTTP surface isolated from the health server; the OBO gate is
    // fail-closed when `mcp.require-identity` is on (H-2). Zero LLM below the door.
    val doorPort = config.getInt("mcp.port")
    // Loopback by default (RG-P6 review D): the door must not be reachable off-host
    // unless a deployment explicitly fronts it with an auth-terminating ingress and
    // sets `mcp.host = 0.0.0.0`. The JWT is decoded, not signature-verified, here.
    val doorHost = if (config.hasPath("mcp.host")) config.getString("mcp.host") else "127.0.0.1"
    val requireIdentity = config.getBoolean("mcp.require-identity")
    // Identity-source policy (RG-P6 review A): production trusts ONLY an OBO Bearer
    // token. `mcp.trust-network = true` (dev / behind a trusted terminator) re-opens
    // the `X-User-Id` header, the `user_id` arg, and the `admin:` role convention.
    val trustNetwork = config.hasPath("mcp.trust-network") && config.getBoolean("mcp.trust-network")
    val identityPolicy = if (trustNetwork) IdentityPolicy.PERMISSIVE else IdentityPolicy.TOKEN_ONLY
    val requestContext = RequestContext()
    val door = ResolveDoor(pipeline::resolve)
    val doorHandler = ResolveDoorHandler(door, requireIdentity, identityPolicy)
    val doorServer =
        embeddedServer(
            factory = CIO,
            rootConfig =
                serverConfig {
                    module {
                        intercept(ApplicationCallPipeline.Plugins) {
                            requestContext.authHeader.set(call.request.header("Authorization"))
                            requestContext.userIdHeader.set(call.request.header("X-User-Id"))
                            try {
                                proceed()
                            } finally {
                                requestContext.authHeader.remove()
                                requestContext.userIdHeader.remove()
                            }
                        }
                        installMcpKtorBase(
                            McpKtorConfig(
                                serviceName = "ttr-resolver-door",
                                serverPort = doorPort,
                                connectionIdleTimeoutSeconds = 120,
                            ),
                            OpenTelemetry.noop(),
                        )
                        installResolveDoor(door, doorHandler, requestContext)
                    }
                },
            configure = {
                connectionIdleTimeoutSeconds = 120
                connectors.add(
                    EngineConnectorBuilder().apply {
                        port = doorPort
                        host = doorHost
                    },
                )
            },
        )
    launch {
        doorServer.start(wait = false)
        log.info(
            "resolve door (MCP streamable HTTP) started on {}:{} (require-identity={}, trust-network={})",
            doorHost,
            doorPort,
            requireIdentity,
            trustNetwork,
        )
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
        log.info("Shutting down resolver gRPC server + resolve door")
        doorServer.stop(1000, 2000)
        grpcServer.shutdown()
        nlpClient.close()
        fuzzyClient.close()
    }
}

/**
 * Build the HMAC resume-token codec from config (S-3 key discipline, RS-26).
 * `resolver.resume-token.keys` is a map key_id → base64url secret (≥32 bytes);
 * `active-key-id` signs. Hardened per RG-P6 review K/L:
 *  - a missing `max-age-seconds` no longer means "never expires" — it falls back to
 *    [DEFAULT_RESUME_MAX_AGE_SECONDS] so a dropped config key cannot silently make
 *    tokens eternal;
 *  - an empty key set is FAIL-CLOSED (the service refuses to boot) unless
 *    `allow-ephemeral-key = true` is set explicitly — the ephemeral per-process key
 *    breaks stateless resume across replicas/restarts, so it is opt-in dev only;
 *  - configured keys shorter than 32 bytes are rejected (weak HMAC secret).
 */
private fun buildResumeTokenCodec(config: Config): ResumeTokenCodec {
    val path = "resolver.resume-token"
    val maxAge =
        if (config.hasPath("$path.max-age-seconds")) {
            config.getLong("$path.max-age-seconds")
        } else {
            DEFAULT_RESUME_MAX_AGE_SECONDS
        }
    if (config.hasPath("$path.keys") && !config.getConfig("$path.keys").isEmpty) {
        val keysConfig = config.getConfig("$path.keys")
        val keys =
            keysConfig
                .root()
                .keys
                .associateWith { keyId ->
                    val secret =
                        java.util.Base64
                            .getUrlDecoder()
                            .decode(keysConfig.getString(keyId))
                    require(secret.size >= MIN_RESUME_KEY_BYTES) {
                        "resolver.resume-token.keys.$keyId is ${secret.size}B; need ≥$MIN_RESUME_KEY_BYTES (RS-26)"
                    }
                    secret
                }
        val active = config.getString("$path.active-key-id")
        log.info("resume-token codec: {} key(s), active={}, max-age={}s", keys.size, active, maxAge)
        return ResumeTokenCodec(keys, active, maxAgeSeconds = maxAge)
    }
    val allowEphemeral = config.hasPath("$path.allow-ephemeral-key") && config.getBoolean("$path.allow-ephemeral-key")
    require(allowEphemeral) {
        "resolver.resume-token.keys is empty and allow-ephemeral-key is not set — refusing to boot on an " +
            "ephemeral per-process key (stateless resume would break across replicas/restarts). Configure a key, " +
            "or set resolver.resume-token.allow-ephemeral-key = true for local dev only."
    }
    val ephemeral =
        java.security.SecureRandom
            .getInstanceStrong()
            .generateSeed(32)
    log.warn("resolver.resume-token.keys unset — using an EPHEMERAL dev key; resume tokens will not survive a restart")
    return ResumeTokenCodec(mapOf("dev" to ephemeral), activeKeyId = "dev", maxAgeSeconds = maxAge)
}

/** Default replay window when `max-age-seconds` is unset — never "no expiry" (review K). */
private const val DEFAULT_RESUME_MAX_AGE_SECONDS = 3600L

/** Minimum configured HMAC secret length (review L). */
private const val MIN_RESUME_KEY_BYTES = 32
