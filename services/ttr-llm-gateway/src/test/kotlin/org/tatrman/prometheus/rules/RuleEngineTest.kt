package org.tatrman.prometheus.rules

import com.charleskorn.kaml.Yaml
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.core.io.ClassPathResource
import org.tatrman.prometheus.model.ModelsConfig
import org.tatrman.prometheus.model.rules.RequestMetadata
import org.tatrman.prometheus.model.rules.RuleEngine

class RuleEngineTest :
    StringSpec({
        val configString =
            """
            rules {
                aliases {
                    "gpt-4-alias" = "gpt-4o"
                    "fast-model" = "gpt-4o-mini"
                }
            }
            """.trimIndent()

        val config = ConfigFactory.parseString(configString)
        val ruleEngine = RuleEngine(config)

        val availableModels =
            ClassPathResource("models.yaml")
                .inputStream
                .bufferedReader()
                .use {
                    Yaml.default.decodeFromString(ModelsConfig.serializer(), it.readText())
                }.models

        "selectModel should return exact match if exists" {
            val metadata = RequestMetadata(modelName = "gpt-4o")
            val result = ruleEngine.selectModel(metadata, availableModels)

            result shouldNotBe null
            result?.name shouldBe "gpt-4o"
        }

        "selectModel should apply alias mapping" {
            val metadata = RequestMetadata(modelName = "gpt-4-alias")
            val result = ruleEngine.selectModel(metadata, availableModels)

            result shouldNotBe null
            result?.name shouldBe "gpt-4o"
        }

        "selectModel should apply alias mapping for fast-model" {
            val metadata = RequestMetadata(modelName = "fast-model")
            val result = ruleEngine.selectModel(metadata, availableModels)

            result shouldNotBe null
            result?.name shouldBe "gpt-4o-mini"
        }

        "selectModel should return null if model not found" {
            val metadata = RequestMetadata(modelName = "non-existent-model")
            val result = ruleEngine.selectModel(metadata, availableModels)

            result shouldBe null
        }

        // Phase 06 A1/A2 — multi-vendor model catalog. Selection-by-name is the routing primitive:
        // ModelService.clientMap looks up by the returned model's `provider` enum (azure | anthropic),
        // so as long as the right (name, provider) pair comes back from the rule engine, routing is
        // correct.

        "selects Anthropic Sonnet model and tags it with provider=anthropic" {
            val result = ruleEngine.selectModel(RequestMetadata(modelName = "claude-sonnet-4-6"), availableModels)
            result shouldNotBe null
            result?.name shouldBe "claude-sonnet-4-6"
            result?.provider shouldBe "anthropic"
            result?.fullName shouldBe "claude-sonnet-4-6"
        }

        "selects Anthropic Opus + Haiku entries with provider=anthropic" {
            ruleEngine
                .selectModel(RequestMetadata(modelName = "claude-opus-4-7"), availableModels)
                ?.provider shouldBe "anthropic"
            ruleEngine
                .selectModel(RequestMetadata(modelName = "claude-haiku-4-5"), availableModels)
                ?.provider shouldBe "anthropic"
        }

        "Azure models still resolve with provider=azure after Anthropic was added" {
            ruleEngine
                .selectModel(RequestMetadata(modelName = "gpt-4o"), availableModels)
                ?.provider shouldBe "azure"
            ruleEngine
                .selectModel(RequestMetadata(modelName = "gpt-4.1"), availableModels)
                ?.provider shouldBe "azure"
        }
    })
