// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.config

/**
 * LG-P1·S1 — startup config validation (contracts §2.1). Returns **every** error, not first-fail, so
 * a misconfigured deploy is fixed in one pass rather than N restarts. Fail startup, never first request.
 */
object ConfigValidator {
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val BUDGET_MODES = setOf("hard", "soft")
    private val MODEL_TYPES = setOf("chat", "embedding")

    fun validate(
        catalog: Catalog,
        providers: ProvidersConfig,
        governance: GovernanceConfig,
    ): List<String> {
        val e = mutableListOf<String>()
        val models = catalog.models

        // unique ids / names
        models
            .groupBy { it.id }
            .filter { it.value.size > 1 }
            .keys
            .forEach { e += "duplicate model id: $it" }
        models
            .groupBy { it.name }
            .filter { it.value.size > 1 }
            .keys
            .forEach { e += "duplicate model name: $it" }

        val names = models.map { it.name }.toSet()
        val ids = models.map { it.id }.toSet()
        val providerKeys = providers.providers.keys

        // aliases: must not collide with a model name (tier-1 vs tier-2 ambiguity), must be unique
        val aliasOwners = models.flatMap { m -> m.aliases.map { it to m.id } }
        aliasOwners.forEach { (alias, mid) ->
            if (alias in names) e += "alias '$alias' (model $mid) collides with a model name"
        }
        aliasOwners.groupBy { it.first }.filter { it.value.size > 1 }.keys.forEach {
            e += "duplicate alias '$it' (claimed by ${aliasOwners.filter { a -> a.first == it }.map { a -> a.second }})"
        }

        models.forEach { m ->
            // fallback ids exist
            m.fallback.forEach { f -> if (f !in ids) e += "model ${m.id} fallback references unknown id: $f" }
            // provider resolvable
            if (m.provider !in providerKeys) e += "model ${m.id} references unknown provider: ${m.provider}"
            // pricing (chat both sides; embedding input only)
            when (m.type) {
                "chat" -> {
                    if (m.pricing?.input == null) e += "chat model ${m.id} missing pricing.input"
                    if (m.pricing?.output == null) e += "chat model ${m.id} missing pricing.output"
                }
                "embedding" -> if (m.pricing?.input == null) e += "embedding model ${m.id} missing pricing.input"
                else -> e += "model ${m.id} has unknown type '${m.type}' (expected one of $MODEL_TYPES)"
            }
        }

        // governance
        val teamIds = governance.teams.map { it.id }.toSet()
        governance.teams.forEach { t ->
            t.budget?.let { b ->
                if (b.mode !in
                    BUDGET_MODES
                ) {
                    e += "team ${t.id} budget mode must be hard|soft, was '${b.mode}'"
                }
            }
        }
        governance.keys.forEach { k ->
            if (!SHA256.matches(k.sha256)) e += "seeded key '${k.name}' sha256 is not 64-hex (plaintext leak?)"
            if (k.team !in teamIds) e += "seeded key '${k.name}' references unknown team: ${k.team}"
        }

        return e
    }
}
