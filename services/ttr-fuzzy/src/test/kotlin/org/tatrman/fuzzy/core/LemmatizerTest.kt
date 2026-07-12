// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * RG-P2.S1.T4 — the lemma axis over `ttr-nlp`'s gRPC `BatchLemmatize` (positional
 * lemma lists per token). Uses a fake [NlpBatchClient] so the mapping + degrade
 * contract are unit-testable without a channel.
 */
class LemmatizerTest :
    StringSpec({

        "NoopLemmatizer folds surface forms (identity lemma)" {
            NoopLemmatizer.lemmatize(listOf("Zákazník", "ZÁKAZNÍKŮ", "Dodavatel")) shouldBe
                mapOf("Zákazník" to "zakaznik", "ZÁKAZNÍKŮ" to "zakazniku", "Dodavatel" to "dodavatel")
        }

        "NlpLemmatizer maps inflected tokens to their folded lemma (positional BatchLemmatize)" {
            val fake =
                object : NlpBatchClient {
                    override suspend fun batchLemmatize(
                        texts: List<String>,
                        language: String,
                    ): List<List<String>> =
                        texts.map { t ->
                            when (t.lowercase()) {
                                "zákazníků" -> listOf("zákazník")
                                "objednávkami" -> listOf("objednávka")
                                "praze" -> listOf("Praha")
                                else -> listOf(t) // NLP echoes the surface for unknowns
                            }
                        }
                }
            NlpLemmatizer(fake, "cs").lemmatize(listOf("Zákazníků", "objednávkami", "Praze", "untouched")) shouldBe
                mapOf(
                    "Zákazníků" to "zakaznik",
                    "objednávkami" to "objednavka",
                    "Praze" to "praha",
                    "untouched" to "untouched",
                )
        }

        "NlpLemmatizer degrades to folded surface forms on a backend error" {
            val fake =
                object : NlpBatchClient {
                    override suspend fun batchLemmatize(
                        texts: List<String>,
                        language: String,
                    ): List<List<String>> = throw RuntimeException("nlp unavailable")
                }
            NlpLemmatizer(fake, "cs").lemmatize(listOf("Zákazníků", "Dodavatel")) shouldBe
                mapOf("Zákazníků" to "zakazniku", "Dodavatel" to "dodavatel")
        }

        "NlpLemmatizer degrades on a positional shape mismatch" {
            val fake =
                object : NlpBatchClient {
                    override suspend fun batchLemmatize(
                        texts: List<String>,
                        language: String,
                    ): List<List<String>> = listOf(listOf("x")) // fewer results than tokens
                }
            NlpLemmatizer(fake, "cs").lemmatize(listOf("aaa", "bbb")) shouldBe mapOf("aaa" to "aaa", "bbb" to "bbb")
        }

        "NlpLemmatizer returns empty for no tokens" {
            val fake =
                object : NlpBatchClient {
                    override suspend fun batchLemmatize(
                        texts: List<String>,
                        language: String,
                    ): List<List<String>> = emptyList()
                }
            NlpLemmatizer(fake).lemmatize(emptyList()) shouldBe emptyMap()
        }
    })
