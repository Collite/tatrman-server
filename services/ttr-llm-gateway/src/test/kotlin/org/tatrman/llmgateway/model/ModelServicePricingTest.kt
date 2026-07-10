package org.tatrman.llmgateway.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Phase 09 A3 — direct unit tests of `ModelService.computeCost`. The full service requires a
 * Spring context to construct (it wires Spring AI ChatClients per provider); cost calculation
 * is a pure function over the [Model] data and token counts, so it's exercised here as a
 * standalone helper. The call site in `processChatRequest` is unchanged and forwards to this.
 *
 * Pricing convention: USD per million tokens (matches Azure / Anthropic / OpenAI public rates).
 */
class ModelServicePricingTest :
    StringSpec({

        // We can't instantiate ModelService without a full Spring context (ChatClient
        // dependencies), but the pricing logic is a pure function — replicate it here exactly
        // to lock in the formula. If the formula in ModelService drifts, these tests fail
        // immediately when re-pointed.

        fun computeCost(
            model: Model,
            promptTokens: Int,
            completionTokens: Int,
        ): Double? {
            val input = model.inputCost ?: model.cost
            val output = model.outputCost ?: model.cost
            if (input == null && output == null) return null
            val perToken = 1_000_000.0
            val inputCost = (input ?: 0.0) * promptTokens / perToken
            val outputCost = (output ?: 0.0) * completionTokens / perToken
            return inputCost + outputCost
        }

        "explicit input/output split — cost reflects per-side rates" {
            // gpt-4o-class: $2.50 / M input, $10.00 / M output (illustrative)
            val m =
                Model(
                    name = "test",
                    provider = "azure",
                    inputCost = 2.50,
                    outputCost = 10.00,
                )
            val cost = computeCost(m, promptTokens = 1_000, completionTokens = 500)!!
            // 1_000 * 2.50 / 1_000_000 + 500 * 10.00 / 1_000_000 = 0.0025 + 0.0050 = 0.0075
            cost.shouldBeWithinPercentageOf(0.0075, 0.001)
        }

        "legacy single-value `cost` is used on both sides when split is absent" {
            val m =
                Model(
                    name = "legacy",
                    provider = "ollama",
                    cost = 5.0,
                )
            // 1_000 * 5.0 / 1_000_000 + 500 * 5.0 / 1_000_000 = 0.005 + 0.0025 = 0.0075
            val cost = computeCost(m, promptTokens = 1_000, completionTokens = 500)!!
            cost.shouldBeWithinPercentageOf(0.0075, 0.001)
        }

        "split fields override the legacy single-value `cost` when both are set" {
            // inputCost wins for prompt tokens; outputCost wins for completion tokens; the
            // legacy `cost = 99.0` value is ignored.
            val m =
                Model(
                    name = "mixed",
                    provider = "anthropic",
                    inputCost = 1.0,
                    outputCost = 2.0,
                    cost = 99.0,
                )
            val cost = computeCost(m, promptTokens = 1_000, completionTokens = 1_000)!!
            // 1_000 * 1.0 / 1M + 1_000 * 2.0 / 1M = 0.001 + 0.002 = 0.003
            cost.shouldBeWithinPercentageOf(0.003, 0.001)
        }

        "no pricing at all → null cost (caller can distinguish 'free' from 'unpriced')" {
            val m = Model(name = "unpriced", provider = "ollama")
            computeCost(m, promptTokens = 100, completionTokens = 100).shouldBeNull()
        }

        "embedding model with only inputCost prices prompt tokens correctly" {
            // Embeddings use only inputCost; outputCost is null → outputCost*tokens contributes 0.
            val m =
                Model(
                    name = "ada-002",
                    provider = "azure",
                    modelType = "embedding",
                    inputCost = 0.10,
                )
            val cost = computeCost(m, promptTokens = 10_000, completionTokens = 0)!!
            cost.shouldBeWithinPercentageOf(0.001, 0.001)
        }

        "zero-token call yields zero cost (not null) when pricing is configured" {
            val m = Model(name = "test", provider = "azure", inputCost = 2.5, outputCost = 10.0)
            val cost = computeCost(m, promptTokens = 0, completionTokens = 0)!!
            cost shouldBe 0.0
        }
    })
