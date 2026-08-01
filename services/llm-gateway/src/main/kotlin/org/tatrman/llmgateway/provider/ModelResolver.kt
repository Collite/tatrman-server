// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider

import org.tatrman.llmgateway.config.CatalogModel

/** What the caller is asking for — resolution filters/guards on this (chat vs embedding endpoint). */
enum class RequestedType(
    val wire: String,
) {
    CHAT("chat"),
    EMBEDDING("embedding"),
}

/** The outcome of resolving a request to a concrete catalog model (design §3.2 C-1). */
sealed interface Resolution {
    /** A concrete catalog model was selected. */
    data class Resolved(
        val model: CatalogModel,
    ) : Resolution

    /** A name/namespace/tag-set was given but matched nothing — maps to 404 model_not_found (§1.7). */
    data class NotFound(
        val requested: String,
    ) : Resolution

    /** The request cannot be routed at all (no model + no tags, or a wrong-type match) — maps to 400. */
    data class Invalid(
        val reason: String,
    ) : Resolution
}

/**
 * The three-tier model resolver (design §3.2 C-1, contracts §2.1). Pure over the immutable catalog, so it
 * is a function of `(catalog, name, tags, type)` alone — no ordering dependence, no IO. Tiers:
 *
 *  1. **alias** — precomputed `alias → model` map (collisions already rejected by the LG-P1 validator);
 *  2. **literal** — exact catalog `name`, case-insensitive (1.x `equals(ignoreCase)` parity);
 *  3. **tag soft-match** — used only when no name is given: most `model_tags` matched wins, ties broken by
 *     **lowest cost rank (`pricing.input + pricing.output`) then name** (contracts §2.1 "lowest cost" made
 *     computable). Namespaced `provider/model` is accepted additively.
 *
 * Two 1.x behaviors are deliberately NOT ported (design §3.2): the 1.x random pick among best candidates
 * (replaced by the deterministic cost-then-name tie-break — see the 100-ordering property test) and the
 * `availableModels.firstOrNull()` silent default on no match (replaced by a typed [Resolution.NotFound] /
 * [Resolution.Invalid] — an explicit unknown model is an error, never a fallback to some arbitrary row).
 */
class ModelResolver(
    private val models: List<CatalogModel>,
) {
    // alias → model, lowercased. Validator guarantees no alias collides with a name or another alias.
    private val aliasToModel: Map<String, CatalogModel> =
        buildMap { models.forEach { m -> m.aliases.forEach { a -> put(a.lowercase(), m) } } }

    // name → model, lowercased (case-insensitive literal tier).
    private val nameToModel: Map<String, CatalogModel> = models.associateBy { it.name.lowercase() }

    fun resolve(
        name: String?,
        tags: List<String>,
        want: RequestedType,
    ): Resolution {
        val requested = name?.trim()
        if (!requested.isNullOrEmpty()) return resolveByName(requested, want)
        if (tags.isNotEmpty()) return resolveByTags(tags, want)
        return Resolution.Invalid("no 'model' and no 'model_tags' — nothing to route on")
    }

    private fun resolveByName(
        name: String,
        want: RequestedType,
    ): Resolution {
        // namespaced provider/model — additive; resolves the local part WITHIN that provider only.
        if (name.contains('/')) {
            val provider = name.substringBefore('/')
            val local = name.substringAfter('/')
            val m =
                models.firstOrNull { model ->
                    model.provider.equals(provider, ignoreCase = true) &&
                        (
                            model.name.equals(local, ignoreCase = true) ||
                                model.aliases.any { it.equals(local, ignoreCase = true) }
                        )
                } ?: return Resolution.NotFound(name)
            return typeChecked(m, want)
        }
        // tier 1: alias
        aliasToModel[name.lowercase()]?.let { return typeChecked(it, want) }
        // tier 2: literal (case-insensitive)
        nameToModel[name.lowercase()]?.let { return typeChecked(it, want) }
        // strict: an explicit unknown name is an error — NEVER the 1.x firstOrNull() default.
        return Resolution.NotFound(name)
    }

    private fun resolveByTags(
        tags: List<String>,
        want: RequestedType,
    ): Resolution {
        val wanted = tags.map { it.lowercase() }.toSet()
        // Only type-appropriate models are candidates, so tag routing never crosses chat/embedding.
        val scored =
            models
                .filter { it.matches(want) }
                .map { it to it.tags.count { t -> t.lowercase() in wanted } }
                .filter { (_, score) -> score > 0 }
        if (scored.isEmpty()) return Resolution.NotFound("model_tags=$tags")
        val winner =
            scored
                .sortedWith(
                    compareByDescending<Pair<CatalogModel, Int>> { it.second }
                        .thenBy { costRank(it.first) }
                        .thenBy { it.first.name },
                ).first()
                .first
        return Resolution.Resolved(winner) // already type-filtered → no further guard needed
    }

    private fun typeChecked(
        model: CatalogModel,
        want: RequestedType,
    ): Resolution =
        if (model.matches(want)) {
            Resolution.Resolved(model)
        } else {
            Resolution.Invalid("model '${model.name}' is a ${model.type} model, not usable as ${want.wire}")
        }

    private fun CatalogModel.matches(want: RequestedType): Boolean =
        when (want) {
            RequestedType.CHAT -> isChat
            RequestedType.EMBEDDING -> isEmbedding
        }

    // Cost rank = input + output per 1M tokens (embeddings: input only). Missing pricing sorts last.
    private fun costRank(model: CatalogModel): Double {
        val pricing = model.pricing ?: return Double.MAX_VALUE
        return pricing.input + (pricing.output ?: pricing.input)
    }
}
