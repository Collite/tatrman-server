// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.llmgateway.auth.ConfigKeyValidator
import org.tatrman.llmgateway.auth.requireKey
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.engine.CircuitBreaker
import org.tatrman.llmgateway.engine.InferenceEngine
import org.tatrman.llmgateway.provider.PassthroughHandler
import org.tatrman.llmgateway.provider.ProviderRegistry
import org.tatrman.llmgateway.provider.RegistryResolution
import org.tatrman.llmgateway.provider.RequestedType
import org.tatrman.llmgateway.provider.ResponseEnrichment
import org.tatrman.llmgateway.provider.anthropic.AnthropicHandler
import org.tatrman.llmgateway.provider.pumpSse
import org.tatrman.llmgateway.stream.TapParser
import org.tatrman.llmgateway.store.Pg
import org.tatrman.llmgateway.store.RedisConn
import org.tatrman.llmgateway.wire.ChatRequest
import org.tatrman.llmgateway.wire.GatewayError
import org.tatrman.llmgateway.wire.GatewayException
import org.tatrman.llmgateway.wire.installGatewayErrorHandling
import org.tatrman.llmgateway.wire.openAiErrorBody
import org.tatrman.llmgateway.wire.respondGatewayError
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

private val log = LoggerFactory.getLogger("org.tatrman.llmgateway.Application")

// Static catalog ⇒ a static `created` timestamp on /v1/models (contracts §1.5). Fixed generation epoch;
// bump only when the catalog itself changes (config edit + redeploy).
private const val CATALOG_EPOCH = 1_752_000_000L

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "ttr-llm-gateway", 7280)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

