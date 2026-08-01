// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import org.tatrman.llmgateway.config.CatalogModel
import org.tatrman.llmgateway.config.Pricing
import org.tatrman.llmgateway.provider.ResponseEnrichment

/**
 * LG-P4·S2·T5 — cost parity with the 1.x `ModelService.computeCost`: USD =
 * `(in·pricing.input + out·pricing.output) / 1e6`. Catalog rows all carry split pricing (LG-P1 validator),
 * so the legacy single-`cost` fallback is NOT ported. The formula lives in `ResponseEnrichment.computeCost`.
 */
class CostSpec :
    StringSpec({

        fun model(
            input: Double,
            output: Double?,
        ) = CatalogModel(
            id = "m",
            name = "m",
            provider = "openai",
            upstream = "m",
            type = "chat",
            pricing = Pricing(input = input, output = output),
        )

        "split pricing: (in·input + out·output)/1e6" {
            // gpt-4o-ish: $2.50 in / $10.00 out per 1M
            ResponseEnrichment.computeCost(model(2.5, 10.0), 1_000, 500) shouldBeExactly
                (1_000 * 2.5 + 500 * 10.0) / 1e6
            // 1M in / 1M out at $5/$15
            ResponseEnrichment.computeCost(model(5.0, 15.0), 1_000_000, 1_000_000) shouldBeExactly 20.0
            // zero tokens → zero cost
            ResponseEnrichment.computeCost(model(3.0, 6.0), 0, 0) shouldBeExactly 0.0
        }

        "missing output pricing falls back to input rate (embedding-style rows)" {
            ResponseEnrichment.computeCost(model(1.0, null), 2_000, 1_000) shouldBeExactly
                (2_000 * 1.0 + 1_000 * 1.0) / 1e6
        }

        "no pricing → zero (never throws)" {
            val noPrice = model(0.0, 0.0).copy(pricing = null)
            ResponseEnrichment.computeCost(noPrice, 10, 10) shouldBeExactly 0.0
        }
    })
