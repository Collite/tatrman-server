// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.config

import com.typesafe.config.Config

/**
 * LG-P1·S1 — `providers.conf` (HOCON, contracts §2.2). Loaded via typesafe-config rather than kaml
 * because it carries env-var references (`${?AZURE_OPENAI_ENDPOINT}`) and layered defaults. One key
 * per provider in 2.0 (C-5) — the `keyEnv` name is resolved to a `Key` per call, never at construction.
 */
data class ProvidersConfig(
    val providers: Map<String, ProviderDef>,
    val retry: RetryConfig,
    val circuit: CircuitConfig,
    val sse: SseConfig,
    val cache: CacheConfig,
)

data class ProviderDef(
    val kind: String, // "openai-wire" | "anthropic"
    val baseUrl: String,
    val auth: AuthDef,
    val apiVersion: String? = null, // azure
    val urlPattern: String? = null, // azure deployment rewrite
    val version: String? = null, // anthropic API version (anthropic-version header)
    val defaultMaxTokens: Int? = null, // anthropic: max_tokens fallback (Anthropic requires the field)
)

data class AuthDef(
    val header: String,
    val scheme: String? = null, // e.g. "Bearer"
    val keyEnv: String, // env var NAME (resolved per call, C-5) — not the secret itself
)

data class RetryConfig(
    val maxAttempts: Int,
    val initialBackoffMs: Long,
    val maxBackoffMs: Long,
    val wallClockBudgetMs: Long,
)

data class CircuitConfig(
    val failureThreshold: Int,
    val cooldownMs: Long,
)

data class SseConfig(
    val heartbeatSeconds: Long,
)

data class CacheConfig(
    val enabled: Boolean,
    val keyPrefix: String,
)

/** Map a resolved HOCON [Config] (root of `providers.conf`) into [ProvidersConfig]. */
fun providersFrom(config: Config): ProvidersConfig {
    val provs = config.getConfig("providers")
    val providers =
        provs.root().keys.associateWith { key ->
            val p = provs.getConfig(key)
            val auth = p.getConfig("auth")
            ProviderDef(
                kind = p.getString("kind"),
                baseUrl = p.getString("baseUrl"),
                auth =
                    AuthDef(
                        header = auth.getString("header"),
                        scheme = auth.stringOrNull("scheme"),
                        keyEnv = auth.getString("keyEnv"),
                    ),
                apiVersion = p.stringOrNull("apiVersion"),
                urlPattern = p.stringOrNull("urlPattern"),
                version = p.stringOrNull("version"),
                defaultMaxTokens = if (p.hasPath("defaultMaxTokens")) p.getInt("defaultMaxTokens") else null,
            )
        }
    val retry = config.getConfig("retry")
    val circuit = config.getConfig("circuit")
    return ProvidersConfig(
        providers = providers,
        retry =
            RetryConfig(
                maxAttempts = retry.getInt("maxAttempts"),
                initialBackoffMs = retry.getLong("initialBackoffMs"),
                maxBackoffMs = retry.getLong("maxBackoffMs"),
                wallClockBudgetMs = retry.getLong("wallClockBudgetMs"),
            ),
        circuit =
            CircuitConfig(
                failureThreshold = circuit.getInt("failureThreshold"),
                cooldownMs = circuit.getLong("cooldownMs"),
            ),
        sse = SseConfig(heartbeatSeconds = config.getConfig("sse").getLong("heartbeatSeconds")),
        cache =
            config.getConfig("cache").let {
                CacheConfig(enabled = it.getBoolean("enabled"), keyPrefix = it.getString("keyPrefix"))
            },
    )
}

private fun Config.stringOrNull(path: String): String? = if (hasPath(path)) getString(path) else null