fun Application.module(
    config: Config,
    gateway: GatewayConfig = ConfigLoader.loadFromResources(),
) {
    val serverConfig = KtorConfigFactory.fromConfig(config, "ttr-llm-gateway", 7280)
    installKtorServerBase(serverConfig)
    // Single OpenAI-shaped error surface for every route (contracts §1.7).
    install(StatusPages) { installGatewayErrorHandling() }

    // OTLP trace/metric/log export + the Logback→OTLP bridge; noop when telemetry is disabled.
    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "ttr-llm-gateway",
            protocol = System.getenv("LLM_GATEWAY_OTEL_PROTOCOL") ?: "grpc",
        ),
        enabled = serverConfig.telemetryEnabled,
    )

    val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    log.info(
        "config loaded: {} catalog models, {} providers, {} teams",
        gateway.catalog.models.size,
        gateway.providers.providers.size,
        gateway.governance.teams.size,
    )

    // Data-plane key validation (D-1) — interim seeded-key impl; PG-backed issued keys land LG-P4·S1.
    val keyValidator = ConfigKeyValidator(gateway.governance)

    // Stores (T4). Built only when enabled so the skeleton boots storeless in unit tests; a Redis
    // outage never fails boot (lazy connect), but PG must be up at startup for Flyway to migrate.
    val pg =
        if (config.hasPath("db.enabled") && config.getBoolean("db.enabled")) {
            Pg.fromConfig(config).also {
                it.migrate()
                log.info("Postgres connected + Flyway migrated")
            }
        } else {
            null
        }
    val redis =
        if (config.hasPath("redis.enabled") && config.getBoolean("redis.enabled")) {
            RedisConn.fromConfig(config).also { log.info("Redis client created") }
        } else {
            null
        }

    // Provider layer (LG-P2): one pooled Ktor CIO client; per-provider keys resolved from env (C-5).
    val upstreamClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = gateway.providers.retry.wallClockBudgetMs
                connectTimeoutMillis = 5_000
            }
        }
    val registry =
        ProviderRegistry.build(
            gateway,
            passthrough = PassthroughHandler(upstreamClient),
            anthropic = AnthropicHandler(upstreamClient),
        )
    log.info("provider registry: {} models", registry.size)

    // Resilience engine (LG-P3·S2): typed retries + fallback chains + per-instance circuit-breaker-lite.
    val circuit = CircuitBreaker(gateway.providers.circuit)
    val engine = InferenceEngine(gateway.providers, circuit)

    monitor.subscribe(ApplicationStopping) {
        runCatching { pg?.close() }
        runCatching { redis?.close() }
        runCatching { upstreamClient.close() }
    }

    routing {
        // ── Data plane (virtual key required, D-1) ──────────────────────────────────────────────
        post("/v1/chat/completions") {
            call.requireKey(keyValidator)
            val req = ChatRequest.parse(call.receiveText()) // bad body → SerializationException → 400 (§1.7)
            // Full three-tier resolution (LG-P3·S1): alias → literal → model_tags soft-match. Unknown name →
            // 404; a wrong-type (embedding) match or no-model-no-tags → 400 Validation (never a silent default).
            // requestedModel (req.model) vs served (entry.target.upstream) both flow to the Settle record in P3·S2.
            val entry =
                when (val r = registry.resolve(req.model, req.modelTags, RequestedType.CHAT)) {
                    is RegistryResolution.Resolved -> r.entry
                    is RegistryResolution.Invalid -> throw GatewayException(GatewayError.Validation(r.reason))
                    is RegistryResolution.NotFound ->
                        return@post call.respond(
                            HttpStatusCode.NotFound,
                            openAiErrorBody(
                                "invalid_request_error",
                                "model_not_found",
                                "unknown model '${r.requested}'",
                            ),
                        )
                }
            if (entry.handler == null) {
                return@post call.respond(
                    HttpStatusCode.NotImplemented,
                    openAiErrorBody(
                        "server_error",
                        "not_implemented",
                        "no handler for provider '${entry.target.providerName}'",
                    ),
                )
            }
            // The fallback chain (self first) + the resilience engine own retry/fallback/circuit (LG-P3·S2).
            val chain = registry.chainFor(entry)

            if (req.stream) {
                // Before-first-token rule (design §3.2): the whole attempt loop runs BEFORE the SSE writer
                // attaches. openStream peeks the first frame per attempt in THIS coroutineScope (bound to the
                // call, so a client disconnect cancels the upstream read). Only a successful attempt's stream
                // attaches; an all-exhausted stream commits a real HTTP status — no in-band error frame.
                coroutineScope {
                    when (val open = engine.openStream(chain, req, this)) {
                        is InferenceEngine.StreamOpen.Attached -> {
                            val serving = open.serving
                            call.response.header("X-Gateway-Provider", serving.target.providerName)
                            call.response.header("X-Gateway-Model", serving.target.upstream)
                            val tap = TapParser(serving.target.providerName, serving.target.upstream)
                            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                                pumpSse(
                                    frames = open.frames,
                                    out = this,
                                    tap = {}, // observations feed settlement/metrics in LG-P5; no consumer yet
                                    parser = tap,
                                    model = serving.model,
                                    heartbeatMillis = gateway.providers.sse.heartbeatSeconds * 1000,
                                )
                            }
                        }
                        is InferenceEngine.StreamOpen.Failed -> {
                            // Pre-first-token exhaustion → proper HTTP status (the S2 deviation, now resolved).
                            call.response.header("X-Gateway-Provider", open.lastAttempted.target.providerName)
                            call.response.header("X-Gateway-Model", open.lastAttempted.target.upstream)
                            call.respondGatewayError(open.error)
                        }
                    }
                }
                return@post
            }

            when (val outcome = engine.complete(chain, req)) {
                is InferenceEngine.ChainResult.Ok -> {
                    val serving = outcome.serving
                    call.response.header("X-Gateway-Provider", serving.target.providerName)
                    call.response.header("X-Gateway-Model", serving.target.upstream)
                    call.respond(HttpStatusCode.OK, ResponseEnrichment.chat(outcome.result, serving.model))
                }
                is InferenceEngine.ChainResult.Failed -> {
                    call.response.header("X-Gateway-Provider", outcome.lastAttempted.target.providerName)
                    call.response.header("X-Gateway-Model", outcome.lastAttempted.target.upstream)
                    call.respondGatewayError(outcome.error)
                }
            }
        }
        post("/v1/embeddings") {
            call.requireKey(keyValidator)
            // Embeddings ride passthrough end-to-end (B-T5) — raw JsonObject, no B-T2 model.
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val input =
                json["input"]
                    ?: throw GatewayException(GatewayError.Validation("missing required field 'input'"))
            if (input is JsonObject) {
                // 1.x non-standard {text|texts} shape dropped (LG-D3) — OpenAI input is a string or array.
                throw GatewayException(
                    GatewayError.Validation(
                        "'input' must be a string or array (the 1.x {text|texts} shape is not supported)",
                    ),
                )
            }
            val modelName = json["model"]?.jsonPrimitive?.contentOrNull
            // Embeddings have no model_tags tier; resolve by name only. Wrong-type (a chat model) or a
            // missing model → 400 Validation; a truly-unknown name → 404 (mirrors the chat route).
            val entry =
                when (val r = registry.resolve(modelName, emptyList(), RequestedType.EMBEDDING)) {
                    is RegistryResolution.Resolved -> r.entry
                    is RegistryResolution.Invalid -> throw GatewayException(GatewayError.Validation(r.reason))
                    is RegistryResolution.NotFound ->
                        return@post call.respond(
                            HttpStatusCode.NotFound,
                            openAiErrorBody(
                                "invalid_request_error",
                                "model_not_found",
                                "unknown embedding model '${r.requested}'",
                            ),
                        )
                }
            val handler =
                entry.handler
                    ?: return@post call.respond(
                        HttpStatusCode.NotImplemented,
                        openAiErrorBody("server_error", "not_implemented", "provider not implemented"),
                    )

            val result = handler.embed(json, entry.target, entry.key)
            if (result.status !in 200..299) {
                call.response.header("X-Gateway-Provider", entry.target.providerName)
                call.respondGatewayError(entry.errorConverter.convert(result.status, result.body))
            } else {
                call.response.header("X-Gateway-Provider", entry.target.providerName)
                call.response.header("X-Gateway-Model", entry.target.upstream)
                call.respond(HttpStatusCode.OK, ResponseEnrichment.embeddings(result, entry.model))
            }
        }
        get("/v1/models") {
            call.requireKey(keyValidator)
            call.respond(
                buildJsonObject {
                    put("object", "list")
                    putJsonArray("data") {
                        // one entry per catalog row — aliases are NOT listed as models (contracts §1.5)
                        gateway.catalog.models.forEach { m ->
                            add(
                                buildJsonObject {
                                    put("id", m.name)
                                    put("object", "model")
                                    put("created", CATALOG_EPOCH)
                                    put("owned_by", m.provider)
                                    putJsonArray("tags") { m.tags.forEach { add(it) } }
                                },
                            )
                        }
                    }
                },
            )
        }

        // ── Ops plane (unauthenticated, cluster-internal) ───────────────────────────────────────
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/health/live") { call.respond(buildJsonObject { put("status", "UP") }) }
        // Truthful readiness (F-1): config parsed (we booted) AND PG AND Redis reachable — live probes
        // each call, never fake-green. Storeless (disabled) counts as DOWN — the service can't serve.
        get("/health/ready") {
            val pgOk = pg?.probe() ?: false
            val redisOk = redis?.probe() ?: false
            val ready = pgOk && redisOk
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                buildJsonObject {
                    put("status", if (ready) "UP" else "NOT_READY")
                    putJsonObject("checks") {
                        put("config", "UP")
                        put("postgres", if (pgOk) "UP" else "DOWN")
                        put("redis", if (redisOk) "UP" else "DOWN")
                    }
                },
            )
        }
        // Truthful per-provider circuit state (F-1). Empty until a provider has been observed; per-instance
        // (counters are not shared across replicas by design, C-4/architecture §5).
        get("/health/providers") {
            call.respond(
                buildJsonObject {
                    putJsonObject("providers") {
                        circuit.snapshot().forEach { (provider, health) ->
                            putJsonObject(provider) {
                                put("circuit", health.state)
                                put("consecutive_failures", health.consecutiveFailures)
                                if (health.lastErrorClass != null) {
                                    put("last_error", health.lastErrorClass)
                                } else {
                                    put("last_error", JsonNull)
                                }
                            }
                        }
                    }
                },
            )
        }
        // A real (empty) Prometheus registry scrape; gateway counters land LG-P5·S2.
        get("/metrics") { call.respondText(metrics.scrape()) }
    }

    log.info(
        "ttr-llm-gateway 2.0 booted (port {}, telemetry={})",
        serverConfig.serverPort,
        serverConfig.telemetryEnabled,
    )
}
