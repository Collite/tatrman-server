package org.tatrman.prometheus.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.embedding.EmbeddingResponseMetadata
import org.tatrman.prometheus.web.EmbeddingInputApi
import org.tatrman.prometheus.web.EmbeddingRequestApi

/**
 * Phase 09 A3 / DF-X — verifies the embedding service shape: model selection by name +
 * fallback to `findType("embedding")`, response repacking, cost from `inputCost` only,
 * provider routing.
 */
class EmbeddingServiceTest :
    StringSpec({

        fun mockEmbeddingModel(vectorLen: Int = 4): EmbeddingModel {
            val model = mockk<EmbeddingModel>(relaxed = true)
            every { model.call(any<EmbeddingRequest>()) } answers {
                val req = it.invocation.args.first() as EmbeddingRequest
                val vectors =
                    req.instructions.mapIndexed { _, _ ->
                        Embedding(FloatArray(vectorLen) { idx -> idx.toFloat() / 10f }, 0)
                    }
                val metadata = EmbeddingResponseMetadata("test-model", DefaultUsage(7, 0, 7))
                EmbeddingResponse(vectors, metadata)
            }
            return model
        }

        fun repoWith(models: List<Model>): ModelRepository {
            val repo = mockk<ModelRepository>(relaxed = true)
            every { repo.findByName(any()) } answers {
                val name = firstArg<String>()
                models.find { it.name == name }
            }
            every { repo.findType("embedding") } answers { models.filter { it.modelType == "embedding" } }
            return repo
        }

        "process returns one item per input text, with the expected indices" {
            val emb = mockEmbeddingModel(vectorLen = 3)
            val service =
                EmbeddingService(
                    modelRepository =
                        repoWith(
                            listOf(
                                Model(
                                    name = "ada-002",
                                    provider = "azure",
                                    modelType = "embedding",
                                    inputCost = 0.10,
                                ),
                            ),
                        ),
                    azureEmbedding = emb,
                )

            val resp =
                service.process(
                    EmbeddingRequestApi(
                        input = EmbeddingInputApi(texts = listOf("hello", "world", "there")),
                    ),
                )
            resp.data shouldHaveSize 3
            resp.data.map { it.index } shouldBe listOf(0, 1, 2)
            resp.data[0].embedding.size shouldBe 3
        }

        "process attributes cost from inputCost only (no output tokens for embeddings)" {
            val emb = mockEmbeddingModel()
            val service =
                EmbeddingService(
                    modelRepository =
                        repoWith(
                            listOf(
                                Model(
                                    name = "ada-002",
                                    provider = "azure",
                                    modelType = "embedding",
                                    inputCost = 0.10,
                                ),
                            ),
                        ),
                    azureEmbedding = emb,
                )
            val resp =
                service.process(
                    EmbeddingRequestApi(model = "ada-002", input = EmbeddingInputApi(text = "hello")),
                )
            // promptTokens = 7 (from mock), inputCost = 0.10 / 1M → 7 * 0.10 / 1_000_000 = 7e-7
            resp.usage.cost!!.shouldBeWithinPercentageOf(7e-7, 0.001)
            resp.usage.inputTokens shouldBe 7
            resp.usage.outputTokens shouldBe 0
        }

        "process falls back to the first embedding model when the requested name is unknown" {
            val emb = mockEmbeddingModel()
            val service =
                EmbeddingService(
                    modelRepository =
                        repoWith(
                            listOf(
                                Model(name = "ada-002", provider = "azure", modelType = "embedding", inputCost = 0.10),
                                Model(name = "gpt-4o", provider = "azure", modelType = "chat", cost = 5.0),
                            ),
                        ),
                    azureEmbedding = emb,
                )
            val resp =
                service.process(
                    EmbeddingRequestApi(model = "does-not-exist", input = EmbeddingInputApi(text = "hi")),
                )
            // Falls back to first `type: embedding` model — ada-002.
            resp.model shouldBe "ada-002"
        }

        "process rejects an empty input list" {
            val emb = mockEmbeddingModel()
            val service =
                EmbeddingService(
                    modelRepository =
                        repoWith(
                            listOf(Model(name = "ada-002", provider = "azure", modelType = "embedding")),
                        ),
                    azureEmbedding = emb,
                )
            shouldThrow<IllegalArgumentException> {
                service.process(EmbeddingRequestApi(input = EmbeddingInputApi(texts = emptyList())))
            }
        }

        "process rejects a non-azure provider until more clients are wired" {
            val emb = mockEmbeddingModel()
            val service =
                EmbeddingService(
                    modelRepository =
                        repoWith(
                            listOf(
                                Model(
                                    name = "cohere-embed",
                                    provider = "cohere",
                                    modelType = "embedding",
                                    inputCost = 0.05,
                                ),
                            ),
                        ),
                    azureEmbedding = emb,
                )
            shouldThrow<IllegalArgumentException> {
                service.process(EmbeddingRequestApi(model = "cohere-embed", input = EmbeddingInputApi(text = "hi")))
            }
        }
    })
