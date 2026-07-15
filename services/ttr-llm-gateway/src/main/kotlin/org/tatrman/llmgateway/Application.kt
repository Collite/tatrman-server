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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.llmgateway.admin.AdminAuth
import org.tatrman.llmgateway.auth.ConfigKeyValidator
import org.tatrman.llmgateway.auth.KeyPrincipal
import org.tatrman.llmgateway.auth.KeyValidator
import org.tatrman.llmgateway.auth.bearerToken
import org.tatrman.llmgateway.auth.requireKey
import org.tatrman.llmgateway.cache.CacheEnvelope
import org.tatrman.llmgateway.cache.CacheKey
import org.tatrman.llmgateway.cache.CacheReplay
import org.tatrman.llmgateway.cache.ResponseCache
import org.tatrman.llmgateway.cache.StreamBodyAssembler
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.engine.CircuitBreaker
import org.tatrman.llmgateway.engine.InferenceEngine
import org.tatrman.llmgateway.governance.BudgetService
import org.tatrman.llmgateway.governance.BudgetUsageRepo
import org.tatrman.llmgateway.governance.GovernanceLoad
import org.tatrman.llmgateway.governance.RateLimiter
import org.tatrman.llmgateway.governance.Settle
import org.tatrman.llmgateway.governance.TokenEstimator
import org.tatrman.llmgateway.governance.Usage
import org.tatrman.llmgateway.observability.GatewayMetrics
import org.tatrman.llmgateway.observability.PromptLogRecord
import org.tatrman.llmgateway.observability.PromptLogWriter
import org.tatrman.llmgateway.provider.PassthroughHandler
import org.tatrman.llmgateway.provider.ProviderRegistry
import org.tatrman.llmgateway.provider.ProviderResult
import org.tatrman.llmgateway.provider.RegistryEntry
import org.tatrman.llmgateway.provider.RegistryResolution
import org.tatrman.llmgateway.provider.RequestedType
import org.tatrman.llmgateway.provider.ResponseEnrichment
import org.tatrman.llmgateway.provider.UpstreamUsage
import org.tatrman.llmgateway.provider.anthropic.AnthropicHandler
import org.tatrman.llmgateway.provider.pumpSse
import org.tatrman.llmgateway.stream.StreamObservation
import org.tatrman.llmgateway.stream.StreamUsage
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
    otelOverride: OpenTelemetry? = null, // tests inject an in-memory-exporter SDK; prod builds the OTLP one
) {
    val serverConfig = KtorConfigFactory.fromConfig(config, "ttr-llm-gateway", 7280)
    installKtorServerBase(serverConfig)
    // Single OpenAI-shaped error surface for every route (contracts §1.7).
    install(StatusPages) { installGatewayErrorHandling() }

    // OTLP trace/metric/log export + the Logback→OTLP bridge; noop when telemetry is disabled.
    val otel =
        otelOverride ?: createOpenTelemetrySdk(
            OtelEndpointConfig(
                serviceName = "ttr-llm-gateway",
                protocol = System.getenv("LLM_GATEWAY_OTEL_PROTOCOL") ?: "grpc",
            ),
            enabled = serverConfig.telemetryEnabled,
        )
    // Server span per request + W3C traceparent extraction (F-1: continue the caller's trace — the 1.x
    // gateway DROPPED the inbound context; this is the named fix). Manual attempt spans nest under it.
    install(KtorServerTelemetry) { setOpenTelemetry(otel) }
    val tracer = otel.getTracer("ttr-llm-gateway")

    val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    log.info(
        "config loaded: {} catalog models, {} providers, {} teams",
        gateway.catalog.models.size,
        gateway.providers.providers.size,
        gateway.governance.teams.size,
    )

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

    // Data-plane key validation (D-1). PG present → the real PG-backed validator (LG-P4·S1): config teams
    // upserted + seeded keys imported (G-3), then `virtual_keys` lookups behind a ≤30 s cache. Storeless
    // (Docker-free unit tier / dev) → the config-backed seeded-key validator; no issuance/revocation there.
    val governance = pg?.let { GovernanceLoad.apply(it.db, gateway.governance) }
    val keyValidator: KeyValidator = governance?.validator ?: ConfigKeyValidator(gateway.governance)

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
    // Gateway metric set (LG-P5·S2, §6); circuit_state gauges over the observed providers.
    val gwMetrics = GatewayMetrics(metrics).also { it.registerCircuitGauges(gateway.providers.providers.keys, circuit) }
    val engine = InferenceEngine(gateway.providers, circuit, metrics = gwMetrics, tracer = tracer)
    // Prompt-log sink (F-1): async write-behind over PG. Present only with a store.
    val promptLog = pg?.let { PromptLogWriter(it.db, this, metrics) }

    // Admission + budgets (LG-P4·S2). Rate limiting rides Redis (fail-open on outage); money budgets ride PG
    // (so they exist only with a store — storeless boots skip both). Team rpm / budget / cost-center prefixes
    // come from governance.yaml; per-key overrides ride on the validated KeyPrincipal (min-wins, D-3).
    val rateLimiter = RateLimiter(redis, metrics)
    val budgetService =
        pg?.let {
            BudgetService(
                usage = BudgetUsageRepo(it.db),
                teamBudgets =
                    gateway.governance.teams
                        .mapNotNull { t -> t.budget?.let { b -> t.id to b } }
                        .toMap(),
                metrics = metrics,
            )
        }
    val teamRpm = gateway.governance.teams.associate { it.id to it.rateLimit?.requestsPerMinute }
    val teamCostCenter = gateway.governance.teams.associate { it.id to it.costCenterPrefix }

    // Exact-match response cache (LG-P5·S1, E-1). Best-effort over Redis (a down Redis just misses); keyed
    // by the logical (resolved) model so fallback-served responses cache under the caller's name.
    val responseCache = ResponseCache(redis, gateway.providers.cache, metrics)
    val cacheCfg = gateway.providers.cache

    // Admin key API (LG-P4·S3): Keycloak-JWT gated, over the S1 issuance service. Present only when
    // configured (an `admin { }` block with the realm public key) AND PG-backed (issuance needs the store).
    val adminAuth =
        if (config.hasPath("admin.enabled") &&
            config.getBoolean("admin.enabled") &&
            config.hasPath("admin.realmPublicKey")
        ) {
            AdminAuth(
                issuer = config.optStr("admin.issuer"),
                audience = config.optStr("admin.audience"),
                realmPublicKeyBase64 = config.getString("admin.realmPublicKey"),
                requiredRole = config.optStr("admin.role") ?: "llm-gateway-admin",
            )
        } else {
            null
        }
    val keyService = governance?.keyService

    // Attribution (D-2, contracts §1.2): X-Cost-Center refines within the key's team and is prefix-validated
    // (a key can never charge a foreign bucket → 400); absent ⇒ the team default. X-Turn-Ref is trace-only.
    fun costCenterFor(
        team: String,
        header: String?,
    ): String {
        val prefix = teamCostCenter[team] ?: team
        if (header != null && !header.startsWith(prefix)) {
            throw GatewayException(
                GatewayError.Validation("X-Cost-Center '$header' must start with the key's team prefix '$prefix'"),
            )
        }
        return header ?: prefix
    }

    // Admission gate (architecture §3.1 order): rate-limit (hard 429) → money-budget pre-check (hard 429).
    // Reuses the GatewayError rendering — RateLimit carries Retry-After, BudgetExceeded the x-gateway-reason.
    fun admit(principal: KeyPrincipal) {
        val rl = rateLimiter.check(principal.keyId, teamRpm[principal.team], principal.rpmOverride)
        if (!rl.allowed) throw GatewayException(GatewayError.RateLimit(rl.retryAfterSeconds * 1000))
        val budget = budgetService?.precheck(principal.team, principal.budgetUsdOverride)
        if (budget != null && !budget.allowed) throw GatewayException(GatewayError.BudgetExceeded())
    }

    monitor.subscribe(ApplicationStopping) {
        runCatching { promptLog?.close() }
        runCatching { pg?.close() }
        runCatching { redis?.close() }
        runCatching { upstreamClient.close() }
    }

    routing {
        // ── Data plane (virtual key required, D-1) ──────────────────────────────────────────────
        post("/v1/chat/completions") {
            val principal = call.requireKey(keyValidator)
            admit(principal) // rate-limit + budget pre-check (429 on breach) BEFORE any upstream work
            val req = ChatRequest.parse(call.receiveText()) // bad body → SerializationException → 400 (§1.7)
            val startMs = System.currentTimeMillis()
            // Attribution (D-2): prefix-validate X-Cost-Center against the key's team (foreign bucket → 400).
            val costCenter = costCenterFor(principal.team, call.request.headers["X-Cost-Center"])

            val promptText = messagesText(req.messages)

            // One Settle record, three sinks (§5.5): budget, prompt-log (async), metrics (tokens+cost).
            fun dispatch(
                s: Settle,
                responseText: String,
                status: String,
            ) {
                budgetService?.settle(s)
                promptLog?.enqueue(PromptLogRecord(s, promptText, responseText, status))
                gwMetrics.tokens(s.teamId, s.servedProvider, s.servedModel, "input", s.usage.promptTokens)
                gwMetrics.tokens(s.teamId, s.servedProvider, s.servedModel, "output", s.usage.completionTokens)
                gwMetrics.cost(s.teamId, s.servedProvider, s.servedModel, s.costUsd)
            }

            // Build + fan out the settle for one served (non-cached) response (D-4). Usage precedence is the
            // caller's (tap UsageChunk → non-stream usage → estimate).
            fun settle(
                serving: RegistryEntry,
                fallbackFrom: String?,
                stripped: List<String>,
                tokens: Usage,
                estimated: Boolean,
                ttfbMs: Long?,
                responseText: String,
                status: String = "SUCCESS",
            ) = dispatch(
                Settle(
                    keyId = principal.keyId,
                    teamId = principal.team,
                    costCenter = costCenter,
                    turnRef = call.request.headers["X-Turn-Ref"],
                    requestedModel = req.model ?: "",
                    servedProvider = serving.target.providerName,
                    servedModel = serving.target.upstream,
                    fallbackFrom = fallbackFrom,
                    strippedParams = stripped,
                    usage = tokens,
                    costUsd =
                        ResponseEnrichment.computeCost(
                            serving.model,
                            tokens.promptTokens,
                            tokens.completionTokens,
                        ),
                    estimated = estimated,
                    cached = false,
                    ttfbMs = ttfbMs,
                    durationMs = System.currentTimeMillis() - startMs,
                    traceId = currentTraceId(),
                ),
                responseText,
                status,
            )

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

            // Exact-match cache (LG-P5·S1, E-1): keyed by the logical (resolved) model. `bypass`/`refresh`
            // skip the read (both still store on the miss below); a positive catalog TTL gates cacheability.
            val cacheMode = call.request.headers["X-Gateway-Cache"]
            val cacheKey =
                if (cacheCfg.enabled && entry.model.cacheTtlSeconds > 0) {
                    CacheKey.of(cacheCfg.keyPrefix, entry.model.name, req)
                } else {
                    null
                }
            if (cacheKey != null && cacheMode != "bypass" && cacheMode != "refresh") {
                val hit = responseCache.get(cacheKey)
                if (hit == null) {
                    gwMetrics.cacheMiss()
                } else {
                    gwMetrics.cacheHit()
                    // A hit is still a request: the rate-limit token was spent in admit(); settle-as-cached
                    // records the SAVED cost (echoed) but adds nothing to budget_usage (Settle.cached=true).
                    call.response.header("X-Gateway-Provider", hit.servedProvider) // original serving provider (P-2)
                    call.response.header("X-Gateway-Model", hit.servedModel)
                    call.response.header("X-Gateway-Cache", "hit")
                    dispatch(
                        Settle(
                            keyId = principal.keyId,
                            teamId = principal.team,
                            costCenter = costCenter,
                            turnRef = call.request.headers["X-Turn-Ref"],
                            requestedModel = req.model ?: "",
                            servedProvider = hit.servedProvider,
                            servedModel = hit.servedModel,
                            fallbackFrom = null,
                            strippedParams = emptyList(),
                            usage = Usage(hit.promptTokens, hit.completionTokens, hit.cachedTokens),
                            costUsd = hit.costUsd,
                            estimated = false,
                            cached = true,
                            ttfbMs = 0,
                            durationMs = System.currentTimeMillis() - startMs,
                            traceId = currentTraceId(),
                        ),
                        assistantText(hit.body),
                        "SUCCESS",
                    )
                    if (req.stream) {
                        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                            writeStringUtf8(CacheReplay.streamEvents(hit)) // synthetic 2-event replay (§1.4)
                            flush()
                        }
                    } else {
                        call.respond(HttpStatusCode.OK, CacheReplay.nonStreamBody(hit))
                    }
                    return@post
                }
            }

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
                            val settleTap = StreamSettleTap()
                            // Reassemble the completion off the same frames so a stream MISS populates the cache.
                            val assembler = StreamBodyAssembler(serving.target.upstream)
                            try {
                                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                                    pumpSse(
                                        frames = open.frames.onEach { assembler.observe(it) },
                                        out = this,
                                        tap = { settleTap.observe(it) }, // capture usage + TTFB for the settle
                                        parser = tap,
                                        model = serving.model,
                                        heartbeatMillis = gateway.providers.sse.heartbeatSeconds * 1000,
                                    )
                                }
                            } finally {
                                // Settle at stream end — success, error, OR client-abandon (D-4). Usage is the
                                // tap's UsageChunk when it arrived, else a flagged tokenizer estimate.
                                val streamed = settleTap.usage
                                val (tokens, estimated) =
                                    if (streamed != null) {
                                        Usage(
                                            streamed.promptTokens,
                                            streamed.completionTokens,
                                            streamed.cachedTokens,
                                        ) to
                                            false
                                    } else {
                                        Usage(
                                            TokenEstimator.estimatePromptTokens(req.messages, serving.model.name),
                                            0,
                                        ) to
                                            true
                                    }
                                settle(
                                    serving,
                                    open.fallbackFrom,
                                    open.strippedParams,
                                    tokens,
                                    estimated,
                                    settleTap.ttfbMs,
                                    assembler.content(),
                                    if (assembler.completed()) "SUCCESS" else "ERROR",
                                )
                                // Store on a clean stream completion (finish_reason seen, content, no tool_calls):
                                // an errored/abandoned stream leaves finishReason null → not cached.
                                if (cacheKey != null && assembler.completed() && assembler.cacheable()) {
                                    val cost =
                                        ResponseEnrichment.computeCost(
                                            serving.model,
                                            tokens.promptTokens,
                                            tokens.completionTokens,
                                        )
                                    val enriched =
                                        ResponseEnrichment.chat(
                                            ProviderResult(
                                                200,
                                                assembler.assembled(),
                                                UpstreamUsage(tokens.promptTokens, tokens.completionTokens),
                                                null,
                                            ),
                                            serving.model,
                                        )
                                    responseCache.put(
                                        cacheKey,
                                        CacheEnvelope(
                                            enriched,
                                            serving.target.providerName,
                                            serving.target.upstream,
                                            tokens.promptTokens,
                                            tokens.completionTokens,
                                            tokens.cachedTokens,
                                            cost,
                                            System.currentTimeMillis(),
                                        ),
                                        entry.model.cacheTtlSeconds,
                                    )
                                }
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
                    // Usage precedence (D-4): the upstream `usage` field, else a flagged tokenizer estimate.
                    val u = outcome.result.usage
                    val (tokens, estimated) =
                        if (u != null) {
                            Usage(u.promptTokens, u.completionTokens) to false
                        } else {
                            Usage(TokenEstimator.estimatePromptTokens(req.messages, serving.model.name), 0) to true
                        }
                    settle(
                        serving,
                        outcome.fallbackFrom,
                        outcome.strippedParams,
                        tokens,
                        estimated,
                        null,
                        assistantText(outcome.result.body),
                    )
                    val enriched = ResponseEnrichment.chat(outcome.result, serving.model)
                    // Store on a successful miss (also runs for bypass/refresh — they skip the read, not the store).
                    if (cacheKey != null) {
                        val cost =
                            ResponseEnrichment.computeCost(serving.model, tokens.promptTokens, tokens.completionTokens)
                        responseCache.put(
                            cacheKey,
                            CacheEnvelope(
                                enriched,
                                serving.target.providerName,
                                serving.target.upstream,
                                tokens.promptTokens,
                                tokens.completionTokens,
                                tokens.cachedTokens,
                                cost,
                                System.currentTimeMillis(),
                            ),
                            entry.model.cacheTtlSeconds,
                        )
                    }
                    call.respond(HttpStatusCode.OK, enriched)
                }
                is InferenceEngine.ChainResult.Failed -> {
                    call.response.header("X-Gateway-Provider", outcome.lastAttempted.target.providerName)
                    call.response.header("X-Gateway-Model", outcome.lastAttempted.target.upstream)
                    call.respondGatewayError(outcome.error)
                }
            }
        }
        post("/v1/embeddings") {
            val principal = call.requireKey(keyValidator)
            admit(principal) // embeddings cost money too — same rate-limit + budget gate as chat
            // D-2: prefix-validate X-Cost-Center against the key's team (foreign bucket → 400)
            val costCenter = costCenterFor(principal.team, call.request.headers["X-Cost-Center"])
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
                // Settle embeddings on the input-only usage (no completion tokens). The tokenizer estimate is
                // chat-message-shaped, so for embeddings we settle the reported tokens or 0 (flagged estimated).
                val inTokens = result.usage?.promptTokens ?: 0
                val embSettle =
                    Settle(
                        keyId = principal.keyId,
                        teamId = principal.team,
                        costCenter = costCenter,
                        turnRef = call.request.headers["X-Turn-Ref"],
                        requestedModel = modelName ?: "",
                        servedProvider = entry.target.providerName,
                        servedModel = entry.target.upstream,
                        fallbackFrom = null,
                        strippedParams = emptyList(),
                        usage = Usage(inTokens, 0),
                        costUsd = ResponseEnrichment.computeCost(entry.model, inTokens, 0),
                        estimated = result.usage == null,
                        cached = false,
                        ttfbMs = null,
                        durationMs = 0,
                        traceId = currentTraceId(),
                    )
                budgetService?.settle(embSettle)
                // embeddings have no text response
                promptLog?.enqueue(PromptLogRecord(embSettle, input.toString(), "", "SUCCESS"))
                gwMetrics.tokens(principal.team, entry.target.providerName, entry.target.upstream, "input", inTokens)
                gwMetrics.cost(principal.team, entry.target.providerName, entry.target.upstream, embSettle.costUsd)
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

        // ── Admin plane (Keycloak JWT, realm role llm-gateway-admin — LG-P4·S3, contracts §1.8) ──
        // Registered only when configured + PG-backed. In-service JWT verification is the only gate (D-1).
        if (adminAuth != null && keyService != null) {
            post("/admin/keys") {
                if (!call.adminOk(adminAuth)) return@post
                val body =
                    runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            buildJsonObject { put("error", "invalid JSON body") },
                        )
                val team = body["team"]?.jsonPrimitive?.contentOrNull
                val name = body["name"]?.jsonPrimitive?.contentOrNull
                if (team.isNullOrBlank() || name.isNullOrBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "'team' and 'name' are required") },
                    )
                }
                val issued =
                    try {
                        keyService.issueKey(team, name)
                    } catch (e: IllegalArgumentException) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            buildJsonObject {
                                put(
                                    "error",
                                    e.message ?: "unknown team",
                                )
                            },
                        )
                    }
                call.respond(
                    HttpStatusCode.Created,
                    buildJsonObject {
                        put("id", issued.row.id)
                        put("key", issued.plaintext) // plaintext returned EXACTLY once (D-1)
                        put("team", issued.row.teamId)
                        put("name", issued.row.name)
                        put("created_at", issued.row.createdAt.toString())
                    },
                )
            }
            get("/admin/keys") {
                if (!call.adminOk(adminAuth)) return@get
                val team = call.request.queryParameters["team"]
                val rows =
                    if (team !=
                        null
                    ) {
                        keyService.list(team)
                    } else {
                        gateway.governance.teams.flatMap { keyService.list(it.id) }
                    }
                call.respond(
                    buildJsonObject {
                        putJsonArray("data") {
                            rows.forEach { r ->
                                add(
                                    buildJsonObject {
                                        // NEVER key_hash / plaintext (contracts §1.8)
                                        put("id", r.id)
                                        put("team", r.teamId)
                                        put("name", r.name)
                                        put("created_at", r.createdAt.toString())
                                        put("revoked_at", r.revokedAt?.toString())
                                        put("last_used_at", r.lastUsedAt?.toString())
                                    },
                                )
                            }
                        }
                    },
                )
            }
            delete("/admin/keys/{id}") {
                if (!call.adminOk(adminAuth)) return@delete
                val id = call.parameters["id"]
                if (id.isNullOrBlank()) {
                    return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "missing key id") },
                    )
                }
                keyService.revoke(id) // idempotent; takes effect within the validator's ≤30 s TTL
                call.respond(HttpStatusCode.NoContent)
            }
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

