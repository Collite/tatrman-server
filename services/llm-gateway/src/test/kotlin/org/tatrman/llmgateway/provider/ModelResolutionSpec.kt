// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.random.Random
import org.tatrman.llmgateway.config.CatalogModel
import org.tatrman.llmgateway.config.Pricing

/**
 * LG-P3·S1·T1/T3 — the three-tier resolver (design §3.2 C-1). Alias → literal (case-insensitive) →
 * tag soft-match (tie-break lowest cost rank, then name). Strict on unknown explicit names (the 1.x
 * `firstOrNull()` silent-default bug is a NAMED regression here) and deterministic (the 1.x
 * `bestcandidates.random()` bug is a property test over 100 catalog orderings).
 */
class ModelResolutionSpec :
    StringSpec({

        fun chat(
            id: String,
            name: String,
            aliases: List<String> = emptyList(),
            tags: List<String> = emptyList(),
            provider: String = "azure",
            input: Double = 1.0,
            output: Double = 1.0,
            dimensions: Map<String, String> = emptyMap(),
        ) = CatalogModel(
            id = id,
            name = name,
            aliases = aliases,
            provider = provider,
            upstream = name,
            type = "chat",
            tags = tags,
            pricing = Pricing(input, output),
            dimensions = dimensions,
        )

        fun embed(
            id: String,
            name: String,
            provider: String = "azure",
        ) = CatalogModel(
            id = id,
            name = name,
            provider = provider,
            upstream = name,
            type = "embedding",
            tags = listOf("embedding"),
            pricing = Pricing(1.0, null),
        )

        val gpt4o =
            chat(
                "azure-gpt-4o",
                "gpt-4o",
                aliases = listOf("gpt-4"),
                tags = listOf("smart", "coding"),
                input = 10.0,
                output = 10.0,
            )
        val mini =
            chat(
                "azure-gpt-4o-mini",
                "gpt-4o-mini",
                aliases = listOf("fast"),
                tags = listOf("fast", "cheap", "chat"),
                input = 1.0,
                output = 1.0,
            )
        val haiku =
            chat(
                "anthropic-haiku",
                "claude-haiku-4-5",
                aliases = listOf("haiku", "claude-haiku"),
                tags = listOf("fast", "cheap"),
                provider = "anthropic",
                input = 1.0,
                output = 1.0,
            )
        val ada = embed("azure-ada-002", "ada-002")
        val catalog = listOf(gpt4o, mini, haiku, ada)
        val resolver = ModelResolver(catalog)

        fun resolvedName(r: Resolution): String? = (r as? Resolution.Resolved)?.model?.name

        // ── (a) alias tier ────────────────────────────────────────────────────────────────────────
        "alias tier: gpt-4 → gpt-4o, haiku → claude-haiku-4-5, claude-haiku → claude-haiku-4-5" {
            resolvedName(resolver.resolve("gpt-4", emptyList(), RequestedType.CHAT)) shouldBe "gpt-4o"
            resolvedName(resolver.resolve("haiku", emptyList(), RequestedType.CHAT)) shouldBe "claude-haiku-4-5"
            resolvedName(resolver.resolve("claude-haiku", emptyList(), RequestedType.CHAT)) shouldBe "claude-haiku-4-5"
        }

        // ── (b) literal tier, case-insensitive (1.x equals(ignoreCase) parity) ──────────────────────
        "literal tier: exact name matches, case-insensitive" {
            resolvedName(resolver.resolve("gpt-4o", emptyList(), RequestedType.CHAT)) shouldBe "gpt-4o"
            resolvedName(resolver.resolve("GPT-4O", emptyList(), RequestedType.CHAT)) shouldBe "gpt-4o"
            resolvedName(resolver.resolve("Claude-Haiku-4-5", emptyList(), RequestedType.CHAT)) shouldBe
                "claude-haiku-4-5"
        }

        // ── (c) STRICT unknown — the 1.x firstOrNull() silent-default bug must never return ─────────
        "strict unknown: explicit unknown name → NotFound, NEVER a default model (1.x firstOrNull regression)" {
            val r = resolver.resolve("does-not-exist", emptyList(), RequestedType.CHAT)
            r.shouldBeInstanceOf<Resolution.NotFound>()
            (r as Resolution.NotFound).requested shouldBe "does-not-exist"
        }

        // ── (d) tag tier: most-tags-matched wins; ties → cost rank then name ─────────────────────────
        "tag tier: most-tags-matched wins" {
            // only gpt-4o carries "coding" → unambiguous winner even though "smart" is unique too
            resolvedName(resolver.resolve(null, listOf("smart", "coding"), RequestedType.CHAT)) shouldBe "gpt-4o"
        }
        "tag tier tie-break: equal match count → lowest cost rank, then name" {
            // both mini (fast,cheap,chat) and haiku (fast,cheap) match {fast,cheap} == 2 each;
            // equal cost rank (1+1) → name tie-break → claude-haiku-4-5 sorts before gpt-4o-mini
            resolvedName(resolver.resolve(null, listOf("fast", "cheap"), RequestedType.CHAT)) shouldBe
                "claude-haiku-4-5"
        }
        "tag tier: zero overlap → NotFound (never a default)" {
            resolver
                .resolve(null, listOf("no-such-tag"), RequestedType.CHAT)
                .shouldBeInstanceOf<Resolution.NotFound>()
        }

        // ── property: resolution is a PURE function of catalog+request (100 orderings) ───────────────
        "property: same catalog+request ⇒ same result across 100 random catalog orderings" {
            checkAll(100, Arb.int()) { seed ->
                val r = ModelResolver(catalog.shuffled(Random(seed)))
                resolvedName(r.resolve("gpt-4", emptyList(), RequestedType.CHAT)) shouldBe "gpt-4o"
                resolvedName(r.resolve(null, listOf("fast", "cheap"), RequestedType.CHAT)) shouldBe "claude-haiku-4-5"
                r.resolve("does-not-exist", emptyList(), RequestedType.CHAT).shouldBeInstanceOf<Resolution.NotFound>()
            }
        }

        // ── (e) namespaced provider/model — additive ────────────────────────────────────────────────
        "namespaced: azure/gpt-4o resolves within provider; plain names still work; unknown ns → NotFound" {
            resolvedName(resolver.resolve("azure/gpt-4o", emptyList(), RequestedType.CHAT)) shouldBe "gpt-4o"
            resolvedName(resolver.resolve("anthropic/haiku", emptyList(), RequestedType.CHAT)) shouldBe
                "claude-haiku-4-5" // alias inside ns
            resolvedName(resolver.resolve("gpt-4o", emptyList(), RequestedType.CHAT)) shouldBe "gpt-4o" // additive
            // gpt-4o lives under azure, not openai → wrong-provider namespace is NotFound
            resolver.resolve("openai/gpt-4o", emptyList(), RequestedType.CHAT).shouldBeInstanceOf<Resolution.NotFound>()
            resolver.resolve("nope/x", emptyList(), RequestedType.CHAT).shouldBeInstanceOf<Resolution.NotFound>()
        }

        // ── (f) type guard: chat request → embedding model = Validation (Invalid) ────────────────────
        "type guard: chat request naming an embedding model → Invalid (not NotFound)" {
            resolver.resolve("ada-002", emptyList(), RequestedType.CHAT).shouldBeInstanceOf<Resolution.Invalid>()
        }
        "type guard: embedding request naming a chat model → Invalid" {
            resolver.resolve("gpt-4o", emptyList(), RequestedType.EMBEDDING).shouldBeInstanceOf<Resolution.Invalid>()
        }
        "tag tier respects type: embedding request never tag-resolves to a chat model" {
            // "fast"/"cheap" tags live on chat models; an embedding request must not land on one
            resolver
                .resolve(null, listOf("fast", "cheap"), RequestedType.EMBEDDING)
                .shouldBeInstanceOf<Resolution.NotFound>()
        }

        // ── (g) no model + no tags → Invalid (1.x silent-first NOT ported) ───────────────────────────
        "no model + no tags → Invalid (explicit; never a silent first())" {
            resolver.resolve(null, emptyList(), RequestedType.CHAT).shouldBeInstanceOf<Resolution.Invalid>()
            resolver.resolve("  ", emptyList(), RequestedType.CHAT).shouldBeInstanceOf<Resolution.Invalid>()
        }

        // ── T3 — GI-3 schema room: dimensions parses, resolver provably never reads it ───────────────
        "GI-3: catalog `dimensions` is ignored by resolution (schema room only, no 2.0 routing logic)" {
            val withDims = gpt4o.copy(dimensions = mapOf("tier" to "strong", "task_kind" to "sql"))
            val without = gpt4o.copy(dimensions = emptyMap())
            // identical resolution regardless of dimensions content → resolver does not read the field
            resolvedName(
                ModelResolver(listOf(withDims, mini, haiku, ada)).resolve("gpt-4", emptyList(), RequestedType.CHAT),
            ) shouldBe
                resolvedName(
                    ModelResolver(listOf(without, mini, haiku, ada)).resolve("gpt-4", emptyList(), RequestedType.CHAT),
                )
        }
    })
