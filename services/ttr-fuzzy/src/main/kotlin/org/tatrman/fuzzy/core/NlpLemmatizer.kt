// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import org.slf4j.LoggerFactory

/**
 * Lemmatises tokens via `ttr-nlp`'s gRPC `BatchLemmatize` (RG-P2.S1.T4 — the
 * lemma axis ON). Each token is one batch text, so `BatchLemmatize` returns a
 * positional lemma list per token; we take the token's lead lemma and fold it.
 * Isolated-token (context-free) lemmatisation is the right model for vocabulary
 * normalisation.
 *
 * **Degradable (never a match outage):** any failure / shape mismatch logs and
 * returns the identity (folded-surface) map, so matching falls back to
 * surface-only rather than failing. The caller owns [client]'s lifecycle.
 */
class NlpLemmatizer(
    private val client: NlpBatchClient,
    private val language: String = "cs",
) : Lemmatizer {
    private val logger = LoggerFactory.getLogger(NlpLemmatizer::class.java)

    override suspend fun lemmatize(tokens: Collection<String>): Map<String, String> {
        val unique = tokens.toCollection(LinkedHashSet()).toList()
        if (unique.isEmpty()) return emptyMap()

        val fallback = unique.associateWith { TextNormalizer.fold(it) }
        return try {
            val groups = client.batchLemmatize(unique, language)
            if (groups.size != unique.size) {
                logger.warn(
                    "NLP BatchLemmatize returned {} results for {} tokens — falling back to folded surface forms",
                    groups.size,
                    unique.size,
                )
                return fallback
            }
            unique.withIndex().associate { (i, token) ->
                val lemma = groups[i].firstOrNull()
                token to (lemma?.let { TextNormalizer.fold(it) } ?: TextNormalizer.fold(token))
            }
        } catch (e: Exception) {
            logger.warn("NLP BatchLemmatize call failed ({}) — falling back to folded surface forms", e.message)
            fallback
        }
    }
}