private fun Config.optStr(path: String): String? = if (hasPath(path)) getString(path) else null

/** The current OTel trace id (32-hex) for the prompt-log row, or null when there is no active/valid span. */
private fun currentTraceId(): String? {
    val ctx =
        io.opentelemetry.api.trace.Span
            .current()
            .spanContext
    return if (ctx.isValid) ctx.traceId else null
}

/** Flatten the chat `messages` to plain text for the prompt-log `prompt_text` (FTS source). */
private fun messagesText(messages: JsonArray): String =
    messages.joinToString("\n") { m ->
        when (val content = (m as? JsonObject)?.get("content")) {
            is JsonPrimitive -> content.contentOrNull ?: ""
            is JsonArray ->
                content
                    .mapNotNull {
                        (it as? JsonObject)
                            ?.get(
                                "text",
                            )?.jsonPrimitive
                            ?.contentOrNull
                    }.joinToString(" ")
            else -> ""
        }
    }

/** The assistant message text from a chat.completion body — the prompt-log `response_text`. */
private fun assistantText(body: JsonObject): String {
    val message = (body["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
    return when (val c = message?.get("content")) {
        is JsonPrimitive -> c.contentOrNull ?: ""
        null -> ""
        else -> c.toString()
    }
}

/**
 * Admin-plane gate: verify the Keycloak JWT + role and respond 401/403 on failure (contracts §1.8). Returns
 * true when the caller may proceed. Admin responses are plain JSON, not the OpenAI error envelope.
 */
private suspend fun io.ktor.server.application.ApplicationCall.adminOk(auth: AdminAuth): Boolean =
    when (auth.authenticate(bearerToken())) {
        is AdminAuth.Result.Ok -> true
        AdminAuth.Result.Forbidden -> {
            respond(
                HttpStatusCode.Forbidden,
                buildJsonObject {
                    put("error", "forbidden")
                    put("detail", "requires the llm-gateway-admin realm role")
                },
            )
            false
        }
        else -> {
            respond(HttpStatusCode.Unauthorized, buildJsonObject { put("error", "unauthorized") })
            false
        }
    }

/** Captures the settle-relevant tap signals off a live stream (last usage chunk + TTFB) for the finally settle. */
private class StreamSettleTap {
    @Volatile var usage: StreamUsage? = null

    @Volatile var ttfbMs: Long? = null

    fun observe(o: StreamObservation) {
        when (o) {
            is StreamObservation.FirstToken -> ttfbMs = o.ttfbMs
            is StreamObservation.UsageChunk -> usage = o.usage
            else -> {}
        }
    }
}
