package org.tatrman.prometheus.model

import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.tatrman.prometheus.web.EmbeddingItemApi
import org.tatrman.prometheus.web.EmbeddingRequestApi
import org.tatrman.prometheus.web.EmbeddingResponseApi
import org.tatrman.prometheus.web.UsageApi

/**
 * Phase 09 A3 / DF-X — embeddings endpoint backing service.
 *
 * Mirrors `ModelService` for the embedding path: pick a model (request override or default
 * embedding model from `models.yaml` — first entry with `type: embedding`), call Spring AI's
 * `EmbeddingModel.call(EmbeddingRequest)`, repack the response into the OpenAI-shaped
 * `EmbeddingResponseApi`. Cost is computed from the model's `inputCost` only (embeddings have
 * no output tokens).
 *
 * Provider routing: Azure OpenAI is the only embedding provider wired in v1. Spring AI's auto-
 * config exposes the bean as `azureOpenAiEmbeddingModel`. Adding more providers (Cohere,
 * voyage-ai, …) means adding the qualifier + a `providerMap` entry; the shape stays the same.
 */
@Service
class EmbeddingService(
    private val modelRepository: ModelRepository,
    @Qualifier("azureOpenAiEmbeddingModel") private val azureEmbedding: EmbeddingModel,
) {
    private val logger = LoggerFactory.getLogger(EmbeddingService::class.java)

    fun process(request: EmbeddingRequestApi): EmbeddingResponseApi {
        val texts = request.input.asList()
        require(texts.isNotEmpty()) { "embedding request must carry at least one input text" }

        val model = resolveModel(request.model)
        val client =
            when (model.provider.lowercase()) {
                "azure" -> azureEmbedding
                else -> throw IllegalArgumentException(
                    "Provider '${model.provider}' does not have an embedding client wired (v1: azure only)",
                )
            }

        logger.info("Calling embedding API: model={} provider={} batch_size={}", model.name, model.provider, texts.size)
        val response = client.call(EmbeddingRequest(texts, null))

        val items =
            response.results.mapIndexed { idx, e ->
                EmbeddingItemApi(
                    index = idx,
                    embedding = e.output.toList(),
                )
            }

        val usage = response.metadata.usage
        val promptTokens = usage.promptTokens.toInt()
        val totalTokens = usage.totalTokens.toInt()

        return EmbeddingResponseApi(
            data = items,
            model = model.name,
            usage =
                UsageApi(
                    totalTokens = totalTokens,
                    inputTokens = promptTokens,
                    outputTokens = 0,
                    cost = costFor(model, promptTokens),
                ),
            cached = false,
        )
    }

    private fun resolveModel(requested: String?): Model {
        if (requested != null) {
            val byName = modelRepository.findByName(requested)
            if (byName != null) return byName
            logger.warn("Embedding model '{}' not found in models.yaml; falling back to default", requested)
        }
        val candidates = modelRepository.findType("embedding")
        require(candidates.isNotEmpty()) { "no embedding models configured (`type: embedding` missing in models.yaml)" }
        return candidates.first()
    }

    /**
     * Phase 09 A3 — embedding cost is `inputTokens × inputCost / 1_000_000`. Outputs are
     * irrelevant for embeddings. Falls back to legacy `cost` when `inputCost` is absent;
     * returns `null` when the model has no pricing at all.
     */
    private fun costFor(
        model: Model,
        promptTokens: Int,
    ): Double? {
        val rate = model.inputCost ?: model.cost ?: return null
        return rate * promptTokens / 1_000_000.0
    }
}
