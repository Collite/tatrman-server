// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider

import org.tatrman.llmgateway.config.CatalogModel
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.wire.AnthropicErrorConverter
import org.tatrman.llmgateway.wire.ErrorConverter
import org.tatrman.llmgateway.wire.OpenAiWireErrorConverter

/** One resolved catalog model: its upstream target, the handler that speaks its wire, the key, and the error converter. */
data class RegistryEntry(
    val model: CatalogModel,
    val target: UpstreamTarget,
    val handler: ProviderHandler?, // null = no handler for this kind (should not happen once all kinds are wired)
    val key: Key,
    val errorConverter: ErrorConverter, // provider-family error mapping (openai-wire vs anthropic)
)

/** A registry-level resolution: the three-tier [Resolution] mapped onto the concrete [RegistryEntry]. */
sealed interface RegistryResolution {
    data class Resolved(
        val entry: RegistryEntry,
    ) : RegistryResolution

    data class NotFound(
        val requested: String,
    ) : RegistryResolution

    data class Invalid(
        val reason: String,
    ) : RegistryResolution
}

/**
 * Immutable catalog × providers registry built at startup (contracts §5.4). Keys are resolved once from
 * each provider's `keyEnv` and handed to the handler PER CALL (C-5). Full three-tier resolution (alias →
 * literal → tag) is delegated to the pure [ModelResolver] (LG-P3·S1); [resolve] then maps the selected
 * catalog model to its [RegistryEntry].
 */
class ProviderRegistry private constructor(
    private val byName: Map<String, RegistryEntry>,
    private val byId: Map<String, RegistryEntry>,
    private val resolver: ModelResolver,
) {
    val size: Int get() = byName.size

    /**
     * The fallback chain for a resolved entry (C-4): the entry itself first, then its catalog `fallback`
     * ids in declared order (unknown ids already rejected by the LG-P1 validator). Order is fixed — the
     * engine only ever *skips* circuit-open entries, never reorders (design §3.2).
     */
    fun chainFor(entry: RegistryEntry): List<RegistryEntry> =
        listOf(entry) + entry.model.fallback.mapNotNull { byId[it] }

    /**
     * Three-tier resolution (design §3.2 C-1): `Resolved(entry)` | `NotFound(requested)` (→ 404) |
     * `Invalid(reason)` (→ 400). [requestedType] guards chat/embedding cross-routing.
     */
    fun resolve(
        name: String?,
        tags: List<String>,
        requestedType: RequestedType,
    ): RegistryResolution =
        when (val r = resolver.resolve(name, tags, requestedType)) {
            is Resolution.Resolved -> RegistryResolution.Resolved(byName.getValue(r.model.name))
            is Resolution.NotFound -> RegistryResolution.NotFound(r.requested)
            is Resolution.Invalid -> RegistryResolution.Invalid(r.reason)
        }

    /** Exact catalog-name match (tier-2 only). Retained for tests/internal callers; routes use [resolve]. */
    fun resolveLiteral(modelName: String): RegistryEntry? = byName[modelName]

    companion object {
        fun build(
            gateway: GatewayConfig,
            passthrough: ProviderHandler,
            anthropic: ProviderHandler? = null,
            keyResolver: (String) -> Key = { Key(System.getenv(it) ?: "") },
        ): ProviderRegistry {
            val byName =
                gateway.catalog.models.associateBy({ it.name }) { m ->
                    // provider ref is validated at load (LG-P1·S1); getValue re-asserts fail-fast
                    val p = gateway.providers.providers.getValue(m.provider)
                    val target =
                        UpstreamTarget(
                            providerName = m.provider,
                            kind = p.kind,
                            baseUrl = p.baseUrl,
                            upstream = m.upstream,
                            urlPattern = p.urlPattern ?: "/v1/{path}",
                            apiVersion = p.apiVersion,
                            authHeader = p.auth.header,
                            authScheme = p.auth.scheme,
                            providerVersion = p.version,
                            defaultMaxTokens = p.defaultMaxTokens,
                        )
                    val handler = if (p.kind == "anthropic") anthropic else passthrough
                    val errorConverter =
                        if (p.kind == "anthropic") AnthropicErrorConverter else OpenAiWireErrorConverter
                    RegistryEntry(m, target, handler, keyResolver(p.auth.keyEnv), errorConverter)
                }
            val byId = byName.values.associateBy { it.model.id }
            return ProviderRegistry(byName, byId, ModelResolver(gateway.catalog.models))
        }
    }
}
