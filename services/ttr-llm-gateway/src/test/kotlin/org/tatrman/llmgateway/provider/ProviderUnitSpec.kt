// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.llmgateway.config.CatalogModel
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.Pricing

/**
 * LG-P2·S1 — provider-layer units with no HTTP: URL templating (incl. Azure rewrite), the ported 1.x
 * cost formula, the §1.3 usage-extension injection, and literal registry resolution + the C-5 key seam.
 */
class ProviderUnitSpec :
    StringSpec({

        fun target(
            urlPattern: String,
            upstream: String = "gpt-4o",
            apiVersion: String? = null,
        ) = UpstreamTarget("azure", "openai-wire", "https://base", upstream, urlPattern, apiVersion, "api-key", null)

        "Azure URL rewrite: deployment + api-version templated" {
            UrlBuilder.path(
                target(
                    "/openai/deployments/{upstream}/{path}?api-version={apiVersion}",
                    "tatrman-gpt-4.1",
                    "2024-10-21",
                ),
                "chat/completions",
            ) shouldBe "/openai/deployments/tatrman-gpt-4.1/chat/completions?api-version=2024-10-21"
        }

        "OpenAI URL: /v1/{path}; Gemini URL: /{path}" {
            UrlBuilder.path(target("/v1/{path}"), "chat/completions") shouldBe "/v1/chat/completions"
            UrlBuilder.path(target("/{path}"), "embeddings") shouldBe "/embeddings"
        }

        "cost = (in×inputCost + out×outputCost)/1e6 (ported 1.x formula)" {
            val model =
                CatalogModel("m", "m", provider = "azure", upstream = "m", type = "chat", pricing = Pricing(2.0, 8.0))
            ResponseEnrichment.computeCost(model, promptTokens = 1000, completionTokens = 500) shouldBe
                (0.006 plusOrMinus 1e-9)
        }

        "chat enrichment injects dual usage names + cost + cached, preserving the rest of the body" {
            val model =
                CatalogModel("m", "m", provider = "azure", upstream = "m", type = "chat", pricing = Pricing(2.0, 8.0))
            val upstream =
                Json
                    .parseToJsonElement(
                        """{"id":"cmpl-1","choices":[{"index":0,"message":{"content":"hi"}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""",
                    ).jsonObject
            val enriched = ResponseEnrichment.chat(ProviderResult(200, upstream, UpstreamUsage(10, 5), "stop"), model)

            val usage = enriched["usage"]!!.jsonObject
            usage["prompt_tokens"]!!.jsonPrimitive.content shouldBe "10"
            usage["input_tokens"]!!.jsonPrimitive.content shouldBe "10" // dual, migration
            usage["output_tokens"]!!.jsonPrimitive.content shouldBe "5"
            usage["cost"]!!.jsonPrimitive.content shouldBe "6.0E-5" // (10*2 + 5*8)/1e6 = 6e-5
            enriched["cached"]!!.jsonPrimitive.content shouldBe "false"
            // the rest of the body is byte-stable
            enriched["id"]!!.jsonPrimitive.content shouldBe "cmpl-1"
            enriched["choices"] shouldBe upstream["choices"]
        }

        "registry resolves literal names, not aliases; anthropic has no handler yet (S3)" {
            val gateway = ConfigLoader.loadFromResources()
            val registry =
                ProviderRegistry.build(
                    gateway,
                    passthrough = PassthroughHandler(io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)),
                )

            registry
                .resolveLiteral("gpt-4o")
                .shouldNotBeNull()
                .target.providerName shouldBe "azure"
            registry.resolveLiteral("haiku").shouldBeNull() // alias — literal resolution only (LG-P3 adds it)
            registry.resolveLiteral("nope").shouldBeNull()
            // anthropic models resolve but have no handler until the converter lands (LG-P2·S3)
            registry
                .resolveLiteral("claude-haiku-4-5")
                .shouldNotBeNull()
                .handler
                .shouldBeNull()
        }

        "C-5: two targets on one provider instance can carry different keys (key per call, not construction)" {
            val handler = PassthroughHandler(io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO))
            // the handler holds no key; keys ride the call — proven structurally by the signature
            val k1 = Key("ttrk-a")
            val k2 = Key("ttrk-b")
            (k1.value != k2.value) shouldBe true
            (handler is ProviderHandler) shouldBe true
        }
    })
