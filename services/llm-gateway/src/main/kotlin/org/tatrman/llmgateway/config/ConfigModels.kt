// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.config

import kotlinx.serialization.Serializable

/*
 * LG-P1·S1 — static config model (contracts §2; C-2: static files, DB-ready schema, stable ids,
 * no positional semantics). catalog.yaml + governance.yaml are kaml-decoded into the @Serializable
 * types here; providers.conf (HOCON) maps into ProvidersConfig (see Providers.kt). ConfigValidator
 * enforces the cross-file invariants at startup — all errors at once, never first-request.
 */

// ── catalog.yaml (contracts §2.1) ───────────────────────────────────────────────────────────────

@Serializable
data class Catalog(
    val models: List<CatalogModel> = emptyList(),
)

@Serializable
data class CatalogModel(
    val id: String, // stable id (DB-ready) — never reused
    val name: String, // caller-facing name (tier-2 literal match)
    val aliases: List<String> = emptyList(), // tier-1 map (C-1)
    val provider: String, // must exist in providers.conf
    val upstream: String, // deployment (azure) / model id (others) — 1.x fullName
    val type: String, // "chat" | "embedding"
    val tags: List<String> = emptyList(), // tier-3 soft match
    val pricing: Pricing? = null, // USD per 1M tokens; both sides required for chat
    val cacheTtlSeconds: Long = 0, // 0 = uncacheable
    val fallback: List<String> = emptyList(), // ordered chain of catalog ids (C-4)
    val dimensions: Map<String, String> = emptyMap(), // reserved: tier / task_kind (GI-3)
    val reasoning: Boolean = false, // reasoning model (gpt-5 / o-series): the OpenAI-wire handler strips
    // `temperature` (only the default 1 is supported) and maps `max_tokens` -> `max_completion_tokens`.
    // Env-driven via catalog `${AZURE_OPENAI_REASONING:-false}` so it tracks the deployment per cluster.
) {
    val isChat: Boolean get() = type == "chat"
    val isEmbedding: Boolean get() = type == "embedding"
}

@Serializable
data class Pricing(
    val input: Double,
    val output: Double? = null, // required for chat; embeddings are input-only (contracts §1.6)
)

// ── governance.yaml (contracts §2.3) ────────────────────────────────────────────────────────────

@Serializable
data class GovernanceConfig(
    val teams: List<Team> = emptyList(),
    val keys: List<SeededKey> = emptyList(), // seeded imports only (G-3); issued keys live in PG
)

@Serializable
data class Team(
    val id: String,
    val costCenterPrefix: String, // X-Cost-Center must start with this (D-2)
    val budget: BudgetDef? = null,
    val rateLimit: RateLimitDef? = null,
)

@Serializable
data class BudgetDef(
    val id: String,
    val usdPerMonth: Double,
    val mode: String = "soft", // hard | soft (soft default, D-6)
)

@Serializable
data class RateLimitDef(
    val id: String,
    val requestsPerMinute: Int,
)

@Serializable
data class SeededKey(
    val team: String,
    val name: String,
    val sha256: String, // SHA-256 hex; plaintext never stored (D-1)
)
